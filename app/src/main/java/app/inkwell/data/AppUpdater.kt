package app.inkwell.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import app.inkwell.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

data class UpdateInfo(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val publishedAt: String,
    val apkUrl: String,
    val apkSize: Long,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

class AppUpdater(private val context: Context, private val http: OkHttpClient) {
    private val sp = context.getSharedPreferences("inkwell_updater", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val _auto = MutableStateFlow(sp.getBoolean("auto", true))
    val autoCheck: StateFlow<Boolean> = _auto
    fun setAutoCheck(v: Boolean) { _auto.value = v; sp.edit().putBoolean("auto", v).apply() }

    /** True when the user dismissed the dialog for this session (Later). */
    private val _dialogDismissed = MutableStateFlow(false)
    val dialogDismissed: StateFlow<Boolean> = _dialogDismissed
    fun dismissDialog() { _dialogDismissed.value = true }
    fun showDialog() { _dialogDismissed.value = false }

    fun skipVersion(v: String) {
        sp.edit().putString("skip", v).apply()
        _dialogDismissed.value = true
    }

    /** Called on app start: checks at most once per 24h, honours the toggle and skipped version. */
    fun autoCheckOnStart() {
        if (!_auto.value) return
        val last = sp.getLong("last_check", 0L)
        if (System.currentTimeMillis() - last < 24 * 3600_000L) return
        check(manual = false)
    }

    fun check(manual: Boolean = true) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        scope.launch {
            val res = runCatching { fetchLatest() }
            val st = res.fold(
                onSuccess = { info ->
                    sp.edit().putLong("last_check", System.currentTimeMillis()).apply()
                    when {
                        info == null -> UpdateState.UpToDate
                        compareVersions(info.version, BuildConfig.VERSION_NAME) <= 0 -> UpdateState.UpToDate
                        !manual && sp.getString("skip", null) == info.version -> UpdateState.UpToDate
                        else -> UpdateState.Available(info)
                    }
                },
                onFailure = { e -> if (manual) UpdateState.Error(describe(e)) else UpdateState.Idle },
            )
            if (st is UpdateState.Available) _dialogDismissed.value = false
            _state.value = st
        }
    }

    private fun describe(e: Throwable): String = when {
        e is UpdateException -> e.message ?: "Update check failed"
        e is IOException -> "Can't reach GitHub. Check your connection."
        else -> "Update check failed: ${e.message ?: e.javaClass.simpleName}"
    }

    private class UpdateException(msg: String) : Exception(msg)

    private fun fetchLatest(): UpdateInfo? {
        val req = Request.Builder().url(API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Inkwell/${BuildConfig.VERSION_NAME}")
            .build()
        http.newCall(req).execute().use { r ->
            if (r.code == 403 || r.code == 429) throw UpdateException("GitHub rate limit reached. Try again later.")
            if (r.code == 404) return null
            if (!r.isSuccessful) throw UpdateException("GitHub returned HTTP ${r.code}.")
            val o = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
            val tag = o["tag_name"]?.jsonPrimitive?.content ?: throw UpdateException("Malformed release data.")
            val apk = (o["assets"] as? JsonArray)?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk", true) == true }
            val ver = tag.removePrefix("v").removePrefix("V")
            if (apk == null) {
                if (compareVersions(ver, BuildConfig.VERSION_NAME) > 0)
                    throw UpdateException("Version $ver is out, but the release has no APK file yet.")
                return null
            }
            return UpdateInfo(
                version = ver,
                notes = o["body"]?.jsonPrimitive?.contentOrNull().orEmpty(),
                pageUrl = o["html_url"]?.jsonPrimitive?.contentOrNull().orEmpty(),
                publishedAt = o["published_at"]?.jsonPrimitive?.contentOrNull().orEmpty(),
                apkUrl = apk["browser_download_url"]!!.jsonPrimitive.content,
                apkSize = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
            )
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = if (isString) content else null

    /** Download then open installer. Safe to call repeatedly. */
    fun downloadAndInstall(info: UpdateInfo) {
        val cur = _state.value
        if (cur is UpdateState.Downloading) return
        if (cur is UpdateState.ReadyToInstall && cur.file.exists()) { install(cur.file); return }
        _state.value = UpdateState.Downloading(info, 0f)
        scope.launch {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val out = File(dir, "inkwell-${info.version}.apk")
                val req = Request.Builder().url(info.apkUrl).header("User-Agent", "Inkwell/${BuildConfig.VERSION_NAME}").build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw UpdateException("Download failed (HTTP ${r.code}).")
                    val body = r.body ?: throw UpdateException("Empty download.")
                    val total = body.contentLength().takeIf { it > 0 } ?: info.apkSize
                    var done = 0L
                    var lastEmit = 0L
                    body.byteStream().use { inp ->
                        out.outputStream().use { os ->
                            val buf = ByteArray(32 * 1024)
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                os.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (total > 0 && now - lastEmit > 100) {
                                    lastEmit = now
                                    _state.value = UpdateState.Downloading(info, (done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                }
                _state.value = UpdateState.ReadyToInstall(info, out)
                withContext(Dispatchers.Main) { install(out) }
            } catch (e: Exception) {
                _state.value = UpdateState.Error(
                    if (e is IOException && e !is java.io.FileNotFoundException) "Download interrupted. Check your connection." else describe(e)
                )
            }
        }
    }

    fun install(file: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        private const val API = "https://api.github.com/repos/anees-dahot/Inkwell/releases/latest"

        fun compareVersions(a: String, b: String): Int {
            fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
            val pa = parts(a); val pb = parts(b)
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        fun formatSize(bytes: Long): String =
            if (bytes <= 0) "" else if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "${bytes / 1024} KB"
    }
}

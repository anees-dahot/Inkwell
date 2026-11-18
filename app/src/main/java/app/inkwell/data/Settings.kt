package app.inkwell.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ReaderMode { AUTO, WEBTOON, PAGED }
enum class ReaderDirection { LTR, RTL }

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("inkwell", Context.MODE_PRIVATE)

    private val _theme = MutableStateFlow(enumOf(sp.getString("theme", null), ThemeMode.SYSTEM))
    val theme: StateFlow<ThemeMode> = _theme
    fun setTheme(v: ThemeMode) { _theme.value = v; sp.edit().putString("theme", v.name).apply() }

    private val _lang = MutableStateFlow(sp.getString("lang", "en") ?: "en")
    val lang: StateFlow<String> = _lang
    fun setLang(v: String) { _lang.value = v; sp.edit().putString("lang", v).apply() }

    private val _mode = MutableStateFlow(enumOf(sp.getString("mode", null), ReaderMode.AUTO))
    val readerMode: StateFlow<ReaderMode> = _mode
    fun setReaderMode(v: ReaderMode) { _mode.value = v; sp.edit().putString("mode", v.name).apply() }

    private val _dir = MutableStateFlow(enumOf(sp.getString("dir", null), ReaderDirection.LTR))
    val readerDirection: StateFlow<ReaderDirection> = _dir
    fun setReaderDirection(v: ReaderDirection) { _dir.value = v; sp.edit().putString("dir", v.name).apply() }

    /** Last selected source per content type (key = ContentType.name). */
    fun lastSource(type: String): String? = sp.getString("last_src_$type", null)

    /** Most-recently-used source ids for a type, newest first (max 5). */
    fun recentSources(type: String): List<String> =
        sp.getString("recent_src_$type", null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    fun rememberSource(type: String, id: String) {
        val list = (listOf(id) + recentSources(type).filter { it != id }).take(5)
        sp.edit().putString("last_src_$type", id).putString("recent_src_$type", list.joinToString(",")).apply()
    }

    /** Library auto-update interval in hours; 0 = off. */
    private val _updateHours = MutableStateFlow(sp.getInt("upd_hours", 0))
    val updateHours: StateFlow<Int> = _updateHours
    fun setUpdateHours(v: Int) { _updateHours.value = v; sp.edit().putInt("upd_hours", v).apply() }

    private val _wifiOnly = MutableStateFlow(sp.getBoolean("upd_wifi", true))
    val updateWifiOnly: StateFlow<Boolean> = _wifiOnly
    fun setUpdateWifiOnly(v: Boolean) { _wifiOnly.value = v; sp.edit().putBoolean("upd_wifi", v).apply() }

    private val _charging = MutableStateFlow(sp.getBoolean("upd_charging", false))
    val updateCharging: StateFlow<Boolean> = _charging
    fun setUpdateCharging(v: Boolean) { _charging.value = v; sp.edit().putBoolean("upd_charging", v).apply() }

    private inline fun <reified E : Enum<E>> enumOf(s: String?, def: E): E =
        runCatching { enumValueOf<E>(s!!) }.getOrDefault(def)
}

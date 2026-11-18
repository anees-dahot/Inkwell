package app.inkwell.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Renders very tall images (webtoon strips) as a stack of horizontal tiles.
 *
 * A single long-strip page can be 800×15000 px, beyond the GPU's max texture size
 * (4096 on many devices). Uploading it as one bitmap aborts the RenderThread
 * (`glTexSubImage2D GL_INVALID_VALUE`). We download to disk, then decode
 * ≤[TILE_PX]-tall slices with [BitmapRegionDecoder], sub-sampled to the view width.
 */
@Composable
fun TiledImage(
    url: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    placeholder: @Composable () -> Unit,
    error: @Composable (retry: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val targetWidth = constraints.maxWidth.coerceAtLeast(1)
        var attempt by remember(url) { mutableStateOf(0) }
        var state by remember(url) { mutableStateOf<TileState>(TileState.Loading) }
        LaunchedEffect(url, targetWidth, attempt) {
            state = TileState.Loading
            state = runCatching { loadTiles(context, url, targetWidth) }
                .fold({ TileState.Ready(it) }, { TileState.Failed })
        }
        when (val s = state) {
            TileState.Loading -> placeholder()
            TileState.Failed -> error { attempt++ }
            is TileState.Ready -> Column(Modifier.fillMaxWidth()) {
                s.tiles.forEach { t ->
                    Image(
                        t, contentDescription,
                        Modifier.fillMaxWidth().aspectRatio(t.width.toFloat() / t.height),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }
        }
    }
}

private sealed interface TileState {
    data object Loading : TileState
    data object Failed : TileState
    class Ready(val tiles: List<ImageBitmap>) : TileState
}

private const val TILE_PX = 2048

private val client by lazy {
    OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder().header("User-Agent", "Inkwell/0.1 (Android)")
            if (req.header("Referer") == null) {
                app.inkwell.data.source.ImageReferers.forHost(req.url.host)?.let {
                    b.header("Referer", it).header("User-Agent", app.inkwell.data.source.MangaDexSource.UA)
                }
            }
            chain.proceed(b.build())
        }
        .build()
}

private suspend fun loadTiles(context: Context, url: String, targetWidth: Int): List<ImageBitmap> =
    withContext(Dispatchers.IO) {
        val file = download(context, url)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        require(w > 0 && h > 0) { "Undecodable image" }

        // Largest power-of-two sample that keeps width >= view width (no upscale blur).
        var sample = 1
        while (w / (sample * 2) >= targetWidth) sample *= 2

        val decoder = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(file.path)
        else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(file.path, false)
        checkNotNull(decoder) { "No region decoder" }
        try {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565 // pages are opaque; halves memory
            }
            val step = TILE_PX * sample
            (0 until h step step).map { top ->
                decoder.decodeRegion(Rect(0, top, w, minOf(top + step, h)), opts).asImageBitmap()
            }
        } finally {
            decoder.recycle()
        }
    }

private fun download(context: Context, url: String): File {
    // downloaded chapters are served as local files
    if (url.startsWith("file:")) return File(java.net.URI(url))
    if (url.startsWith("/")) return File(url)
    val dir = File(context.cacheDir, "pages").apply { mkdirs() }
    val name = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    val file = File(dir, name)
    if (file.length() > 0) return file
    val tmp = File(dir, "$name.part")
    client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        check(r.isSuccessful) { "HTTP ${r.code}" }
        tmp.outputStream().use { out -> r.body!!.byteStream().copyTo(out) }
    }
    check(tmp.renameTo(file)) { "Cache write failed" }
    trimCache(dir)
    return file
}

/** Keep the page cache under ~200 MB, evicting oldest files first. */
private fun trimCache(dir: File, maxBytes: Long = 200L shl 20) {
    val files = dir.listFiles()?.filter { !it.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
    var total = files.sumOf { it.length() }
    for (f in files) {
        if (total <= maxBytes) break
        total -= f.length()
        f.delete()
    }
}

/** Downloads (or reuses the disk cache of) a page image and returns the file. */
suspend fun fetchPageFile(context: Context, url: String): File = withContext(Dispatchers.IO) { download(context, url) }

/** Warm the page cache used by [TiledImage]; failures are ignored. */
suspend fun prefetchPage(context: Context, url: String) { runCatching { fetchPageFile(context, url) } }

/** Width / height of the image (downloads into the page cache), or null on failure. */
suspend fun probeAspect(context: Context, url: String): Float? = withContext(Dispatchers.IO) {
    runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(download(context, url).path, o)
        if (o.outWidth > 0 && o.outHeight > 0) o.outWidth.toFloat() / o.outHeight else null
    }.getOrNull()
}

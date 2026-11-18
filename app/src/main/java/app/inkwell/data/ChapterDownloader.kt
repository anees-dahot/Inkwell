package app.inkwell.data

import app.inkwell.data.db.DownloadItem
import app.inkwell.data.model.MangaSource
import app.inkwell.data.model.NovelSource
import app.inkwell.data.source.ImageReferers
import app.inkwell.data.source.SourceRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fetches one chapter (manga page images or novel text) into the [DownloadStore]. */
class ChapterDownloader(
    private val registry: SourceRegistry,
    private val http: OkHttpClient,
    private val store: DownloadStore,
) {
    /** Returns bytes written. Throws on failure; partial output is removed. */
    suspend fun download(item: DownloadItem, onProgress: suspend (done: Int, total: Int) -> Unit): Long {
        val s = item.sourceId; val e = item.entryId; val ep = item.episodeId
        val tmp = store.tmpDir(s, e, ep)
        tmp.deleteRecursively(); tmp.mkdirs()
        try {
            val bytes: Long = when (val src = registry.get(s)) {
                is MangaSource -> pages(src, ep, tmp, onProgress)
                is NovelSource -> {
                    onProgress(0, 1)
                    val text = src.chapterText(ep)
                    if (text.isEmpty()) throw IOException("No readable content")
                    store.writeText(tmp, text).also { onProgress(1, 1) }
                }
                else -> throw IOException("Downloads are not supported for this source")
            }
            val dest = store.dir(s, e, ep)
            dest.deleteRecursively()
            if (!tmp.renameTo(dest)) throw IOException("Could not save chapter")
            return bytes
        } catch (t: Throwable) {
            withContext(NonCancellable) { tmp.deleteRecursively() }
            throw t
        }
    }

    private suspend fun pages(src: MangaSource, ep: String, dir: File, onProgress: suspend (Int, Int) -> Unit): Long = coroutineScope {
        val pages = src.pages(ep)
        if (pages.isEmpty()) throw IOException("No pages")
        onProgress(0, pages.size)
        val done = AtomicInteger(0)
        val total = java.util.concurrent.atomic.AtomicLong(0)
        val sem = Semaphore(3)
        pages.mapIndexed { i, p ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    total.addAndGet(fetch(p.imageUrl, store.pageFile(dir, i)))
                    onProgress(done.incrementAndGet(), pages.size)
                }
            }
        }.awaitAll()
        total.get()
    }

    private suspend fun fetch(url: String, file: File): Long {
        if (!url.startsWith("http")) throw IOException("Unsupported image url")
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                val b = Request.Builder().url(url).header("User-Agent", "Inkwell/0.1 (Android)")
                ImageReferers.forHost(b.build().url.host)?.let { b.header("Referer", it) }
                http.newCall(b.build()).await().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    return withContext(Dispatchers.IO) { r.body!!.byteStream().use { i -> file.outputStream().use { o -> i.copyTo(o) } } }
                }
            } catch (c: CancellationException) { throw c } catch (x: Exception) { last = x; delay(600L * (attempt + 1)) }
        }
        throw last ?: IOException("Download failed")
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) { cont.resume(response) }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }
}

package app.inkwell.data

import app.inkwell.data.db.DownloadItem
import app.inkwell.data.model.MangaSource
import app.inkwell.data.model.NovelSource
import app.inkwell.data.model.cleanPages
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
    /** Returns bytes written. Throws on failure; partial output stays for the next attempt to resume. */
    suspend fun download(item: DownloadItem, onProgress: suspend (done: Int, total: Int) -> Unit): Long {
        val s = item.sourceId; val e = item.entryId; val ep = item.episodeId
        val tmp = store.tmpDir(s, e, ep)
        // keep pages already fetched by an earlier, interrupted attempt (slow / dropped connections)
        tmp.mkdirs(); tmp.setLastModified(System.currentTimeMillis())
        // Partial output is kept so pause / retry / a dropped connection resume where they left off;
        // cancel and delete remove it (DownloadStore.delete), stale leftovers age out.
        val bytes: Long = when (val src = registry.get(s)) {
            is MangaSource -> pages(src, ep, tmp, onProgress)
            is NovelSource -> {
                onProgress(0, 1)
                val text = patiently { src.chapterText(ep) }
                if (text.isEmpty()) throw IOException("No readable content")
                store.writeText(tmp, text).also { onProgress(1, 1) }
            }
            else -> throw IOException("Downloads are not supported for this source")
        }
        val dest = store.dir(s, e, ep)
        dest.deleteRecursively()
        if (!tmp.renameTo(dest)) throw IOException("Could not save chapter")
        return bytes
    }

    private suspend fun pages(src: MangaSource, ep: String, dir: File, onProgress: suspend (Int, Int) -> Unit): Long = coroutineScope {
        val pages = patiently { src.pages(ep) }.cleanPages()
        if (pages.isEmpty()) throw IOException("No pages")
        onProgress(0, pages.size)
        val done = AtomicInteger(0)
        val total = java.util.concurrent.atomic.AtomicLong(0)
        val sem = Semaphore(3)
        pages.mapIndexed { i, p ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val f = store.pageFile(dir, i)
                    total.addAndGet(if (f.length() > 0) f.length() else fetch(p.imageUrl, f))
                    onProgress(done.incrementAndGet(), pages.size)
                }
            }
        }.awaitAll()
        total.get()
    }

    /** Retries a source call through flaky / dropped connections, waiting for the network to return. */
    private suspend fun <T> patiently(block: suspend () -> T): T {
        var last: Exception? = null
        repeat(4) { attempt ->
            try { return block() } catch (c: CancellationException) { throw c } catch (x: Exception) {
                if (x is app.inkwell.data.source.CloudflareException) throw x
                last = x
                var waited = 0L
                while (!app.inkwell.data.source.Net.online && waited < 120_000) { delay(2_000); waited += 2_000 }
                delay(1_500L * (attempt + 1))
            }
        }
        throw last ?: IOException("Download failed")
    }

    private suspend fun fetch(url: String, file: File): Long {
        if (!url.startsWith("http")) throw IOException("Unsupported image url")
        var last: Exception? = null
        val part = File(file.path + ".part")
        repeat(5) { attempt ->
            try {
                // headers (UA, Referer) and transient-error retries come from the shared image client
                http.newCall(Request.Builder().url(url).build()).await().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val n = withContext(Dispatchers.IO) { r.body!!.byteStream().use { i -> part.outputStream().use { o -> i.copyTo(o) } } }
                    if (n <= 0) throw IOException("Empty image")
                    if (!part.renameTo(file)) throw IOException("Could not save page")
                    return n
                }
            } catch (c: CancellationException) { part.delete(); throw c } catch (x: Exception) {
                part.delete(); last = x
                // back off longer each time; wait for connectivity to come back before trying again
                var waited = 0L
                while (!app.inkwell.data.source.Net.online && waited < 60_000) { delay(2_000); waited += 2_000 }
                delay(1_000L * (attempt + 1) * (attempt + 1))
            }
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

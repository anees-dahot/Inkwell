package app.inkwell

import app.inkwell.data.model.MangaSource
import app.inkwell.data.model.NovelSource
import app.inkwell.data.model.Source
import app.inkwell.data.source.manga.mangaSources
import app.inkwell.data.source.novel.novelSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Live network smoke test: popular -> details -> episodes -> content for every scraper source.
 * Opt-in: ./gradlew :app:testDebugUnitTest -Psmoke --tests app.inkwell.SourceSmokeTest
 * Report written to app/build/smoke-report.tsv. Subset: add -Ponly=id1,id2 (writes build/smoke-<ids>.tsv)
 */
class SourceSmokeTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private suspend fun check(s: Source): String {
        val stage = StringBuilder()
        return try {
            withTimeout(90_000) {
                stage.append("popular")
                val list = s.popular(1).items
                if (list.isEmpty()) return@withTimeout "FAIL\tpopular empty"
                stage.clear().append("details")
                val e = list.first()
                val d = s.details(e.id)
                stage.clear().append("episodes")
                val eps = s.episodes(e.id).ifEmpty { s.episodes(d.entry.id) }
                if (eps.isEmpty()) return@withTimeout "WARN\t${list.size} items, 0 episodes (${e.title})"
                stage.clear().append("content")
                // first chapters are often image-only (covers/illustrations): try a few
                var n = 0
                for (ep in eps.take(4)) {
                    n = when (s) {
                        is NovelSource -> s.chapterText(ep.id).size
                        is MangaSource -> s.pages(ep.id).size
                        else -> -1
                    }
                    if (n > 0) break
                }
                if (n <= 0) "WARN\t${list.size} items, ${eps.size} eps, empty content"
                else "OK\t${list.size} items, ${eps.size} eps, $n paras/pages"
            }
        } catch (t: Throwable) {
            "FAIL\t$stage: ${t::class.simpleName}: ${t.message?.take(140)?.replace('\n', ' ')}"
        }
    }

    @Test
    fun allSources() {
        assumeTrue(System.getProperty("smoke") != null)
        val only = System.getProperty("only")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
        val sources = (novelSources(client) + mangaSources(client))
            .filter { only == null || it.id.lowercase() in only }
        val sem = Semaphore(12)
        val rows = runBlocking(Dispatchers.IO) {
            sources.map { s -> async { sem.withPermit { "${s.id}\t${check(s)}" } } }.awaitAll()
        }
        val out = File(if (only == null) "build/smoke-report.tsv" else "build/smoke-${only.joinToString("_").take(60)}.tsv")
        out.writeText(rows.sorted().joinToString("\n") + "\n")
        println(rows.groupBy { it.split('\t')[1] }.mapValues { it.value.size })
    }
}

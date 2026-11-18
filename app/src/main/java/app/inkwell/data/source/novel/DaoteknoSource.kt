package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * DaoTekno (daotekno.com) — a custom PHP reader serving the DaoTranslate.com
 * catalog. Uses the shared WebView UA (minus "; wv") so cf_clearance matches.
 */
class DaoteknoSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "daotekno"
    override val name = "DaoTekno"
    override val baseUrl = "https://daotekno.com"
    override val lang = "en"

    private val maxRateLimitRetries = 4
    private val maxRetryAfterSeconds = 30

    /** GET with a wait-and-retry for the site's HTTP 429 rate limiting. */
    private suspend fun fetchHtml(url: String): String = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            val res = client.newCall(request(url)).execute()
            if (res.code == 429 && attempt < maxRateLimitRetries) {
                val seconds = res.header("retry-after")?.trim()?.toIntOrNull()
                val waitMs = when {
                    seconds == null || seconds < 0 -> 10_000L
                    seconds > maxRetryAfterSeconds -> null
                    else -> (seconds + 1) * 1000L
                }
                res.close()
                if (waitMs != null) {
                    attempt++
                    delay(waitMs)
                    continue
                }
            }
            if (res.code == 403 || res.code == 503) throw CloudflareException(url)
            check(res.isSuccessful) { "$name HTTP ${res.code}" }
            return@withContext res.body?.string().orEmpty()
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    private fun numFromName(n: String): Float? =
        Regex("(\\d+(?:\\.\\d+)?)").findAll(n).lastOrNull()?.value?.toFloatOrNull()

    private fun parseNovelCards(html: String): List<Entry> {
        val d = Jsoup.parse(html)
        return d.select("a.novel-card.novel-item").mapNotNull { el ->
            val href = el.attr("href").removePrefix("/")
            var nm = el.selectFirst(".novel-title")?.text()?.trim().orEmpty()
            if (nm.isEmpty()) nm = el.text().replace(Regex("Index\\s*»"), "").trim()
            if (href.isEmpty() || nm.isEmpty()) return@mapNotNull null
            Entry(id, href, nm, null, ContentType.NOVEL)
        }
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val html = fetchHtml("$baseUrl/?page=$page")
        // Out-of-range pages are clamped to the last page; compare against the
        // "N / M" page marker to stop pagination.
        val marker = Jsoup.parse(html).selectFirst("span.btn-pagi.active")?.text().orEmpty()
        val totalPages = marker.substringAfter('/', "").trim().toIntOrNull()
        if (totalPages != null && page > totalPages) return PagedResult(emptyList(), false)
        val items = parseNovelCards(html)
        return PagedResult(items, totalPages == null || page < totalPages)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        // Search is filtered server-side and served as a single page.
        if (page > 1) return PagedResult(emptyList(), false)
        val q = URLEncoder.encode(query, "UTF-8")
        return PagedResult(parseNovelCards(fetchHtml("$baseUrl/?q=$q")), false)
    }

    // ---- details -----------------------------------------------------------

    private fun nextChapterPage(d: Document): String? =
        d.select("a.btn-page").firstOrNull { it.text().contains("Next") }?.attr("href")

    private fun chapterList(d: Document): List<Episode> =
        d.select("#allChaptersGrid > a.chapter-box").mapNotNull { el ->
            val href = el.attr("href").removePrefix("/")
            val nm = el.text().replace(Regex("\\s+"), " ").trim()
            if (href.isEmpty() || nm.isEmpty()) return@mapNotNull null
            Episode(id = href, name = nm, number = numFromName(nm), dateMillis = null)
        }

    private suspend fun loadChapters(entryId: String): List<Episode> {
        val first = Jsoup.parse(fetchHtml(absPath(entryId)))
        val chapters = chapterList(first).toMutableList()
        var next = nextChapterPage(first)
        var pages = 1
        while (next != null && pages < 100) {
            val d = Jsoup.parse(fetchHtml(absPath(entryId) + next))
            chapters += chapterList(d)
            next = nextChapterPage(d)
            pages++
        }
        // Every page lists the newest chapter first; reading order is reversed.
        return chapters.reversed()
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val first = Jsoup.parse(fetchHtml(absPath(entryId)))
        val title = first.selectFirst("h1")?.text()?.trim()
            ?.takeUnless { it.isEmpty() } ?: "Untitled"
        val chapters = loadChapters(entryId)
        return EntryDetails(
            entry = Entry(id, entryId, title, null, ContentType.NOVEL),
            description = null,
            authors = emptyList(),
            genres = emptyList(),
            status = null,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        return loadChapters(entryId).also { cachedChapters[entryId] = it }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = Jsoup.parse(fetchHtml(absPath(episodeId)))
        val content = d.selectFirst("#novel-content") ?: return emptyList()
        content.select("script, style, .promo").remove()
        return paragraphsOf(content)
    }
}

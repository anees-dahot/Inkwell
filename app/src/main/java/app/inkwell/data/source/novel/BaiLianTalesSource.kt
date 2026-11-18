package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Port of LNReader's BaiLianTales plugin (jormuntl WordPress theme).
 *
 * - Listing:      `novel/page/<n>/?orderby=…`, cards are `article.novel-card`.
 * - Search:       `page/<n>/?s=<term>`, results in `#jt-search-results`.
 * - Novel page:   `novel/<slug>/`; the TOC shows 50 chapters per page, further
 *                 pages are plain links (`?toc_page=<n>`).
 * - Chapter page: text in `.chapter-content`; premium chapters render a login
 *                 overlay and are listed as locked (🔒).
 */
class BaiLianTalesSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "bailiantales"
    override val name = "BaiLianTales"
    override val baseUrl = "https://bailiantales.com"
    override val lang = "en"

    // ---- requests ----------------------------------------------------------

    /** Paging past the last listing or search page is a 404, not an error. */
    private suspend fun fetchPage(url: String): Document? = withContext(Dispatchers.IO) {
        client.newCall(request(url)).execute().use { r ->
            if (r.code == 404) return@withContext null
            check(r.isSuccessful) { "$name HTTP ${r.code}" }
            Jsoup.parse(r.body?.string().orEmpty(), url)
        }
    }

    private fun toPath(href: String?): String? {
        val prefix = "$baseUrl/"
        if (href.isNullOrEmpty() || !href.startsWith(prefix)) return null
        return href.removePrefix(prefix)
    }

    // ---- listing -----------------------------------------------------------

    private suspend fun listPage(pageNo: Int, orderby: String): PagedResult<Entry> {
        val d = fetchPage(absPath("novel/page/$pageNo") + "/?orderby=$orderby")
            ?: return PagedResult(emptyList(), false)
        val items = d.select("article.novel-card").mapNotNull { el ->
            val link = el.selectFirst("h3 a") ?: return@mapNotNull null
            val path = toPath(link.attr("href")) ?: return@mapNotNull null
            Entry(
                sourceId = id,
                id = path,
                title = link.text().trim().ifBlank { "Untitled" },
                coverUrl = abs(el.selectFirst("img")?.attr("src")),
                type = ContentType.NOVEL,
            )
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = listPage(page, "date")
    override suspend fun latest(page: Int) = listPage(page, "updated")

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val term = URLEncoder.encode(query.trim(), "UTF-8")
        val d = fetchPage(absPath("page/$page") + "/?s=$term")
            ?: return PagedResult(emptyList(), false)
        val items = d.select("#jt-search-results article").mapNotNull { el ->
            val link = el.selectFirst("h3 a") ?: return@mapNotNull null
            val path = toPath(link.attr("href")) ?: return@mapNotNull null
            // Only novel pages (`novel/<slug>/`), not chapters or other posts.
            if (!Regex("^novel/[^/]+/$").matches(path)) return@mapNotNull null
            Entry(id, path, link.text().trim().ifBlank { "Untitled" }, abs(el.selectFirst("img")?.attr("src")), ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    // ---- chapters ----------------------------------------------------------

    private fun chapterListOf(d: Document): List<Episode> =
        d.select("#novel-toc .chapter-item-improved").mapNotNull { el ->
            val path = toPath(el.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val locked = el.selectFirst("[data-jt-unlock]") != null
            val chapterName = el.selectFirst(".jt-toc-title")?.text()?.trim().orEmpty()
            Episode(
                id = path,
                name = if (locked) "🔒 $chapterName" else chapterName,
                number = null,
                dateMillis = parseDate(el.selectFirst(".jt-toc-date")?.text()?.trim()),
            )
        }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val d = fetchPage(absPath(entryId)) ?: throw IllegalStateException("$name: novel not found")
        val title = d.selectFirst(".jt-novel-details h1")?.text()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: "Untitled"
        val summary = d.select(".jt-hero-synopsis p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && !Regex("synopsis:?", RegexOption.IGNORE_CASE).matches(it) }
            .joinToString("\n\n")
        val genres = d.select(".jt-novel-details a.genre-tag").eachText().map { it.trim() }
        val statusRaw = d.selectFirst(".jt-novel-details .status-badge")?.text()?.trim()?.lowercase()
        val status = when (statusRaw) {
            "ongoing" -> "Ongoing"
            "completed" -> "Completed"
            "hiatus" -> "On Hiatus"
            else -> null
        }
        val cover = d.selectFirst(".novel-hero img.wp-post-image")?.attr("src")

        val chapters = chapterListOf(d).toMutableList()
        val tocPages = d.select("#novel-toc .jt-page-jump option").size
        for (page in 2..tocPages) {
            fetchPage(absPath(entryId) + "?toc_page=$page")?.let { chapters.addAll(chapterListOf(it)) }
        }

        return EntryDetails(
            entry = Entry(id, entryId, title, abs(cover), ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = emptyList(),
            genres = genres,
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter text ------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = fetchPage(absPath(episodeId)) ?: return emptyList()
        // Premium chapters render a login overlay instead of the text.
        if (d.selectFirst(".locked-chapter-overlay") != null) return emptyList()
        val content = d.selectFirst(".chapter-content") ?: return emptyList()
        // Strip the hidden anti-copy watermarks ("[bailiantales.com]", …).
        content.select("script, style, .sr-only, .watermark-hidden, [data-watermark], [aria-hidden=\"true\"]").remove()
        val hiddenStyle = Regex("display\\s*:\\s*none|font-size\\s*:\\s*0(?![.\\d])", RegexOption.IGNORE_CASE)
        content.select("[style]").forEach { el ->
            if (hiddenStyle.containsMatchIn(el.attr("style"))) el.remove()
        }
        (content.select("*") + content).forEach { el ->
            el.childNodes().filterIsInstance<Comment>().forEach { it.remove() }
        }
        return paragraphsOf(content)
    }
}

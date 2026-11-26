package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Base for sites running the WordPress "Madara" manga theme
 * (port of Keiyoushi lib-multisrc/madara). Subclasses only set id/name/baseUrl
 * and override the open knobs below when a site deviates.
 */
abstract class MadaraSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    override val lang = "en"
    override val headers: Map<String, String> get() = mapOf("Referer" to "$baseUrl/")

    /** URL path segment of the series directory (…/manga/slug/). */
    protected open val mangaSubString = "manga"
    protected open val popularOrder = "views"
    protected open val latestOrder = "latest"

    protected open val listItemSelector = "div.page-item-detail"
    protected open val searchItemSelector = ".c-tabs-item__content"
    protected open val titleSelector = ".post-title a"
    protected open val thumbSelector = ".item-thumb img, .tab-thumb img"

    protected open val chapterSelector = "li.wp-manga-chapter"
    protected open val pageImageSelector = ".reading-content img, div.page-break img"

    /** java.time patterns tried (English locale) on `.chapter-release-date`. */
    protected open val dateFormats = listOf("MMMM d, yyyy", "MMM d, yyyy", "dd.MM.yyyy", "dd/MM/yyyy", "yyyy-MM-dd")

    protected fun path(url: String): String = url.toHttpUrl().encodedPath

    protected fun imgUrl(e: Element?): String? {
        e ?: return null
        for (a in listOf("data-src", "data-lazy-src", "data-cfsrc", "src")) {
            val v = e.attr(a).trim()
            if (v.isNotEmpty() && !v.startsWith("data:")) return abs(v)
        }
        return null
    }

    protected suspend fun listing(url: String, itemSel: String): PagedResult<Entry> {
        val d = try {
            doc(url)
        } catch (e: IllegalStateException) {
            if (e.message?.contains("404") == true) return PagedResult(emptyList(), false) else throw e
        }
        val items = d.select(itemSel).mapNotNull { el ->
            val a = el.selectFirst(titleSelector) ?: return@mapNotNull null
            val href = a.absUrl("href").ifEmpty { return@mapNotNull null }
            val latest = app.inkwell.data.model.chapterNumber(el.selectFirst(".chapter-item .chapter a, .list-chapter .chapter a, .latest-chap .chapter a, span.chapter a")?.text())
            Entry(id, path(href), a.text().trim(), ref(imgUrl(el.selectFirst(thumbSelector))), type, latest)
        }.distinctBy { it.id }
        return PagedResult(items, items.isNotEmpty())
    }

    protected open fun browseUrl(page: Int, order: String) =
        "$baseUrl/$mangaSubString/page/$page/?m_orderby=$order"

    override suspend fun popular(page: Int) = listing(browseUrl(page, popularOrder), listItemSelector)

    /** Genre archive: /manga-genre/<slug>/ (Madara default). */
    protected open val genreSubString = "manga-genre"
    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> =
        listing("$baseUrl/$genreSubString/${app.inkwell.data.model.tagSlug(tag)}/page/$page/?m_orderby=$popularOrder", listItemSelector)
    override suspend fun latest(page: Int) = listing(browseUrl(page, latestOrder), listItemSelector)

    protected open fun searchUrl(query: String, page: Int): String =
        "$baseUrl/page/$page/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query.trim())
            .addQueryParameter("post_type", "wp-manga").build().toString()

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        return listing(searchUrl(query, page), searchItemSelector)
    }

    private fun item(d: Document, heading: String): Element? =
        d.select(".post-content_item").firstOrNull {
            it.selectFirst("h5, .summary-heading")?.text()?.trim()?.startsWith(heading, true) == true
        }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val titleEl = d.selectFirst(".post-title h1, .post-title h3") ?: error("Page not found")
        titleEl.select("span, .manga-title-badges").remove()
        val descEl = d.selectFirst("div.description-summary div.summary__content, div.summary_content .post-content_item > h5 + div, .manga-excerpt")
        val desc = descEl?.select("p")?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() && !it.startsWith("A brief description", true) }
            ?.joinToString("\n\n")?.ifEmpty { null } ?: descEl?.text()?.trim()?.ifEmpty { null }
        val status = item(d, "Status")?.selectFirst(".summary-content")?.text()?.trim()?.let {
            when {
                it.contains("complete", true) || it.contains("end", true) -> "Completed"
                it.contains("ongoing", true) -> "Ongoing"
                it.contains("hiatus", true) -> "Hiatus"
                it.contains("cancel", true) || it.contains("drop", true) -> "Cancelled"
                else -> it.ifEmpty { null }
            }
        }
        val authors = (d.select(".author-content a") + d.select(".artist-content a"))
            .map { it.text().trim() }.filter { it.isNotEmpty() && !it.equals("updating", true) }.distinct()
        return EntryDetails(
            entry = Entry(id, entryId, titleEl.text().trim(), ref(imgUrl(d.selectFirst(".summary_image img"))), type),
            description = desc,
            authors = authors,
            genres = d.select(".genres-content a").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = status,
        )
    }

    private fun toMillis(raw: String?): Long? {
        val s = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        for (f in dateFormats) runCatching {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern(f, Locale.ENGLISH))
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return parseDate(s)
    }

    protected fun parseChapters(d: Document): List<Episode> = d.select(chapterSelector).mapNotNull { el ->
        val a = el.selectFirst("a") ?: return@mapNotNull null
        val href = a.absUrl("href").ifEmpty { return@mapNotNull null }
        val name = a.text().trim()
        val dateRaw = el.selectFirst(".chapter-release-date")?.text()?.trim()
            ?: el.selectFirst("a.c-new-tag")?.attr("title")
        Episode(
            id = path(href).trimEnd('/'),
            name = name,
            number = Regex("""\d+(?:\.\d+)?""").find(name)?.value?.toFloatOrNull(),
            dateMillis = toMillis(dateRaw),
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val url = absPath(entryId).trimEnd('/') + "/"
        val d = doc(url)
        var list = parseChapters(d)
        if (list.isEmpty()) {
            list = runCatching { parseChapters(formPost(url + "ajax/chapters/", emptyMap())) }.getOrDefault(emptyList())
        }
        if (list.isEmpty()) {
            val postId = d.selectFirst("#manga-chapters-holder[data-id], [data-post]")?.let { it.attr("data-id").ifEmpty { it.attr("data-post") } }
                ?: d.selectFirst("input.rating-post-id, input#manga-chapters-holder")?.attr("value")
            if (!postId.isNullOrEmpty()) {
                list = runCatching {
                    parseChapters(formPost("$baseUrl/wp-admin/admin-ajax.php", mapOf("action" to "manga_get_chapters", "manga" to postId)))
                }.getOrDefault(emptyList())
            }
        }
        return list
    }

    override suspend fun pages(episodeId: String): List<Page> {
        val url = absPath(episodeId).trimEnd('/') + "/"
        return doc(url).select(pageImageSelector).mapNotNull { imgUrl(it) }.distinct()
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
    }
}

package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
import java.util.Locale

/** Standalone Madara-theme scraper shared by the adult manhwa sites (own copy, independent of MadaraSource). */
abstract class AdultMadaraSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    /** URL segment of the series listing, e.g. "manga" or "series". */
    protected abstract val seriesPath: String
    override val lang = "en"
    override val headers get() = mapOf("Referer" to "$baseUrl/")

    private fun img(e: Element?): String? = e?.let {
        listOf("data-src", "data-lazy-src", "src").firstNotNullOfOrNull { a -> it.absUrl(a).ifEmpty { null } }
    }
    private fun path(url: String) = url.toHttpUrl().encodedPath

    private fun parseList(d: org.jsoup.nodes.Document): PagedResult<Entry> {
        val items = d.select("div.page-item-detail, div.c-tabs-item__content").mapNotNull { el ->
            val a = el.select(".post-title a").firstOrNull { it.text().isNotBlank() } ?: return@mapNotNull null
            val href = a.absUrl("href").ifEmpty { return@mapNotNull null }
            Entry(id, path(href), a.text().trim(), ref(img(el.selectFirst("img"))), type,
                app.inkwell.data.model.chapterNumber(el.selectFirst(".chapter-item .chapter a, .latest-chap .chapter a, span.chapter a")?.text()))
        }.distinctBy { it.id }
        val next = d.selectFirst("a.nextpostslink, .nav-previous a, a.next.page-numbers") != null
        return PagedResult(items, next)
    }

    private suspend fun listing(order: String?, query: String?, page: Int): PagedResult<Entry> {
        val url = if (query != null) {
            "$baseUrl/page/$page/".toHttpUrl().newBuilder()
                .addQueryParameter("s", query).addQueryParameter("post_type", "wp-manga").build()
        } else {
            "$baseUrl/$seriesPath/page/$page/".toHttpUrl().newBuilder()
                .addQueryParameter("m_orderby", order!!).build()
        }
        return parseList(doc(url))
    }

    override suspend fun popular(page: Int) = listing("views", null, page)
    override suspend fun latest(page: Int) = listing("latest", null, page)

    /** Taxonomy URL segment of genre archives; sites that rename it override this. */
    protected open val genreSubString = "manga-genre"
    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> {
        val slug = app.inkwell.data.model.tagSlug(tag).ifEmpty { return PagedResult(emptyList(), false) }
        val d = try {
            doc("$baseUrl/$genreSubString/$slug/page/$page/?m_orderby=views")
        } catch (e: IllegalStateException) {
            if (e.message?.contains("404") == true) return PagedResult(emptyList(), false) else throw e
        }
        return parseList(d)
    }
    override suspend fun search(query: String, page: Int): PagedResult<Entry> =
        if (query.isBlank()) popular(page) else listing(null, query.trim(), page)

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val h1 = d.selectFirst(".post-title h1") ?: error("Page not found")
        h1.select("span").remove()
        fun field(label: String) = d.select(".post-content_item").firstOrNull {
            it.selectFirst("h5, h4")?.text()?.trim()?.lowercase(Locale.ROOT)?.startsWith(label) == true
        }?.selectFirst(".summary-content")
        val authors = field("author")?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val genres = field("genre")?.select("a")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val status = field("status")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val desc = d.selectFirst("div.description-summary div.summary__content, .manga-excerpt")?.text()?.trim()
        return EntryDetails(
            entry = Entry(id, entryId, h1.text().trim(), ref(img(d.selectFirst("div.summary_image img"))), type),
            description = desc?.ifEmpty { null },
            authors = authors,
            genres = genres,
            status = status,
        )
    }

    private fun parseChapters(d: org.jsoup.nodes.Document): List<Episode> =
        d.select("li.wp-manga-chapter").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = a.absUrl("href").ifEmpty { return@mapNotNull null }
            val name = a.text().trim()
            val dateText = el.selectFirst(".chapter-release-date i, .chapter-release-date")?.text()?.trim()
            Episode(
                id = path(href),
                name = name,
                number = Regex("""\d+(?:\.\d+)?""").find(name)?.value?.toFloatOrNull(),
                dateMillis = parseDate(dateText, "MMMM d, yyyy", "MMM d, yyyy"),
            )
        }

    override suspend fun episodes(entryId: String): List<Episode> {
        val d = doc(absPath(entryId))
        val inline = parseChapters(d)
        if (inline.isNotEmpty()) return inline
        val mid = d.selectFirst("[data-id]#manga-chapters-holder, #manga-chapters-holder")?.attr("data-id")
            ?: Regex("\"manga_id\":\"?(\\d+)").find(d.html())?.groupValues?.get(1)
        if (mid != null) {
            val r = formPost("$baseUrl/wp-admin/admin-ajax.php", mapOf("action" to "manga_get_chapters", "manga" to mid))
            val l = parseChapters(r)
            if (l.isNotEmpty()) return l
        }
        return parseChapters(formPost(absPath(entryId).trimEnd('/') + "/ajax/chapters/", emptyMap()))
    }

    override suspend fun pages(episodeId: String): List<Page> =
        doc(absPath(episodeId)).select("div.reading-content img, .page-break img")
            .mapNotNull { img(it)?.trim()?.replace("http://", "https://") }
            .filter { it.isNotEmpty() && !it.contains("/assets/publication/") }
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
}

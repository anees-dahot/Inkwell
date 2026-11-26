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

/** Port of Mihon's WeebCentral extension (weebcentral.com). Entry id = "series/{ulid}", episode id = "chapters/{ulid}". */
class WeebCentralSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    override val baseUrl = "https://weebcentral.com"
    override val id = "weebcentral"
    override val name = "Weeb Central"
    override val lang = "en"

    override val headers = mapOf("Referer" to "$baseUrl/")

    private val pageSize = 32

    private fun seriesId(url: String) = Regex("""series/([0-9A-Za-z]+)""").find(url)?.groupValues?.get(1)
    private fun cover(sid: String) = ref("https://temp.compsci88.com/cover/fallback/$sid.jpg")

    private suspend fun browse(page: Int, sort: String, text: String? = null, tag: String? = null): PagedResult<Entry> {
        val url = "$baseUrl/search/data".toHttpUrl().newBuilder()
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())
            .apply { if (!text.isNullOrBlank()) addQueryParameter("text", text.trim()) }
            .apply { if (tag != null) addQueryParameter("included_tag", tag) }
            .addQueryParameter("sort", sort)
            .addQueryParameter("order", "Descending")
            .addQueryParameter("official", "Any")
            .addQueryParameter("anime", "Any")
            .addQueryParameter("adult", "Any")
            .addQueryParameter("display_mode", "Minimal Display")
            .build()
        val d: Document = doc(url)
        val items = d.select("a[data-tip][href*=/series/]").mapNotNull { a ->
            val sid = seriesId(a.attr("href")) ?: return@mapNotNull null
            val title = a.attr("data-tip").ifEmpty { a.text() }.ifEmpty { return@mapNotNull null }
            Entry(id, "series/$sid", title, cover(sid), type)
        }
        return PagedResult(items, items.size >= pageSize)
    }

    override suspend fun popular(page: Int) = browse(page, "Popularity")
    override suspend fun latest(page: Int) = browse(page, "Latest Updates")
    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> {
        val t = tag.trim().ifEmpty { return PagedResult(emptyList(), false) }
        return browse(page, "Popularity", tag = t)
    }
    override suspend fun search(query: String, page: Int) =
        if (query.isBlank()) popular(page) else browse(page, "Best Match", query)

    override suspend fun details(entryId: String): EntryDetails {
        val sid = seriesId(entryId) ?: error("Bad id")
        val d = doc("$baseUrl/series/$sid")
        val title = d.selectFirst("h1")?.text().orEmpty()
        if (title.isEmpty()) error("Page not found")
        fun li(label: String) = d.select("li:has(strong:containsOwn($label))").firstOrNull()
        val official = li("Official Translation")?.selectFirst("a")?.text() == "Yes"
        return EntryDetails(
            entry = Entry(id, "series/$sid", title, cover(sid), type),
            description = li("Description")?.selectFirst("p")?.text()?.ifEmpty { null },
            authors = li("Author")?.select("a")?.map { it.text().trim() }.orEmpty(),
            genres = li("Tags")?.select("a")?.map { it.text().trim() }.orEmpty(),
            status = li("Status")?.selectFirst("a")?.text()?.trim(),
            officialUrl = if (official) li("Official Translation")?.let { d.selectFirst("a[aria-label*=official]")?.attr("href") } else null,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val sid = seriesId(entryId) ?: error("Bad id")
        val d = doc("$baseUrl/series/$sid/full-chapter-list")
        return d.select("a[href^=/chapters/]").mapNotNull { a ->
            val cid = a.attr("href").removePrefix("/chapters/").ifEmpty { return@mapNotNull null }
            val name = a.selectFirst("span.grow > span")?.text()?.trim() ?: a.text().trim()
            Episode(
                id = "chapters/$cid",
                name = name,
                number = Regex("""\d+(?:\.\d+)?""").find(name)?.value?.toFloatOrNull(),
                dateMillis = parseDate(a.selectFirst("time[datetime]")?.attr("datetime")),
            )
        }
    }

    override suspend fun pages(episodeId: String): List<Page> {
        val cid = episodeId.substringAfterLast('/')
        val d = doc("$baseUrl/chapters/$cid/images?is_prev=False&current_page=1&reading_style=long_strip")
        return d.select("img[src]").map { it.absUrl("src") }
            .filter { it.startsWith("http") && "/static/" !in it }
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
    }
}

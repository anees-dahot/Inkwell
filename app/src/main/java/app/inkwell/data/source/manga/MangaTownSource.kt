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

/** Port of Keiyoushi's MangaTown extension (www.mangatown.com). */
class MangaTownSource(client: OkHttpClient) : ScraperMangaSource(client) {
    override val baseUrl = "https://www.mangatown.com"
    override val id = "mangatown"
    override val name = "MangaTown"
    override val lang = "en"

    override val headers = mapOf("Referer" to "https://www.mangatown.com/", "Cookie" to "isAdult=1")

    private fun https(u: String?): String? = u?.takeIf { it.isNotBlank() }?.let {
        when {
            it.startsWith("//") -> "https:$it"
            it.startsWith("http://") -> "https://" + it.removePrefix("http://")
            else -> it
        }
    }

    private fun listing(d: Document): PagedResult<Entry> {
        val items = d.select("ul.manga_pic_list > li").mapNotNull { el ->
            val a = el.selectFirst("a.manga_cover") ?: return@mapNotNull null
            val title = el.selectFirst("p.title a")?.text()?.trim()?.ifEmpty { null } ?: a.attr("title")
            Entry(id, a.attr("href"), title, ref(https(a.selectFirst("img")?.attr("src"))), type)
        }
        return PagedResult(items, d.selectFirst("div.next-page a.next:not([href^=javascript])") != null)
    }

    override suspend fun popular(page: Int) = listing(doc("$baseUrl/directory/0-0-0-0-0-0/$page.htm?views.za"))

    override suspend fun latest(page: Int) = listing(doc("$baseUrl/directory/0-0-0-0-0-0/$page.htm?last_chapter_time.za"))

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("name", query.trim())
            .addQueryParameter("page", page.toString()).build()
        return listing(doc(url))
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val li = d.select(".detail_info li")
        fun field(label: String) = li.firstOrNull { it.selectFirst("b")?.text()?.contains(label, true) == true }
        val desc = d.selectFirst("#show")?.also { it.select("a").remove() }?.text()?.trim()
        return EntryDetails(
            entry = Entry(id, entryId, d.selectFirst("h1.title-top")?.text().orEmpty(),
                ref(https(d.selectFirst(".detail_info > img")?.attr("src"))), type),
            description = desc?.ifEmpty { null },
            authors = (field("Author")?.select("a")?.map { it.text().trim() }.orEmpty() +
                field("Artist")?.select("a")?.map { it.text().trim() }.orEmpty()).distinct(),
            genres = field("Genre")?.select("a")?.map { it.text().trim() }.orEmpty(),
            status = field("Status")?.ownText()?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.ifEmpty { null },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> =
        doc(absPath(entryId)).select("ul.chapter_list li").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val name = a.text().trim()
            Episode(
                id = a.attr("href"),
                name = name,
                number = Regex("""(\d+(?:\.\d+)?)\s*$""").find(name)?.groupValues?.get(1)?.toFloatOrNull(),
                dateMillis = parseTime(el.selectFirst("span.time")?.text()),
            )
        }

    private fun parseTime(s: String?): Long? {
        val t = s?.trim().orEmpty()
        val now = System.currentTimeMillis()
        return when {
            t.isEmpty() -> null
            t.startsWith("Today", true) -> now
            t.startsWith("Yesterday", true) -> now - 86_400_000L
            else -> runCatching {
                java.time.LocalDate.parse(t, java.time.format.DateTimeFormatter.ofPattern("MMM dd,yyyy", java.util.Locale.ENGLISH))
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: parseDate(t)
        }
    }

    override suspend fun pages(episodeId: String): List<Page> =
        doc(absPath(episodeId)).select("img.image").mapNotNull { https(it.attr("src").ifEmpty { null }) }
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
}

package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/** www.katamanga.com (the former mangakakalot.gg; Next.js, server-rendered lists + /api chapters). */
class KatamangaSource(client: OkHttpClient) : ScraperMangaSource(client) {
    override val baseUrl = "https://www.katamanga.com"
    override val id = "katamanga"
    override val name = "Katamanga"
    override val lang = "en"

    override val headers = mapOf("Referer" to "https://www.katamanga.com/")

    private fun listing(d: Document): PagedResult<Entry> {
        val items = d.select("div.group").mapNotNull { el ->
            val a = el.selectFirst("h3 a[href^=/manga/]") ?: return@mapNotNull null
            Entry(id, a.attr("href"), a.attr("title").ifEmpty { a.text() },
                ref(el.selectFirst("img")?.attr("src")?.ifEmpty { null }), type)
        }.distinctBy { it.id }
        return PagedResult(items, d.selectFirst("a[aria-label=Next page]") != null)
    }

    override suspend fun popular(page: Int) = listing(doc("$baseUrl/manga-list/hot-manga?page=$page"))

    override suspend fun latest(page: Int) = listing(doc("$baseUrl/manga-list/latest-manga?page=$page"))

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("page", page.toString()).build()
        return listing(doc(url))
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        fun field(label: String) = d.select("div").firstOrNull {
            it.children().size == 2 && it.child(0).tagName() == "span" && it.child(0).text().equals(label, true)
        }?.child(1)?.text()?.trim()
        return EntryDetails(
            entry = Entry(id, entryId, d.selectFirst("h1")?.text().orEmpty(),
                ref(d.selectFirst("meta[property=og:image]")?.attr("content")?.ifEmpty { null }), type),
            description = d.selectFirst(".prose-summary")?.text()?.ifEmpty { null },
            authors = field("Author")?.split(",", ";")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            genres = d.select("a[href^=/genre/]").filter { !it.attr("href").endsWith("/all") }
                .map { it.text().trim() }.distinct(),
            status = field("Status"),
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = entryId.trimEnd('/').substringAfterLast('/')
        val arr = json("$baseUrl/api/manga/$slug/chapters?limit=-1").jsonObject["data"]!!
            .jsonObject["chapters"]!!.jsonArray
        return arr.map { it.jsonObject }.map { c: JsonObject ->
            val cs = c["chapter_slug"]!!.jsonPrimitive.content
            Episode(
                id = "/manga/$slug/$cs",
                name = c["chapter_name"]?.jsonPrimitive?.contentOrNull ?: cs,
                number = c["chapter_num"]?.jsonPrimitive?.floatOrNull,
                dateMillis = parseDate(c["updated_at"]?.jsonPrimitive?.contentOrNull?.replace(' ', 'T'),
                    "yyyy-MM-dd'T'HH:mm:ss"),
            )
        }
    }

    override suspend fun pages(episodeId: String): List<Page> =
        doc(absPath(episodeId)).select("img[alt*=page]")
            .mapNotNull { it.attr("src").ifEmpty { null } }
            .distinct()
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
}

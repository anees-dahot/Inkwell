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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Port of Mihon's MangaKatana extension (mangakatana.com). */
class MangaKatanaSource(client: OkHttpClient) : ScraperMangaSource(client) {
    override val baseUrl = "https://mangakatana.com"
    override val id = "mangakatana"
    override val name = "MangaKatana"
    override val lang = "en"

    override val headers = mapOf("Referer" to "$baseUrl/")

    private val dateFmt = DateTimeFormatter.ofPattern("MMM-dd-yyyy", Locale.ENGLISH)

    private fun path(url: String) = url.toHttpUrl().encodedPath

    private fun parseList(d: Document): PagedResult<Entry> {
        val items = d.select("#book_list div.item").mapNotNull { el ->
            val a = el.selectFirst("h3.title a") ?: return@mapNotNull null
            val href = a.absUrl("href").ifEmpty { return@mapNotNull null }
            val cover = el.selectFirst(".wrap_img img")?.absUrl("src")?.ifEmpty { null }
            Entry(id, path(href), a.text().trim(), ref(cover), type)
        }
        return PagedResult(items, d.selectFirst("a.next.page-numbers") != null)
    }

    override suspend fun popular(page: Int) = parseList(doc("$baseUrl/manga/page/$page"))
    override suspend fun latest(page: Int) = parseList(doc("$baseUrl/latest/page/$page"))

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        val url = (if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page").toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim())
            .addQueryParameter("search_by", "book_name").build()
        return parseList(doc(url))
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val title = d.selectFirst("h1.heading")?.text()?.trim() ?: error("Page not found")
        val status = d.selectFirst(".d-cell-small.value.status")?.text()?.trim()?.ifEmpty { null }
        return EntryDetails(
            entry = Entry(id, entryId, title, ref(d.selectFirst(".cover img")?.absUrl("src")?.ifEmpty { null }), type),
            description = d.selectFirst(".summary > p")?.text()?.trim()?.ifEmpty { null },
            authors = d.select("a.author").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct(),
            genres = d.select("ul.meta .genres a").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> =
        doc(absPath(entryId)).select(".chapters tr").mapNotNull { el ->
            val a = el.selectFirst(".chapter a") ?: return@mapNotNull null
            val href = a.absUrl("href").ifEmpty { return@mapNotNull null }
            val name = a.text().trim()
            val date = runCatching {
                LocalDate.parse(el.selectFirst(".update_time")?.text()?.trim(), dateFmt)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: parseDate(el.selectFirst(".update_time")?.text())
            Episode(
                id = path(href),
                name = name,
                number = Regex("""(?:Chapter|Ch\.?)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(name)?.groupValues?.get(1)?.toFloatOrNull(),
                dateMillis = date,
            )
        }

    override suspend fun pages(episodeId: String): List<Page> {
        val html = fetch(absPath(episodeId))
        // Image list lives in a JS array; the variable name rotates (thzq / ytaw / ...), so prefer thzq and
        // fall back to the longest array of image urls.
        val urlRe = Regex("""'(https?://[^']+)'""")
        val arrays = Regex("""var\s+(\w+)\s*=\s*\[([^\]]*)\]\s*;""").findAll(html)
            .map { it.groupValues[1] to urlRe.findAll(it.groupValues[2]).map { m -> m.groupValues[1] }.toList() }
            .filter { it.second.isNotEmpty() }.toList()
        val urls = arrays.firstOrNull { it.first == "thzq" }?.second
            ?: arrays.maxByOrNull { it.second.size }?.second
            ?: emptyList()
        return urls.mapIndexed { i, u -> Page(i, ref(u)!!) }
    }
}

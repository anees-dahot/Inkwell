package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ImageReferers
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.LocalDate

/** Official LINE Webtoon (English originals; Canvas titles show up in search). */
class WebtoonsSource(client: OkHttpClient) : ScraperMangaSource(client) {
    override val baseUrl = "https://www.webtoons.com"
    override val id = "webtoons"
    override val name = "WEBTOON"
    override val lang = "en"

    private val mobile = "https://m.webtoons.com"
    private val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")

    override val headers = mapOf("Referer" to "$baseUrl/")

    private fun cover(url: String?) = ImageReferers.register(url?.ifEmpty { null }, "$baseUrl/")

    /** id = "/en/genre/slug/list?title_no=N" */
    private fun parseList(d: Document): List<Entry> =
        d.select(".webtoon_list li a[href*=title_no]").mapNotNull { a ->
            val u = a.absUrl("href").toHttpUrl()
            val title = a.selectFirst(".title")?.text()?.ifEmpty { null } ?: return@mapNotNull null
            val img = a.selectFirst("img")?.absUrl("src")
            Entry(id, u.encodedPath + "?title_no=" + u.queryParameter("title_no"), title, cover(img), type)
        }.distinctBy { it.id }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(parseList(doc("$baseUrl/en/ranking/popular")), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        // Page n = the daily schedule n-1 days back from today (newest updates first).
        val p = page.coerceAtLeast(1)
        val day = days[LocalDate.now().minusDays((p - 1).toLong()).dayOfWeek.value - 1]
        return PagedResult(parseList(doc("$baseUrl/en/originals/$day?sortOrder=UPDATE")), p < 7)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        if (page > 1) return PagedResult(emptyList(), false)
        val url = "$baseUrl/en/search".toHttpUrl().newBuilder().addQueryParameter("keyword", query.trim()).build()
        return PagedResult(parseList(doc(url)), false)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val info = d.selectFirst(".detail_header .info")
        val title = d.selectFirst("h1.subj, h3.subj")?.text() ?: error("Title not found")
        val authors = info?.select(".author_area a.author, .author_area .author")?.map { it.text().trim() }
            ?.filter { it.isNotEmpty() }?.distinct().orEmpty()
        val genres = info?.select(".genre")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val day = d.selectFirst("p.day_info")?.text().orEmpty()
        val status = if (day.contains("COMPLETED", true)) "Completed" else if (day.isNotEmpty()) "Ongoing" else null
        val cover = d.selectFirst("meta[property=og:image]")?.attr("content")
        return EntryDetails(
            entry = Entry(id, entryId, title, cover(cover), type),
            description = d.selectFirst("p.summary")?.text()?.ifEmpty { null },
            authors = authors,
            genres = genres,
            status = status,
            officialUrl = absPath(entryId),
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val u = absPath(entryId).toHttpUrl()
        val titleNo = u.queryParameter("title_no") ?: error("Bad title id")
        val canvas = u.pathSegments.getOrNull(1) == "canvas"
        val url = "$mobile/api/v1/${if (canvas) "canvas" else "webtoon"}/$titleNo/episodes".toHttpUrl().newBuilder()
            .addQueryParameter("pageSize", "99999")
            .apply { if (canvas) addQueryParameter("readingLanguageCode", "en") }
            .build()
        val req = request(url.toString()).newBuilder()
            .header("Referer", "$mobile/").header("X-Requested-With", "XMLHttpRequest").build()
        val list = withIO { client.newCall(req).bodyString() }
        val arr: JsonArray = json.parseToJsonElement(list).jsonObject["result"]!!.jsonObject["episodeList"]!!.jsonArray
        return arr.map { it.jsonObject }.map { e: JsonObject ->
            Episode(
                id = e["viewerLink"]!!.jsonPrimitive.content,
                name = e["episodeTitle"]!!.jsonPrimitive.content,
                number = e["episodeNo"]?.jsonPrimitive?.content?.toFloatOrNull(),
                dateMillis = e["exposureDateMillis"]?.jsonPrimitive?.long,
            )
        }
    }

    private suspend fun <T> withIO(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    override suspend fun pages(episodeId: String): List<Page> {
        val d = doc(absPath(episodeId))
        val pages = d.select("div#_imageList > img").mapNotNull { it.attr("data-url").ifEmpty { null } }
        if (pages.isEmpty()) error("Episode locked or unavailable (Fast Pass / coins required)")
        return pages.mapIndexed { i, u -> Page(i, cover(u)!!) }
    }
}

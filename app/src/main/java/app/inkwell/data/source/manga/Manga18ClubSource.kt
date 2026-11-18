package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.PagedResult
import app.inkwell.data.model.Page
import app.inkwell.data.source.ScraperMangaSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Port of Mihon's Manga18 multisrc theme (manga18.club). */
class Manga18ClubSource(client: OkHttpClient) : ScraperMangaSource(client) {
    override val baseUrl = "https://manga18.club"
    override val id = "manga18club"
    override val name = "Manga18.Club"
    override val lang = "en"

    override val headers = mapOf("Referer" to "$baseUrl/")

    private val dateFmt = DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ENGLISH)

    private fun parseList(d: Document): PagedResult<Entry> {
        val items = d.select("div.story_item").mapNotNull { el ->
            val href = abs(el.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val title = el.selectFirst("div.mg_info > div.mg_name a")?.text() ?: return@mapNotNull null
            Entry(id, path(href), title, ref(el.selectFirst("img")?.absUrl("src")), type)
        }
        val hasNext = d.selectFirst(".pagination > li:last-child:not(.active)") != null
        return PagedResult(items, hasNext)
    }

    private fun path(url: String): String =
        url.toHttpUrl().encodedPath

    override suspend fun popular(page: Int) = parseList(doc("$baseUrl/list-manga/$page?order_by=views"))
    override suspend fun latest(page: Int) = parseList(doc("$baseUrl/list-manga/$page"))

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("list-manga").addPathSegment(page.toString())
            .addQueryParameter("search", query.trim()).build()
        return parseList(doc(url))
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val info = d.selectFirst("div.detail_listInfo")
        val title = d.select("div.detail_name > h1").text()
        val desc = buildString {
            d.select("div.detail_reviewContent").eachText().forEach { append(it).append("\n\n") }
            info?.selectFirst("div.item:contains(Other name) div.info_value")?.text()
                ?.takeIf { it != "Updating" && it.isNotEmpty() }
                ?.let { append("Alternative Names:\n").append(it) }
        }.trim()
        val status = info?.select("div.item:contains(Status) div.info_value")?.text()
        val author = info?.selectFirst("div.info_label:contains(author) + div.info_value, div.info_label:contains(autor) + div.info_value")
            ?.text()?.takeIf { it != "Updating" }
        val artist = info?.selectFirst("div.info_label:contains(artist) + div.info_value")
            ?.text()?.takeIf { it != "Updating" }
        return EntryDetails(
            entry = Entry(id, entryId, title, ref(d.selectFirst("div.detail_avatar > img")?.absUrl("src")), type),
            description = desc.ifEmpty { null },
            authors = listOfNotNull(author, artist).distinct(),
            genres = info?.select("div.info_value > a[href*=/manga-list/]")?.eachText().orEmpty(),
            status = when (status) {
                "On Going" -> "Ongoing"
                "Completed" -> "Completed"
                else -> null
            },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> =
        doc(absPath(entryId)).select("div.chapter_box .item").mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val name = a.text()
            val date = el.selectFirst("p")?.text()?.trim()?.let {
                runCatching {
                    LocalDate.parse(it, dateFmt).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                }.getOrNull()
            }
            Episode(
                id = path(abs(a.attr("href")) ?: return@mapNotNull null),
                name = name,
                number = Regex("""(\d+(?:\.\d+)?)""").find(name)?.value?.toFloatOrNull(),
                dateMillis = date,
            )
        }

    override suspend fun pages(episodeId: String): List<Page> {
        val d = doc(absPath(episodeId))
        val script = d.selectFirst("script:containsData(slides_p_path)")
            ?: error("Unable to find script with image data")
        return script.data().substringAfter('[').substringBefore(",]")
            .replace("\"", "").split(",").filter { it.isNotBlank() }
            .mapIndexed { i, enc ->
                val url = String(java.util.Base64.getMimeDecoder().decode(enc.trim()), Charsets.UTF_8)
                Page(i, ref(if (url.startsWith("/")) baseUrl + url else url)!!)
            }
    }
}

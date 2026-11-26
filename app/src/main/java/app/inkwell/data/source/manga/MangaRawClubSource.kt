package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/** Port of Mihon's MangaRawClub extension (mgeko.cc). */
class MangaRawClubSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    override val baseUrl = "https://www.mgeko.cc"
    override val id = "mangarawclub"
    override val name = "MangaRaw.Club"
    override val lang = "en"

    override val headers = mapOf("Referer" to "$baseUrl/")

    private val dateFmt = DateTimeFormatterBuilder().parseCaseInsensitive()
        .appendPattern("[MMMM][MMM] d, yyyy, h[:mm] a").toFormatter(Locale.ENGLISH)

    private fun img(e: Element?) = e?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }?.ifEmpty { null }
    private fun path(url: String) = url.toHttpUrl().encodedPath

    private suspend fun browse(url: String): PagedResult<Entry> {
        val req = request(url).newBuilder().header("X-Requested-With", "XMLHttpRequest").build()
        val body = withIO { client.newCall(req).execute().use { r ->
            check(r.isSuccessful) { "$name HTTP ${r.code}" }; r.body?.string().orEmpty()
        } }
        val o = json.parseToJsonElement(body).jsonObject
        val html = o["results_html"]!!.jsonPrimitive.content
        val d = Jsoup.parseBodyFragment(html, baseUrl)
        val items = d.select(".comic-card").mapNotNull { el ->
            val href = el.selectFirst("a")?.absUrl("href")?.ifEmpty { null } ?: return@mapNotNull null
            val title = el.selectFirst(".comic-card__title a")?.text() ?: return@mapNotNull null
            Entry(id, path(href), title, ref(img(el.selectFirst(".comic-card__cover img"))), type)
        }
        val page = o["page"]!!.jsonPrimitive.int
        val pages = o["num_pages"]!!.jsonPrimitive.int
        return PagedResult(items, page < pages)
    }

    private suspend fun <T> withIO(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    override suspend fun popular(page: Int) =
        browse("$baseUrl/browse-comics/data/?page=$page&sort=popular_all_time&safe_mode=0")

    /** Unverified (Cloudflare): data endpoint takes ?genre=<slug>. */
    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> {
        val slug = app.inkwell.data.model.tagSlug(tag).ifEmpty { return PagedResult(emptyList(), false) }
        return browse("$baseUrl/browse-comics/data/?page=$page&sort=popular_all_time&safe_mode=0&genre=$slug")
    }

    override suspend fun latest(page: Int) =
        browse("$baseUrl/browse-comics/data/?page=$page&sort=latest&safe_mode=0")

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        val url = "$baseUrl/search/".toHttpUrl().newBuilder()
            .addQueryParameter("search", query.trim())
            .addQueryParameter("results", page.toString()).build()
        val d = doc(url)
        val items = d.select(".novel-item").mapNotNull { el ->
            val href = el.selectFirst("a")?.absUrl("href")?.ifEmpty { null } ?: return@mapNotNull null
            val title = el.selectFirst(".novel-title")?.text() ?: return@mapNotNull null
            Entry(id, path(href), title, ref(img(el.selectFirst(".novel-cover img"))), type)
        }
        return PagedResult(items, d.selectFirst("nav.paging a:contains(Next)") != null)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        d.selectFirst(".novel-header") ?: error("Page not found")
        val title = d.selectFirst(".novel-title, h1")?.text().orEmpty()
        val author = d.selectFirst(".author a")?.attr("title")?.trim()?.takeIf { it.lowercase() != "updating" }
        val desc = buildString {
            d.selectFirst(".description")?.text()?.substringAfter("Summary is")?.let { append(it.trim()) }
            val raw = d.selectFirst(".alternative-title")?.ownText()
            if (!raw.isNullOrEmpty()) {
                val sep = if (Regex("[•;]").containsMatchIn(raw)) Regex("[•;]") else Regex(",")
                val alts = raw.split(sep).map { it.trim() }.filter { it.isNotEmpty() && it.lowercase() != "updating" }
                if (alts.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n")
                    append("Alternative Names:")
                    alts.forEach { append("\n- ").append(it) }
                }
            }
        }
        val genres = d.select(".categories a[href*=genre]").map { a ->
            a.ownText().split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        }
        val status = when {
            d.selectFirst("div.header-stats strong.completed") != null -> "Completed"
            d.selectFirst("div.header-stats strong.ongoing") != null -> "Ongoing"
            else -> null
        }
        return EntryDetails(
            entry = Entry(id, entryId, title, ref(img(d.selectFirst(".cover img"))), type),
            description = desc.ifEmpty { null },
            authors = listOfNotNull(author),
            genres = genres,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val d = doc(absPath(entryId).trimEnd('/') + "/all-chapters/")
        return d.select("ul.chapter-list > li").mapNotNull { el ->
            val href = el.selectFirst("a")?.absUrl("href")?.ifEmpty { null } ?: return@mapNotNull null
            val raw = el.selectFirst(".chapter-title, .chapter-number")?.ownText().orEmpty().removeSuffix("-eng-li")
            Episode(
                id = path(href),
                name = "Chapter $raw",
                number = Regex("""\d+(?:\.\d+)?""").find(raw)?.value?.toFloatOrNull(),
                dateMillis = parseChapterDate(el.selectFirst(".chapter-update")?.attr("datetime")),
            )
        }
    }

    private fun parseChapterDate(s: String?): Long? {
        if (s.isNullOrEmpty()) return null
        val t = s.replace(".", "").replace("Sept", "Sep")
        return runCatching {
            LocalDateTime.parse(t, dateFmt).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    override suspend fun pages(episodeId: String): List<Page> =
        doc(absPath(episodeId)).select("#chapter-reader img")
            .map { it.absUrl("src") }
            .filter { it.isNotEmpty() && !it.contains("credits-mgeko.png") }
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
}

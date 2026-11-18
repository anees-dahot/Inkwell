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

/** Port of Mihon's Mangapill extension (mangapill.com). Manga only; no manhwa. */
class MangapillSource(client: OkHttpClient) : ScraperMangaSource(client) {
    override val baseUrl = "https://mangapill.com"
    override val id = "mangapill"
    override val name = "Mangapill"
    override val lang = "en"

    override val headers = mapOf("Referer" to "$baseUrl/")

    private fun img(e: Element?) = e?.let { it.absUrl("data-src").ifEmpty { it.absUrl("src") } }?.ifEmpty { null }

    private fun parseList(d: org.jsoup.nodes.Document): List<Entry> =
        d.select("div.grid > div:has(a[href^=/manga/])").mapNotNull { el ->
            val a = el.selectFirst("a[href^=/manga/]:has(div)") ?: return@mapNotNull null
            val title = a.selectFirst("div.font-black, div.font-bold")?.text()?.ifEmpty { null } ?: return@mapNotNull null
            val href = a.attr("href")
            Entry(id, href, title, ref(img(el.selectFirst("img"))), type)
        }.distinctBy { it.id }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val d = doc("$baseUrl/search?type=manga&page=$page")
        return PagedResult(parseList(d), d.selectFirst("a.btn:containsOwn(Next)") != null)
    }

    // /chapters is not paginated; only the first page is available.
    override suspend fun latest(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(parseList(doc("$baseUrl/chapters")), false)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("page", page.toString()).build()
        val d = doc(url)
        return PagedResult(parseList(d), d.selectFirst("a.btn:containsOwn(Next)") != null)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val title = d.selectFirst("h1")?.text().orEmpty()
        if (title.isEmpty()) error("Page not found")
        fun field(label: String) = d.select("label:containsOwn($label)").firstOrNull()
            ?.parent()?.selectFirst("div")?.text()?.trim()
        val status = when (field("Status")?.lowercase()) {
            "publishing" -> "Ongoing"
            "finished" -> "Completed"
            "on hiatus" -> "Hiatus"
            "discontinued" -> "Cancelled"
            else -> field("Status")
        }
        return EntryDetails(
            entry = Entry(id, entryId, title, ref(img(d.selectFirst("img.lazy, img[data-src]"))), type),
            description = d.selectFirst("div.mb-3 > p.text-sm")?.let { p ->
                p.select("br").after("\\n"); p.text().replace("\\n ", "\n").replace("\\n", "\n").trim()
            }?.ifEmpty { null },
            authors = emptyList(),
            genres = d.select("a[href^=/search?genre=]").map { it.text().trim() },
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> =
        doc(absPath(entryId)).select("#chapters a[href^=/chapters/]").map { a ->
            val name = a.text().trim()
            Episode(
                id = a.attr("href"),
                name = name,
                number = Regex("""\d+(?:\.\d+)?""").find(name)?.value?.toFloatOrNull(),
                dateMillis = null,
            )
        }

    override suspend fun pages(episodeId: String): List<Page> =
        doc(absPath(episodeId)).select("img.js-page")
            .map { img(it).orEmpty() }
            .filter { it.isNotEmpty() }
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
}

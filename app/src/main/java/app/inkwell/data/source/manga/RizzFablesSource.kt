package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.PagedResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/**
 * rizzfables.com — customised MangaThemesia: /series/ returns the whole catalogue
 * on one page (no real paging), synopsis is injected via a JS variable.
 */
class RizzFablesSource(client: OkHttpClient) : MangaThemesiaSource(client) {
    override val baseUrl = "https://rizzfables.com"
    override val id = "rizzfables"
    override val name = "Rizz Fables"

    override val mangaUrlDirectory = "/series"
    override val nextPageSelector = "a.never-matches"
    override val dateFormats = listOf("d MMM yyyy", "MMMM d, yyyy")

    override suspend fun popular(page: Int) =
        if (page > 1) PagedResult(emptyList<Entry>(), false) else listing(browseUrl(1, popularOrder))

    override suspend fun latest(page: Int) =
        if (page > 1) PagedResult(emptyList<Entry>(), false) else listing(browseUrl(1, latestOrder))

    // Search has no working page 2 (redirects to an ad host).
    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        if (page > 1) return PagedResult(emptyList(), false)
        return listing("$baseUrl/".toHttpUrl().newBuilder().addQueryParameter("s", query.trim()).build().toString())
    }

    override fun description(d: Document): String? {
        val script = d.select("script").map { it.data() }.firstOrNull { it.contains("var description =") }
        val raw = script?.substringAfter("var description =")?.substringBefore("\n")?.trim()?.trimEnd(';')?.trim()
        val parsed = raw?.let { runCatching { json.parseToJsonElement(it).let { e -> (e as kotlinx.serialization.json.JsonPrimitive).content } }.getOrNull() }
        return parsed?.trim()?.ifEmpty { null } ?: super.description(d)
    }
}

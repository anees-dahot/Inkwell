package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Asura Scans (asurascans.com; asuracomic.net redirects here). Site is Astro with a
 * public JSON API at api.asurascans.com. Entry id = "series/{slug}", episode id = "series/{slug}/chapters/{number}".
 */
class AsuraScansMangaSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    override val baseUrl = "https://asurascans.com"
    override val id = "asura"
    override val name = "Asura Scans"
    override val lang = "en"

    private val api = "https://api.asurascans.com/api"
    private val pageSize = 20

    override val headers = mapOf("Referer" to "$baseUrl/", "Accept" to "application/json")

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

    private suspend fun browse(page: Int, sort: String, search: String? = null, genre: String? = null): PagedResult<Entry> {
        val offset = (page - 1) * pageSize
        val url = "$api/series".toHttpUrl().newBuilder()
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("sort", sort)
            .apply { if (!search.isNullOrBlank()) addQueryParameter("search", search.trim()) }
            .apply { if (genre != null) addQueryParameter("genres", genre) }
            .build()
        val o = json(url).jsonObject
        val items = o["data"]?.jsonArray.orEmpty().mapNotNull { toEntry(it.jsonObject) }
        val total = o["meta"]?.jsonObject?.get("total")?.str()?.toIntOrNull() ?: 0
        return PagedResult(items, offset + items.size < total)
    }

    private fun toEntry(s: JsonObject): Entry? {
        val slug = s["slug"].str() ?: return null
        val title = s["title"].str() ?: return null
        return Entry(id, "series/$slug", title, ref(s["cover"].str()), type,
            s["latest_chapters"]?.jsonArray?.mapNotNull { it.jsonObject["number"].str()?.toFloatOrNull() }?.maxOrNull())
    }

    override suspend fun popular(page: Int) = browse(page, "popular")
    override suspend fun latest(page: Int) = browse(page, "latest")
    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> {
        val slug = app.inkwell.data.model.tagSlug(tag).ifEmpty { return PagedResult(emptyList(), false) }
        return browse(page, "popular", genre = slug)
    }
    override suspend fun search(query: String, page: Int) =
        if (query.isBlank()) popular(page) else browse(page, "popular", query)

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.substringAfterLast('/')
        val s = json("$api/series/$slug").jsonObject["series"]?.jsonObject ?: error("Page not found")
        val entry = toEntry(s) ?: error("Page not found")
        val desc = s["description"].str()?.let { h ->
            val d = Jsoup.parseBodyFragment(h)
            d.select("br").after("\\n"); d.select("p").append("\\n\\n")
            d.text().replace("\\n", "\n").lines().joinToString("\n") { it.trim() }.trim()
        }?.ifEmpty { null }
        return EntryDetails(
            entry = entry,
            description = desc,
            authors = listOfNotNull(s["author"].str(), s["artist"].str())
                .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            genres = s["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"].str() },
            status = s["status"].str()?.replaceFirstChar { it.uppercase() },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = entryId.substringAfterLast('/')
        val arr = json("$api/series/$slug/chapters").jsonObject["data"]?.jsonArray ?: emptyList()
        return arr.mapNotNull { e ->
            val c = e.jsonObject
            if (c["is_premium"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            val num = c["number"].str() ?: return@mapNotNull null
            val title = c["title"].str()?.takeIf { it.isNotBlank() }
            Episode(
                id = "series/$slug/chapters/$num",
                name = "Chapter $num" + (title?.let { " - $it" } ?: ""),
                number = num.toFloatOrNull(),
                dateMillis = parseDate(c["published_at"].str()),
            )
        }
    }

    override suspend fun pages(episodeId: String): List<Page> {
        val d = json("$api/${episodeId.trimStart('/')}").jsonObject["data"]?.jsonObject ?: error("No pages")
        val pages = d["chapter"]?.jsonObject?.get("pages")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["url"].str() }
        if (pages.isEmpty()) error("Chapter locked or has no pages")
        return pages.mapIndexed { i, u -> Page(i, ref(u)!!) }
    }
}

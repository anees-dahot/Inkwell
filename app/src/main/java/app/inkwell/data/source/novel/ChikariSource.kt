package app.inkwell.data.source.novel

import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperNovelSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Chikari (chikari.moe). A JSON API: `/api/series` (paged catalogs and
 * search), `/api/novels/<slug>` (details), `/api/novels/<slug>/chapters`
 * (paged chapter list) and `/api/novels/<slug>/chapters/<n>/read` (chapter
 * body as plain-text paragraphs).
 */
class ChikariSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "chikari"
    override val name = "Chikari"
    override val baseUrl = "https://chikari.moe"
    override val lang = "en"

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject?.str(key: String): String? = this?.get(key).str()

    private fun itemsOf(root: JsonElement): List<JsonObject> =
        ((root as? JsonObject)?.get("items") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    private fun entryOf(o: JsonObject): Entry? {
        val title = o.str("title") ?: return null
        val slug = o.str("slug") ?: return null
        return Entry(id, slug, title, o.str("cover_url")?.let { abs(it) }, ContentType.NOVEL)
    }

    private suspend fun seriesPage(sort: String, page: Int, limit: Int, query: String? = null): PagedResult<Entry> {
        val offset = (page - 1) * limit
        val url = absPath("api/series").toHttpUrl().newBuilder()
            .addQueryParameter("type", "novel")
            .addQueryParameter("sort", sort)
            .apply { query?.let { addQueryParameter("q", it) } }
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val items = itemsOf(json(url))
        return PagedResult(items.mapNotNull(::entryOf), items.isNotEmpty())
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> = seriesPage("popular", page, 60)

    override suspend fun latest(page: Int): PagedResult<Entry> = seriesPage("updated", page, 60)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> =
        seriesPage("popular", page, 36, query)

    // ---- details -----------------------------------------------------------

    private suspend fun chapterList(slug: String): List<Episode> {
        val chapters = mutableListOf<Episode>()
        var offset = 0
        val limit = 500
        var hasMore = true
        while (hasMore) {
            val url = absPath("api/novels/$slug/chapters").toHttpUrl().newBuilder()
                .addQueryParameter("order", "asc")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .build()
            val fetched = itemsOf(json(url))
            if (fetched.isEmpty()) break
            fetched.forEach { ch ->
                val number = ch.str("number")
                chapters += Episode(
                    id = "api/novels/$slug/chapters/$number/read",
                    name = ch.str("title")?.takeIf { it.isNotEmpty() }
                        ?: "Chapter ${number.orEmpty()}",
                    number = number?.toFloatOrNull(),
                    dateMillis = parseDate(ch.str("created_at")),
                )
            }
            if (fetched.size == limit) offset += limit else hasMore = false
        }
        return chapters
    }

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.trim('/')
        val detail = json(absPath("api/novels/$slug")).jsonObject

        val authors = (detail["authors"] as? JsonArray)
            ?.mapNotNull { el -> (el as? JsonObject)?.str("name") }
            .orEmpty()
        val genres = (detail["genres"] as? JsonArray)
            ?.mapNotNull { el -> (el as? JsonObject)?.str("name") }
            .orEmpty()
        val status = if (detail.str("status") == "releasing") "Ongoing" else "Completed"

        return EntryDetails(
            entry = Entry(
                id, slug,
                detail.str("title")?.takeIf { it.isNotEmpty() } ?: "Untitled",
                detail.str("cover_url")?.let { abs(it) },
                ContentType.NOVEL,
            ),
            description = detail.str("description")?.takeIf { it.isNotEmpty() },
            authors = authors.ifEmpty { listOf("Unknown") },
            genres = genres,
            status = status,
        ).also { cachedChapters[slug] = chapterList(slug) }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = entryId.trim('/')
        cachedChapters[slug]?.let { return it }
        return chapterList(slug).also { cachedChapters[slug] = it }
    }

    /** The chapter body arrives as plain-text paragraphs, one per line. */
    override suspend fun chapterText(episodeId: String): List<String> {
        val root = json(absPath(episodeId.trim('/'))).jsonObject
        val body = root.str("body") ?: return emptyList()
        return body.split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}

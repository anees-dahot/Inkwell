package app.inkwell.data.source.novel

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.JsonPrimitive

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/**
 * Port of LNReader's `genesis` plugin (genesistudio.com). Novels live in a
 * Directus-backed JSON API; anonymous users can read released chapters.
 */
class GenesisSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "genesistudio"
    override val name = "Genesis"
    override val baseUrl = "https://genesistudio.com"
    override val lang = "en"

    private val api = "https://api.genesistudio.com"

    private data class CatalogNovel(val title: String, val path: String, val cover: String)

    private suspend fun parseNovelJson(): List<CatalogNovel> {
        val params = mapOf(
            "status" to "published",
            "fields" to "[\"id\",\"novel_title\",\"cover\",\"abbreviation\"]",
            "limit" to "-1",
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val root = json(absPath("api/directus/novels?$query"))
        return root.jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val title = o.str("novel_title") ?: return@mapNotNull null
            val abbr = o.str("abbreviation") ?: return@mapNotNull null
            val cover = o.str("cover") ?: return@mapNotNull null
            CatalogNovel(title, "novels/$abbr".trim(), "$api/storage/v1/object/public/directus/$cover.png")
        }
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val items = parseNovelJson()
            .map { Entry(id, it.path, it.title, abs(it.cover), ContentType.NOVEL) }
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val needle = normalize(query)
        val items = parseNovelJson()
            .filter { normalize(it.title).contains(needle) }
            .map { Entry(id, it.path, it.title, abs(it.cover), ContentType.NOVEL) }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private suspend fun coverUrl(coverId: String): String {
        val ext = runCatching {
            val data = json(absPath("api/directus-file/$coverId")).jsonObject
            val type = data.str("type")
            if (type != null && type.contains('/')) type.substringAfter('/').replace("jpeg", "jpg") else "png"
        }.getOrDefault("png")
        return "$api/storage/v1/object/public/directus/$coverId.$ext"
    }

    override suspend fun details(entryId: String): EntryDetails {
        val abbreviation = entryId.removePrefix("novels/").trim('/')
        val o = json(absPath("api/directus/novels/by-abbreviation/$abbreviation")).jsonObject

        val status = when (o.str("serialization")?.lowercase()) {
            "ongoing" -> "Ongoing"
            "hiatus" -> "On Hiatus"
            "dropped", "cancelled" -> "Cancelled"
            "completed" -> "Completed"
            else -> null
        }
        val genres = o.arr("genres")
            ?.mapNotNull { (it as? JsonObject)?.get("genres_id")?.jsonObject?.str("label") }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val chapters = extractChapters(o.str("id").orEmpty())

        return EntryDetails(
            entry = Entry(
                sourceId = id,
                id = entryId,
                title = o.str("novel_title") ?: abbreviation,
                coverUrl = o.str("cover")?.let { abs(coverUrl(it)) },
                type = ContentType.NOVEL,
            ),
            description = o.str("synopsis"),
            authors = listOfNotNull(o.str("author")?.takeIf { it.isNotBlank() }),
            genres = genres,
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    private suspend fun extractChapters(novelId: String): List<Episode> {
        val o = json(absPath("api/novels-chapter/$novelId")).jsonObject
        val chapters = o.get("data")?.jsonObject?.arr("chapters") ?: return emptyList()
        return chapters.mapNotNull { el ->
            val c = el.jsonObject
            if (c.str("status") != "released") return@mapNotNull null
            val chapterId = c.str("id") ?: return@mapNotNull null
            val title = c.str("chapter_title").orEmpty()
            val locked = (c["isUnlocked"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull != true
            val path = if (locked) "viewer/locked/$chapterId" else "viewer/$chapterId"
            val name = if (locked) "\uD83D\uDD12 $title" else title
            Episode(
                id = path,
                name = name,
                number = c.num("chapter_number"),
                dateMillis = null,
            )
        }
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        check(!episodeId.startsWith("viewer/locked/")) { "This chapter is locked on the site" }
        val chapterId = episodeId.removePrefix("viewer/")
        val o = json(absPath("api/chapters/$chapterId/content")).jsonObject
        val content = o.get("data")?.jsonObject?.str("chapter_content")
        if (o.str("error") != null || content.isNullOrEmpty() || content == "None") {
            throw IllegalStateException("This chapter is locked on the site")
        }
        return content.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun normalize(str: String): String = str.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Float? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.floatOrNull

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
}

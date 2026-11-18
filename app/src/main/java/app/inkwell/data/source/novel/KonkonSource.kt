package app.inkwell.data.source.novel

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Konkon (konkon.ink) — port of LNReader's konkon plugin. The web app reads a
 * public JSON API at api-k.konkon.ink; covers are stored as keys that must be
 * base64-encoded into media URLs.
 */
class KonkonSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "konkon"
    override val name = "Konkon"
    override val baseUrl = "https://konkon.ink"
    override val lang = "en"

    override val headers: Map<String, String> =
        mapOf("Accept" to "application/json", "Referer" to "https://konkon.ink/")

    private val api = "https://api-k.konkon.ink"
    private val pageSize = 20
    private val cachedEpisodes = HashMap<String, List<Episode>>()

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        // The trending endpoint returns the first `limit` novels, so each
        // page asks for the cumulative limit and slices.
        val limit = page * pageSize
        val data = json("$api/api/public/novels_trending?limit=$limit").jsonObject["data"] as? JsonArray
            ?: return PagedResult(emptyList(), false)
        val items = data.drop((page - 1) * pageSize).take(pageSize).mapNotNull { toEntry(it) }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        val data = json("$api/api/public/latest-updates?per_page=$pageSize&page=$page").jsonObject["data"]
            as? JsonArray ?: return PagedResult(emptyList(), false)
        val items = data.mapNotNull { toEntry(it) }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val results = json("$api/api/public/search?q=" + enc(query.trim())).jsonObject["results"] as? JsonArray
            ?: return PagedResult(emptyList(), false)
        return PagedResult(results.mapNotNull { toEntry(it) }, false)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val slug = slugOf(entryId)
        val pages = mutableListOf<JsonObject>()
        val first = novelPage(slug, 1) ?: return emptyDetails(entryId, slug)
        pages.add(first)
        val lastPage = (first["chapters_pagination"] as? JsonObject)
            ?.get("last_page")?.jsonPrimitive?.floatOrNull?.toInt()?.coerceAtLeast(1) ?: 1
        for (page in 2..lastPage) {
            novelPage(slug, page)?.let { pages.add(it) }
        }

        val episodes = pages
            .mapNotNull { it["volumes"] as? JsonArray }
            .flatMap { it }
            .mapNotNull { it as? JsonObject }
            .sortedBy { it["order"]?.jsonPrimitive?.floatOrNull ?: 0f }
            .flatMap { volume -> (volume["chapters"] as? JsonArray).orEmpty() }
            .mapNotNull { it as? JsonObject }
            .sortedBy { it["sort_order"]?.jsonPrimitive?.floatOrNull ?: 0f }
            .filter { it["status"]?.jsonPrimitive?.contentOrNull == "published" }
            .map { chapter ->
                val locked = (chapter["is_locked"]?.jsonPrimitive?.booleanOrNull == true) &&
                    chapter["user_has_access"]?.jsonPrimitive?.booleanOrNull != true
                Episode(
                    id = "read/chapter/${chapter["id"]?.jsonPrimitive?.contentOrNull}/${chapter["slug"]?.jsonPrimitive?.contentOrNull}",
                    name = (if (locked) "🔒 " else "") + (chapter["title"]?.jsonPrimitive?.contentOrNull ?: "Chapter"),
                    number = null,
                    dateMillis = parseDate(
                        chapter["scheduled_for"]?.jsonPrimitive?.contentOrNull
                            ?: chapter["created_at"]?.jsonPrimitive?.contentOrNull,
                    ),
                )
            }

        val genres = (listOfNotNull(first["genres"] as? JsonArray).flatMap { it } +
            listOfNotNull(first["tags"] as? JsonArray).flatMap { it })
            .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
            .filter { it.isNotBlank() }
            .distinct()

        val entry = Entry(
            sourceId = id,
            id = entryId,
            title = first["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Untitled",
            coverUrl = coverUrl(first),
            type = ContentType.NOVEL,
        ).also { cachedEpisodes[entryId] = episodes }

        return EntryDetails(
            entry = entry,
            description = first["description"]?.jsonPrimitive?.contentOrNull?.let { summaryText(it) },
            authors = listOfNotNull(first["author_name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }),
            genres = genres,
            status = status(first["novel_status"]?.jsonPrimitive?.contentOrNull),
        )
    }

    private fun emptyDetails(entryId: String, slug: String) = EntryDetails(
        entry = Entry(id, entryId, "Untitled", null, ContentType.NOVEL),
        description = null,
        authors = emptyList(),
        genres = emptyList(),
        status = null,
    ).also { cachedEpisodes[entryId] = emptyList() }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedEpisodes[entryId]?.let { return it }
        details(entryId)
        return cachedEpisodes[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val chapterId = Regex("read/chapter/(\\d+)").find(episodeId)?.groupValues?.get(1)
            ?: return emptyList()
        val data = json("$api/api/public/chapters/$chapterId").jsonObject["data"] as? JsonObject
            ?: return emptyList()
        val locked = (data["locked"]?.jsonPrimitive?.booleanOrNull == true ||
            data["is_locked"]?.jsonPrimitive?.booleanOrNull == true) &&
            data["user_has_access"]?.jsonPrimitive?.booleanOrNull != true
        if (locked) return emptyList()
        val html = data["content"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return paragraphsOf(Jsoup.parseBodyFragment(html).body())
    }

    // ---- helpers -----------------------------------------------------------

    private suspend fun novelPage(slug: String, page: Int): JsonObject? =
        json("$api/api/public/novels/" + enc(slug) + "?page=$page&per_page=100").jsonObject["data"] as? JsonObject

    private fun slugOf(entryId: String): String =
        entryId.substringAfter("read/").substringBefore('?').substringBefore('#')

    private fun toEntry(el: kotlinx.serialization.json.JsonElement): Entry? {
        val o = el as? JsonObject ?: return null
        val slug = o["slug"]?.jsonPrimitive?.contentOrNull ?: return null
        return Entry(
            sourceId = id,
            id = "read/$slug",
            title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Untitled",
            coverUrl = coverUrl(o),
            type = ContentType.NOVEL,
        )
    }

    private fun coverUrl(novel: JsonObject): String? {
        val key = novel["featured_image_thumb_medium_key"]?.jsonPrimitive?.contentOrNull
            ?: novel["featured_image_key"]?.jsonPrimitive?.contentOrNull
            ?: novel["featured_image_thumb_small_key"]?.jsonPrimitive?.contentOrNull
            ?: novel["featured_image"]?.jsonPrimitive?.contentOrNull
            ?: return null
        return "$api/api/media/k/" + Base64.getEncoder().encodeToString(key.toByteArray())
    }

    private fun status(raw: String?): String? = when (raw?.lowercase()) {
        "ongoing" -> "Ongoing"
        "completed", "complete" -> "Completed"
        "cancelled", "canceled" -> "Cancelled"
        "hiatus", "on hiatus" -> "On Hiatus"
        else -> null
    }

    private fun summaryText(html: String): String {
        val body = Jsoup.parseBodyFragment(html).body()
        val paragraphs = body.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
        return (if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else body.text()).trim()
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

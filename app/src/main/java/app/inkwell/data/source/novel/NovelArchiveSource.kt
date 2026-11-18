package app.inkwell.data.source.novel

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/**
 * Novel Archive (novelarchive.cc) — a client-rendered site with a complete
 * public JSON API: /api/novels, /api/novels/<id>, /api/novels/<id>/chapters/<n>.
 */
class NovelArchiveSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelarchive"
    override val name = "Novel Archive"
    override val baseUrl = "https://novelarchive.cc"
    override val lang = "en"

    // ---- listing -----------------------------------------------------------

    private suspend fun list(query: String): List<kotlinx.serialization.json.JsonElement> =
        runCatching {
            json(absPath("api/novels?$query")).jsonObject["novels"]?.jsonArray?.toList().orEmpty()
        }.getOrDefault(emptyList())

    private fun toItem(o: kotlinx.serialization.json.JsonObject): Entry {
        val nid = o["id"]?.asStringOrNull().orEmpty()
        val cover = o["cover_url"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
            ?: o["novel_image"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
        return Entry(
            sourceId = id,
            id = "novel?id=$nid",
            title = o["title"]?.asStringOrNull() ?: "Untitled",
            coverUrl = abs(cover),
            type = ContentType.NOVEL,
        )
    }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val items = list("page=$page&per_page=24&sort=popular&status=all").map { toItem(it.jsonObject) }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        val items = list("page=$page&per_page=24&sort=recent&status=all").map { toItem(it.jsonObject) }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val term = java.net.URLEncoder.encode(query, "UTF-8")
        val items = list("page=$page&per_page=24&search=$term").map { toItem(it.jsonObject) }
        return PagedResult(items, items.isNotEmpty())
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val nid = if (entryId.contains("id=")) entryId.substringAfter("id=") else entryId
        val novel = json(absPath("api/novels/$nid")).jsonObject["novel"]?.jsonObject
            ?: throw IllegalStateException("Novel Archive: no such novel")

        val names = novel["chapter_names"]?.jsonArray
            ?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
        val total = novel["total_chapters"]?.asStringOrNull()?.toIntOrNull() ?: names.size

        val chapters = (1..total).map { number ->
            val name = names.getOrNull(number - 1)?.trim().orEmpty()
            Episode(
                id = "reader?novel=$nid&chapter=$number",
                name = name.ifEmpty { "Chapter $number" },
                number = number.toFloat(),
                dateMillis = null,
            )
        }

        return EntryDetails(
            entry = Entry(
                sourceId = id,
                id = entryId,
                title = novel["title"]?.asStringOrNull() ?: "Untitled",
                coverUrl = abs(
                    novel["cover_url"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
                        ?: novel["novel_image"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
                ),
                type = ContentType.NOVEL,
            ),
            description = novel["description"]?.asStringOrNull()?.trim()?.ifEmpty { null },
            authors = listOfNotNull(novel["author"]?.asStringOrNull()?.takeIf { !it.isBlank() }),
            genres = novel["genres"]?.asStringOrNull()?.split(',')?.map { it.trim() }
                ?.filter { it.isNotEmpty() }.orEmpty(),
            status = statusOf(novel["release_status"]?.asStringOrNull() ?: novel["ongoing"]?.asStringOrNull()),
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter -----------------------------------------------------------

    /** The API returns plain text with blank-line paragraph breaks, not markup. */
    override suspend fun chapterText(episodeId: String): List<String> {
        val params = episodeId.substringAfter('?', "").split('&')
            .mapNotNull { p ->
                val kv = p.split('=', limit = 2)
                if (kv.size == 2) kv[0] to kv[1] else null
            }.toMap()
        val nid = params["novel"].orEmpty()
        val number = params["chapter"]?.toIntOrNull() ?: 1
        val content = json(absPath("api/novels/$nid/chapters/$number"))
            .jsonObject["chapter"]?.jsonObject?.get("content")?.asStringOrNull()
            ?: throw IllegalStateException("Novel Archive: empty chapter")
        return content.split(Regex("\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    // ---- helpers -----------------------------------------------------------

    private fun statusOf(raw: String?): String? {
        val value = raw.orEmpty().trim().lowercase()
        return when (value) {
            "completed", "finished" -> "Completed"
            "hiatus", "paused", "on hiatus" -> "On Hiatus"
            "ongoing", "releasing" -> "Ongoing"
            else -> null
        }
    }

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

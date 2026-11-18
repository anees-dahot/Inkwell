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
import okhttp3.OkHttpClient

/**
 * BoonNovel — https://reader.boondollars.com. A self-hosted English library
 * served over a clean public JSON API with no login:
 *   GET /api/public/books                     full catalog
 *   GET /api/public/books/<id>                book detail
 *   GET /api/public/books/<id>/chapters       chapter list
 *   GET /api/public/books/<id>/chapters/<n>   chapter paragraphs
 * The catalog call returns everything and ignores paging, so popular/latest/
 * search all work off that one list. Paths are virtual: novel /book/<id>,
 * chapter /book/<id>/chapter/<n>.
 */
class BoonNovelSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "boonnovel"
    override val name = "BoonNovel"
    override val baseUrl = "https://reader.boondollars.com"
    override val lang = "en"

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject?.str(key: String): String? = this?.get(key).str()

    private data class BookSummary(
        val id: Int,
        val title: String,
        val author: String,
        val cover: String?,
        val tags: List<String>,
        val recentViews: Long,
        val lastChapterDate: String,
    )

    private data class ChapterRef(val number: Float, val title: String)

    /** Prefer medium cover, then full cover, then thumbnail. */
    private fun pickCover(o: JsonObject): String? =
        o.str("cover_medium_url") ?: o.str("cover_url") ?: o.str("cover_thumb_url")

    private suspend fun books(): JsonArray {
        val root = json(absPath("api/public/books"))
        return (root as? JsonArray)
            ?: (root as? JsonObject)?.get("books") as? JsonArray
            ?: JsonArray(emptyList())
    }

    private fun parseBook(el: JsonElement): BookSummary? {
        val o = el as? JsonObject ?: return null
        val bookId = o.str("id")?.toIntOrNull() ?: return null
        val title = o.str("title") ?: return null
        return BookSummary(
            id = bookId,
            title = title,
            author = o.str("author").orEmpty(),
            cover = pickCover(o),
            tags = (o["tags"] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList(),
            recentViews = o.str("recent_views")?.toDoubleOrNull()?.toLong() ?: 0L,
            lastChapterDate = o.str("last_chapter_date").orEmpty(),
        )
    }

    private fun tsMs(s: String?): Long =
        s?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    private fun toEntry(b: BookSummary) =
        Entry(id, "book/${b.id}", b.title, abs(b.cover), ContentType.NOVEL)

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page != 1) return PagedResult(emptyList(), false)
        val items = books().mapNotNull(::parseBook).sortedByDescending { it.recentViews }
        return PagedResult(items.map(::toEntry), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        if (page != 1) return PagedResult(emptyList(), false)
        val items = books().mapNotNull(::parseBook).sortedByDescending { tsMs(it.lastChapterDate) }
        return PagedResult(items.map(::toEntry), false)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page != 1) return PagedResult(emptyList(), false)
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return PagedResult(emptyList(), false)
        val items = books().mapNotNull(::parseBook).filter {
            it.title.lowercase().contains(needle) || it.author.lowercase().contains(needle)
        }
        return PagedResult(items.map(::toEntry), false)
    }

    // ---- details -----------------------------------------------------------

    private fun parseNovelPath(path: String): Int {
        val segs = path.substringBefore('?').trim('/').split('/')
        val i = segs.indexOf("book")
        val idSeg = if (i >= 0) segs.getOrNull(i + 1) else segs.lastOrNull()
        return idSeg?.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid BoonNovel novel path: $path")
    }

    private fun parseChapterPath(path: String): Pair<Int, Int> {
        val segs = path.substringBefore('?').trim('/').split('/')
        val bookId = segs.getOrNull(segs.indexOf("book") + 1)?.toIntOrNull()
        val chapter = segs.getOrNull(segs.indexOf("chapter") + 1)?.toIntOrNull()
        if (bookId == null || chapter == null) {
            throw IllegalArgumentException("Invalid BoonNovel chapter path: $path")
        }
        return bookId to chapter
    }

    private suspend fun fetchChapterRefs(bookId: Int): List<ChapterRef> {
        val root = json(absPath("api/public/books/$bookId/chapters"))
        val arr = (root as? JsonObject)?.get("chapters") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val n = o.str("chapter")?.toFloatOrNull() ?: return@mapNotNull null
            ChapterRef(n, o.str("title").orEmpty())
        }
    }

    /** 12 -> "12", 12.5 -> "12.5" (JS String(number) equivalent). */
    private fun fmtNum(n: Float): String =
        if (n % 1f == 0f) n.toLong().toString() else n.toString()

    override suspend fun details(entryId: String): EntryDetails {
        val bookId = parseNovelPath(entryId)
        val detail = json(absPath("api/public/books/$bookId")).jsonObject
        val status = when (detail.str("status").orEmpty()) {
            "ongoing", "ongoing-trial" -> "Ongoing"
            "completed" -> "Completed"
            "dropped" -> "Dropped"
            "hiatus" -> "On Hiatus"
            else -> null
        }
        val tags = (detail["tags"] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()
        return EntryDetails(
            entry = Entry(
                id, "book/$bookId",
                detail.str("title") ?: "Untitled",
                abs(pickCover(detail)),
                ContentType.NOVEL,
            ),
            description = detail.str("description")?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(detail.str("author")?.takeIf { it.isNotEmpty() }),
            genres = tags,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val bookId = parseNovelPath(entryId)
        return fetchChapterRefs(bookId)
            .distinctBy { it.number }
            .sortedBy { it.number }
            .map { r ->
                val n = fmtNum(r.number)
                Episode(
                    id = "book/$bookId/chapter/$n",
                    name = if (r.title.isNotEmpty()) "Chapter $n: ${r.title}" else "Chapter $n",
                    number = r.number,
                    dateMillis = null,
                )
            }
    }

    /** Chapter bodies arrive as plain-text paragraphs (empty = paragraph break). */
    override suspend fun chapterText(episodeId: String): List<String> {
        val (bookId, chapter) = parseChapterPath(episodeId)
        val root = json(absPath("api/public/books/$bookId/chapters/$chapter")).jsonObject
        val arr = root["content"] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el -> el.str()?.trim()?.takeIf { it.isNotEmpty() } }
    }
}

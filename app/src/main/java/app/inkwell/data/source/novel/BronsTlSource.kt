package app.inkwell.data.source.novel

import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperNovelSource
import app.inkwell.data.source.paragraphsOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * BronsTL (bronstl.com). A JSON API under `/api/` (Laravel pagination:
 * `{ current_page, data, next_page_url }`) drives the whole plugin; chapter
 * bodies arrive as HTML in the chapter's `body` field.
 */
class BronsTlSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "bronstl"
    override val name = "BronsTL"
    override val baseUrl = "https://bronstl.com"
    override val lang = "en"

    override val headers = mapOf(
        "Accept" to "application/json",
        "Referer" to "https://bronstl.com/",
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject?.str(key: String): String? = this?.get(key).str()

    private suspend fun api(path: String): JsonElement {
        val url = if (path.startsWith("http")) path else absPath("api/$path")
        return json(url)
    }

    /** Follow Laravel pagination until exhausted (guarded). */
    private suspend fun fetchAll(path: String): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var next: String? = path
        var guard = 0
        while (next != null && guard++ < 100) {
            val page = api(next).jsonObject
            page["data"]?.let { arr ->
                if (arr is JsonArray) arr.forEach { el -> (el as? JsonObject)?.let { items.add(it) } }
            }
            next = page["next_page_url"]?.str()?.takeIf { it.isNotBlank() }
        }
        return items
    }

    private fun toItem(o: JsonObject): Entry? {
        val slug = o.str("slug") ?: return null
        val title = o.str("title") ?: return null
        return Entry(id, "books/$slug", title, abs(o.str("cover_image")), ContentType.NOVEL)
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val items = fetchAll("books").mapNotNull(::toItem)
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val term = query.lowercase().trim()
        val items = fetchAll("books")
            .filter { o ->
                o.str("title").orEmpty().lowercase().contains(term) ||
                    o.str("author").orEmpty().lowercase().contains(term)
            }
            .mapNotNull(::toItem)
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.trim('/').replace(Regex("^books/"), "")
        val book = api("books/$slug").jsonObject
        val status = when (book["status"]?.let { (it as? JsonObject)?.str("name") }) {
            "ongoing" -> "Ongoing"
            "finished" -> "Completed"
            "dropped" -> "Dropped"
            else -> null
        }
        val chapters = fetchAll("books/$slug/chapters")
            .sortedBy { it.str("chapter_weight")?.toDoubleOrNull() ?: 0.0 }
            .mapIndexed { i, c ->
                Episode(
                    id = "books/$slug/chapters/${c.str("chapter_slug").orEmpty()}",
                    name = c.str("chapter_title").orEmpty(),
                    number = (i + 1).toFloat(),
                    dateMillis = parseDate(c.str("created_at")),
                )
            }
        val genres = (book["tags"] as? JsonArray)
            ?.mapNotNull { el -> (el as? JsonObject)?.str("name") }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val author = book.str("author")?.takeIf { it.isNotEmpty() }
        val illustrator = book.str("illustrator")?.takeIf { it.isNotEmpty() }
        return EntryDetails(
            entry = Entry(id, entryId.trim('/'), book.str("title") ?: "Untitled", abs(book.str("cover_image")), ContentType.NOVEL),
            description = book.str("description")?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(author, illustrator).distinct(),
            genres = genres,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = entryId.trim('/').replace(Regex("^books/"), "")
        return fetchAll("books/$slug/chapters")
            .sortedBy { it.str("chapter_weight")?.toDoubleOrNull() ?: 0.0 }
            .mapIndexed { i, c ->
                Episode(
                    id = "books/$slug/chapters/${c.str("chapter_slug").orEmpty()}",
                    name = c.str("chapter_title").orEmpty(),
                    number = (i + 1).toFloat(),
                    dateMillis = parseDate(c.str("created_at")),
                )
            }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val chapter = api(episodeId.trim('/')).jsonObject
        val body = chapter.str("body") ?: return emptyList()
        val el = Jsoup.parseBodyFragment(body).body()
        el.select("img").forEach { img ->
            val src = img.attr("src")
            if (src.startsWith("/")) img.attr("src", abs(src) ?: src)
        }
        return paragraphsOf(el)
    }
}

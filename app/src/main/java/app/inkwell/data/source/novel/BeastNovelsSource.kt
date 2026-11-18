package app.inkwell.data.source.novel

import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperNovelSource
import app.inkwell.data.source.paragraphsOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/**
 * Beast Novels (beastnovels.com). A custom Laravel site whose `/novels`
 * catalog page embeds the whole catalog as a JSON array (`allBooks: [...]`).
 */
class BeastNovelsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "beastnovels"
    override val name = "Beast Novels"
    override val baseUrl = "https://beastnovels.com"
    override val lang = "en"

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject?.str(key: String): String? = this?.get(key).str()

    /** Extract the embedded catalog array with a bracket-matching scan. */
    private suspend fun fetchCatalog(): JsonArray? {
        val html = fetch(absPath("novels"))
        val marker = Regex("allBooks\\s*:\\s*\\[").find(html) ?: return null
        val start = html.indexOf('[', marker.range.first)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var i = start
        while (i < html.length) {
            val ch = html[i]
            if (inString) {
                if (ch == '\\') i++
                else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                '[', '{' -> depth++
                ']', '}' -> {
                    depth--
                    if (depth == 0) {
                        val parsed = runCatching {
                            jsonParser.parseToJsonElement(html.substring(start, i + 1))
                        }.getOrNull()
                        return parsed as? JsonArray
                    }
                }
            }
            i++
        }
        return null
    }

    private fun entryOf(o: JsonObject): Entry? {
        val slug = o.str("bookslug") ?: return null
        val title = o.str("bookname") ?: return null
        return Entry(id, "series/$slug", title, abs(o.str("image_url")), ContentType.NOVEL)
    }

    private fun tsMs(s: String?): Long =
        s?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val books = fetchCatalog() ?: return PagedResult(emptyList(), false)
        val items = books.mapNotNull { it as? JsonObject }
            .sortedByDescending { it.str("totalview")?.toDoubleOrNull() ?: 0.0 }
            .mapNotNull(::entryOf)
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val books = fetchCatalog() ?: return PagedResult(emptyList(), false)
        val items = books.mapNotNull { it as? JsonObject }
            .sortedByDescending { tsMs(it.str("updated_at")) }
            .mapNotNull(::entryOf)
        return PagedResult(items, false)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val term = query.trim().lowercase()
        val books = fetchCatalog() ?: return PagedResult(emptyList(), false)
        val items = books.mapNotNull { it as? JsonObject }
            .filter { o ->
                o.str("bookname").orEmpty().lowercase().contains(term) ||
                    o.str("author").orEmpty().lowercase().contains(term)
            }
            .mapNotNull(::entryOf)
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private fun chapterList(d: Document): List<Episode> =
        d.select("a.chapter-row").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = href.removePrefix(baseUrl).trim('/')
            val locked = a.attr("data-premium") == "1"
            val num = a.selectFirst(".chapter-card__num")?.text()?.trim().orEmpty()
            val number = a.attr("data-chapter-number").toFloatOrNull()
            val name = a.selectFirst(".chapter-card__title")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() } ?: num
            Episode(
                id = path,
                name = if (locked) "\uD83D\uDD12 $name" else name,
                number = number,
                dateMillis = null,
            )
        }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        // The catalog only supplies the status; a failed catalog request must
        // not stop the novel page from loading.
        val catalog = runCatching { fetchCatalog() }.getOrNull()
        val slug = entryId.trim('/').substringAfterLast('/')
        val book = catalog?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.str("bookslug") == slug }
        val status = when (book?.str("novel_status")) {
            "ongoing" -> "Ongoing"
            "completed" -> "Completed"
            "hiatus" -> "On Hiatus"
            else -> null
        }
        val h1 = d.selectFirst("h1")
        var author: String? = null
        val nextP = h1?.nextElementSibling()
        if (nextP != null && nextP.tagName() == "p") {
            author = nextP.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        }
        val summary = h2Synopsis(d)?.nextElementSibling()?.select("p")?.eachText()
            ?.joinToString(" ")?.trim()?.takeIf { it.isNotEmpty() }
        val genres = d.select("a[href*=\"/novels?genre=\"]").eachText()
            .map { it.trim() }.filter { it.isNotEmpty() }
        val title = h1?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled"
        return EntryDetails(
            entry = Entry(id, entryId.trim('/'), title, abs(d.selectFirst("#heroImg")?.attr("src")), ContentType.NOVEL),
            description = summary,
            authors = listOfNotNull(author),
            genres = genres,
            status = status,
        ).also { cachedChapters[entryId.trim('/')] = chapterList(d) }
    }

    private fun h2Synopsis(d: Document) = d.selectFirst("h2:contains(Synopsis)")

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        val key = entryId.trim('/')
        cachedChapters[key]?.let { return it }
        val d = doc(absPath(key))
        return chapterList(d).also { cachedChapters[key] = it }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.selectFirst(".chapter-content") ?: return emptyList()
        content.select("script, style, iframe").remove()
        return paragraphsOf(content)
    }
}

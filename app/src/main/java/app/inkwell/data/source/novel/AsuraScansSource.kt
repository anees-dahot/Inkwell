package app.inkwell.data.source.novel

import kotlinx.serialization.json.jsonPrimitive

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Port of LNReader's Asura Scans plugin (novel arm). Everything runs through
 * the JSON API at api.asurascans.com: offset-paged browse, series detail with
 * inline chapter list, and per-chapter content_html.
 */
class AsuraScansSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "asurascans"
    override val name = "Asura Scans"
    override val baseUrl = "https://asurascans.com"
    override val lang = "en"

    private val apiUrl = "https://api.asurascans.com/api"
    private val pageSize = 20

    // ---- listing -----------------------------------------------------------

    private suspend fun browse(page: Int, params: Map<String, String>): List<Entry> {
        val url = (apiUrl + "/novel-series").toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("offset", ((page - 1) * pageSize).toString())
            .build()
        val data = json(url).jsonObject["data"] as? JsonArray ?: return emptyList()
        return data.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val slug = o["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Entry(
                id,
                "novels/$slug",
                title,
                abs(o["cover_url"]?.jsonPrimitive?.content),
                ContentType.NOVEL,
            )
        }
    }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val items = browse(page, mapOf("sort" to "popular"))
        return PagedResult(items, items.size >= pageSize)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        val items = browse(page, mapOf("sort" to "update"))
        return PagedResult(items, items.size >= pageSize)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val items = browse(page, mapOf("search" to query))
        return PagedResult(items, items.size >= pageSize)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.trim('/').substringAfterLast('/')
        val root = json("$apiUrl/novel-series/" + URLEncoder.encode(slug, "UTF-8")).jsonObject
        val series = root["series"] as? JsonObject
            ?: throw IllegalStateException("$name: series not found")

        val title = series["title"]?.jsonPrimitive?.content ?: "Untitled"
        val statusRaw = series["status"]?.jsonPrimitive?.content?.lowercase().orEmpty()
        val status = when (statusRaw) {
            "ongoing" -> "Ongoing"
            "complete", "completed" -> "Completed"
            "hiatus" -> "On Hiatus"
            "dropped" -> "Cancelled"
            else -> null
        }
        val author = series["author"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val genres = (series["genres"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            .orEmpty()

        var summary: String? = null
        val descriptionHtml = series["description"]?.jsonPrimitive?.content
        if (!descriptionHtml.isNullOrBlank()) {
            val parsed = Jsoup.parseBodyFragment(descriptionHtml)
            val paragraphs = parsed.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }
            summary = (if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else parsed.body().text().trim())
                .ifBlank { null }
        }

        val chapters = (root["chapters"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val number = o["number"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val chapterTitle = o["title"]?.jsonPrimitive?.content?.trim()
            val name = "Chapter $number" + (chapterTitle?.takeIf { it.isNotEmpty() }?.let { ": $it" } ?: "")
            Episode(
                id = "novels/$slug/chapter/$number",
                name = if (o["is_locked"]?.jsonPrimitive?.booleanOrNull == true) "🔒 $name" else name,
                number = number.toFloatOrNull(),
                dateMillis = parseDate(o["published_at"]?.jsonPrimitive?.content),
            )
        }

        return EntryDetails(
            entry = Entry(
                id,
                entryId,
                title,
                abs(series["cover_url"]?.jsonPrimitive?.content),
                ContentType.NOVEL,
            ),
            description = summary,
            authors = listOfNotNull(author),
            genres = genres,
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter text ------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        // novels/<slug>/chapter/<number>
        val parts = episodeId.trim('/').split('/')
        if (parts.size < 4) return emptyList()
        val slug = URLEncoder.encode(parts[1], "UTF-8")
        val number = URLEncoder.encode(parts[3], "UTF-8")
        val root = json("$apiUrl/novel-series/$slug/chapter/$number").jsonObject
        val html = root["content_html"]?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() } ?: return emptyList()

        val d = Jsoup.parseBodyFragment(html)
        // Some chapters end with a paragraph of leaked site navigation and
        // report dialog text; only trailing paragraphs carrying both markers go.
        var last = d.select("p").lastOrNull()
        while (last != null &&
            last.text().contains("Back to homepage") &&
            last.text().contains("Reporting chapter:")
        ) {
            last.remove()
            last = d.select("p").lastOrNull()
        }
        d.select("script, style, iframe").remove()
        return paragraphsOf(d.body())
    }
}

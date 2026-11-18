package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Port of LNReader's novelrest plugin — a JSON API at novelrest.vercel.app.
 */
class NovelRestSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelrest"
    override val name = "NovelRest"
    override val baseUrl = "https://novelrest.vercel.app"
    override val lang = "en"

    private val apiBase = "$baseUrl/api/lnreader"
    private val cachedChapters = HashMap<String, List<Episode>>()

    private suspend fun novelsPage(page: Int, query: String?, sort: String): PagedResult<Entry> {
        val url = "$apiBase/novels".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "20")
            .apply { query?.let { addQueryParameter("q", it) } }
            .addQueryParameter("sort", sort)
            .build()
        val items = json(url).jsonObject["novels"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val slug = o["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Entry(id, slug, title, abs(o["coverImage"]?.jsonPrimitive?.content), ContentType.NOVEL)
        } ?: emptyList()
        return PagedResult(items, items.isNotEmpty())
    }

    // Default filters: status "" (omitted), sort "latest".
    override suspend fun popular(page: Int) = novelsPage(page, null, "latest")
    override suspend fun latest(page: Int) = novelsPage(page, null, "latest")

    override suspend fun search(query: String, page: Int): PagedResult<Entry> =
        novelsPage(page, query, "latest")

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.removePrefix("/novels/")
        val o = json("$apiBase/novels/$slug").jsonObject
        val title = o["title"]?.jsonPrimitive?.content?.takeUnless { it.isEmpty() } ?: "Untitled"
        val author = o["author"]?.jsonPrimitive?.content?.takeUnless { it.isNullOrEmpty() }
        val genres = o["genres"]?.jsonArray?.mapNotNull { g ->
            when (g) {
                is JsonPrimitive -> g.content.takeIf { it.isNotEmpty() }
                is JsonObject -> g["name"]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                else -> null
            }
        } ?: emptyList()
        val status = when (o["status"]?.jsonPrimitive?.content) {
            "COMPLETED" -> "Completed"
            "ONGOING" -> "Ongoing"
            "HIATUS" -> "On Hiatus"
            else -> null
        }
        val chapters = o["chapters"]?.jsonArray?.mapNotNull { el ->
            val c = el.jsonObject
            val number = c["number"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Episode(
                id = "$slug/$number", // slug/chapterNumber format
                name = c["title"]?.jsonPrimitive?.content?.takeUnless { it.isNullOrEmpty() }
                    ?: "Chapter $number",
                number = number.toFloatOrNull(),
                dateMillis = parseDate(c["createdAt"]?.jsonPrimitive?.content),
            )
        } ?: emptyList()
        cachedChapters[slug] = chapters

        return EntryDetails(
            entry = Entry(id, slug, title, abs(o["coverImage"]?.jsonPrimitive?.content), ContentType.NOVEL),
            description = o["description"]?.jsonPrimitive?.content?.takeUnless { it.isNullOrEmpty() },
            authors = listOfNotNull(author),
            genres = genres,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = entryId.removePrefix("/novels/")
        cachedChapters[slug]?.let { return it }
        details(slug)
        return cachedChapters[slug].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        // chapterPath format: "slug/chapterNumber"
        val parts = episodeId.split('/')
        val number = parts.lastOrNull() ?: return emptyList()
        val slug = parts.dropLast(1).joinToString("/")
        val o = json("$apiBase/novels/$slug/chapters/$number").jsonObject
        val html = o["contentHtml"]?.jsonPrimitive?.content ?: return emptyList()
        if (html.isBlank()) return emptyList()
        return paragraphsOf(Jsoup.parse(html))
    }
}

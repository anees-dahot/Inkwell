package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import java.net.URLEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/** Port of LNReader's inkitt plugin. */
class InkittSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "inkitt"
    override val name = "Inkitt"
    override val baseUrl = "https://www.inkitt.com"
    override val lang = "en"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun str(e: JsonElement?): String? =
        runCatching { e?.jsonPrimitive?.contentOrNull }.getOrNull()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun parseStories(data: JsonElement): PagedResult<Entry> {
        val items = data.jsonObject["stories"]?.jsonArray?.mapNotNull { el ->
            val n = el.jsonObject
            val sid = str(n["id"]) ?: return@mapNotNull null
            val cat = str(n["category_one"]) ?: n["genres"]?.jsonArray?.firstOrNull()?.let { str(it) } ?: return@mapNotNull null
            val vc = n["vertical_cover"]?.jsonObject
            val cover = str(vc?.get("url")) ?: str(vc?.get("iphone")) ?: str(n["cover"]?.jsonObject?.get("url"))
            Entry(id, "$cat/$sid", str(n["title"]).orEmpty(), cover, ContentType.NOVEL)
        } ?: emptyList()
        return PagedResult(items, items.isNotEmpty())
    }

    private val genres = listOf("romance", "fantasy", "scifi", "thriller", "mystery", "horror", "drama")

    /** Site has no global trending page any more: page N lists the popular stories of the Nth genre. */
    override suspend fun popular(page: Int): PagedResult<Entry> {
        val genre = genres.getOrNull(page - 1) ?: return PagedResult(emptyList(), false)
        val d = doc("$baseUrl/genres/$genre")
        val items = d.select("div[data-test-id=story-card][data-story-id]").mapNotNull { el ->
            val sid = el.attr("data-story-id").ifEmpty { null } ?: return@mapNotNull null
            val title = el.attr("data-title").ifEmpty { null } ?: return@mapNotNull null
            Entry(id, "$genre/$sid", title, el.attr("data-cover-url").ifEmpty { null }, ContentType.NOVEL)
        }
        return PagedResult(items, page < genres.size)
    }

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int) =
        parseStories(json("$baseUrl/api/2/search/title?q=${enc(query)}&page=$page"))

    override suspend fun details(entryId: String): EntryDetails {
        val storyId = entryId.substringAfter('/')
        val api = json("$baseUrl/api/stories/$storyId").jsonObject
        val d = runCatching { doc("$baseUrl/stories/$storyId") }.getOrNull()
        val cover = str(api["vertical_cover"]?.jsonObject?.get("url")) ?: str(api["cover_url"])
        val author = api["user"]?.jsonObject?.let { str(it["name"]) ?: str(it["username"]) }
        val genres = api["story_genres"]?.jsonArray?.mapNotNull { str(it.jsonObject["main_genre"]?.jsonObject?.get("name")) }?.distinct().orEmpty()
            .ifEmpty { listOfNotNull(str(api["category_one"]), str(api["category_two"])) }
        return EntryDetails(
            entry = Entry(id, entryId, str(api["title"]).orEmpty(), cover, ContentType.NOVEL),
            description = d?.selectFirst("meta[property=og:description]")?.attr("content")?.ifEmpty { null },
            authors = listOfNotNull(author),
            genres = genres,
            status = null,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val storyId = entryId.substringAfter('/')
        val chapters = json("$baseUrl/api/stories/$storyId").jsonObject["chapters"]?.jsonArray ?: return emptyList()
        return chapters.mapNotNull { el ->
            val c = el.jsonObject
            val num = str(c["chapter_number"]) ?: return@mapNotNull null
            Episode("$entryId/chapters/$num", str(c["name"]) ?: "Chapter $num", num.toFloatOrNull(), null)
        }
    }

    override suspend fun chapterText(episodeId: String): List<String> =
        paragraphsOf(doc("$baseUrl/stories/$episodeId").selectFirst("div#chapterText"))
}

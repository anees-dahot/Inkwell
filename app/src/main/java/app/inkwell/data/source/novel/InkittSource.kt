package app.inkwell.data.source.novel

import app.inkwell.data.model.*
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
        runCatching { e?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotEmpty() && it != "null" }

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

    override suspend fun popular(page: Int) =
        parseStories(json("$baseUrl/trending_stories?page=$page&period=alltime"))

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int) =
        parseStories(json("$baseUrl/api/2/search/title?q=${enc(query)}&page=$page"))

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc("$baseUrl/stories/$entryId")
        val st = d.select("div.dlc > dl").firstOrNull { it.selectFirst("dt")?.text()?.contains("Status") == true }
            ?.selectFirst("dd")?.text()?.trim()
        val storyId = entryId.substringAfter('/')
        val cover = runCatching { str(json("$baseUrl/api/stories/$storyId").jsonObject["vertical_cover"]?.jsonObject?.get("url")) }.getOrNull()
        return EntryDetails(
            entry = Entry(id, entryId, d.select("h1.story-title").text(), cover, ContentType.NOVEL),
            description = d.select("p.story-summary").text().ifEmpty { null },
            authors = listOfNotNull(d.select("dl > dd > a.author-link").text().ifEmpty { null }),
            genres = d.select("dd.genres > a").map { it.text() }.filter { it.isNotEmpty() },
            status = when (st) { "Complete" -> "Completed"; "Ongoing" -> "Ongoing"; else -> null },
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

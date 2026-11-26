package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's novelbuddy plugin (Next.js site + JSON API). */
class NovelBuddySource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelbuddy"
    override val name = "NovelBuddy"
    override val baseUrl = "https://novelbuddy.me"
    override val lang = "en"

    private val api = "https://api.novelbuddy.me/"

    private fun s(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull
    private fun clamp(q: String) = if (q.length <= 50) q else q.take(50).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
    private fun rel(u: String) = u.trimStart('/')

    private suspend fun titles(page: Int, q: String?, sort: String?): PagedResult<Entry> {
        val url = (api + "titles/search").toHttpUrl().newBuilder()
            .apply {
                if (sort != null) addQueryParameter("sort", sort)
                addQueryParameter("page", page.toString())
                addQueryParameter("limit", "24")
                if (!q.isNullOrBlank()) addQueryParameter("q", clamp(q))
            }.build()
        val items = json(url).jsonObject["data"]?.jsonObject?.get("items")?.jsonArray?.mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            Entry(id, rel(s(o["url"]) ?: return@mapNotNull null), s(o["name"]).orEmpty(), s(o["cover"]), ContentType.NOVEL,
                (o["latest_chapters"] as? JsonArray)?.mapNotNull { c -> (c as? JsonObject)?.get("number")?.let { n -> s(n)?.toFloatOrNull() } }?.maxOrNull()
                    ?: ((o["stats"] as? JsonObject)?.get("chapters_count")?.let { n -> s(n)?.toFloatOrNull() }))
        } ?: emptyList()
        return PagedResult(items, items.size >= 24)
    }

    override suspend fun popular(page: Int) = titles(page, null, "views")
    override suspend fun latest(page: Int) = titles(page, null, "latest")
    override suspend fun search(query: String, page: Int) = titles(page, query, null)

    private suspend fun nextData(path: String): JsonObject {
        val body = fetch(absPath(path))
        val raw = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(body)?.groupValues?.get(1) ?: error("Could not find __NEXT_DATA__")
        return json.parseToJsonElement(raw).jsonObject["props"]!!.jsonObject["pageProps"]!!.jsonObject
    }

    private suspend fun manga(entryId: String): JsonObject =
        nextData(rel(entryId))["initialManga"] as? JsonObject ?: error("Could not find initialManga data")

    override suspend fun details(entryId: String): EntryDetails {
        val m = manga(entryId)
        val names = { k: String -> (m[k] as? JsonArray)?.mapNotNull { s((it as? JsonObject)?.get("name")) }.orEmpty() }
        val summary = s(m["summary"])?.takeIf { it.isNotBlank() }?.let {
            val d = Jsoup.parse("<div>" + it.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n") + "</div>")
            d.text().let { _ -> d.selectFirst("div")!!.wholeText() }
                .split('\n').map { l -> l.trim() }.filter { l -> l.isNotEmpty() }.joinToString("\n\n")
        }
        val status = when (s(m["status"])?.lowercase()) {
            "ongoing" -> "Ongoing"
            "hiatus" -> "On Hiatus"
            "dropped", "cancelled" -> "Cancelled"
            "completed" -> "Completed"
            else -> null
        }
        return EntryDetails(
            entry = Entry(id, rel(entryId), s(m["name"]) ?: "Untitled", s(m["cover"]), ContentType.NOVEL),
            description = summary,
            authors = names("authors"),
            genres = names("genres"),
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val m = manga(entryId)
        val mid = s(m["id"]).orEmpty()
        val cv = s(m["content_version"]) ?: s(m["cv"])
        val url = "${api}titles/$mid/chapters" + if (cv != null) "?cv=$cv" else ""
        val chapters = runCatching { json(url).jsonObject["data"]?.jsonObject?.get("chapters")?.jsonArray }.getOrNull()
        if (chapters != null) {
            return chapters.mapNotNull {
                val c = it as? JsonObject ?: return@mapNotNull null
                val cu = rel(s(c["url"]) ?: return@mapNotNull null)
                Episode(
                    "$cu?id=$mid&chapterId=${s(c["id"])}", s(c["name"]).orEmpty(),
                    Regex("""(\d+(?:\.\d+)?)""").find(s(c["name"]).orEmpty())?.value?.toFloatOrNull(),
                    parseDate(s(c["updated_at"])),
                )
            }.reversed()
        }
        return (m["chapters"] as? JsonArray).orEmpty().mapNotNull {
            val c = it as? JsonObject ?: return@mapNotNull null
            Episode(rel(s(c["url"]) ?: return@mapNotNull null), s(c["name"]).orEmpty(), null, parseDate(s(c["updatedAt"])))
        }.reversed()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val nid = Regex("""[?&]id=([^&]+)""").find(episodeId)?.groupValues?.get(1)
        val cid = Regex("""[?&]chapterId=([^&]+)""").find(episodeId)?.groupValues?.get(1)
        var content = ""
        if (nid != null && cid != null) {
            content = s(json("${api}titles/$nid/chapters/$cid").jsonObject["data"]?.jsonObject?.get("chapter")?.jsonObject?.get("content")).orEmpty()
        }
        if (content.isEmpty()) {
            val ch = nextData(rel(episodeId))["initialChapter"] as? JsonObject
            content = s(ch?.get("content")).orEmpty()
        }
        if (content.isEmpty()) return emptyList()
        content = content.replace(
            Regex("Find authorized novels in Webnovel.*?faster updates, better experience.*?Please click www\\.webnovel\\.com for visiting\\.", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), ""
        )
        return paragraphsOf(Jsoup.parse(content).body())
            .filter { !it.contains("freewebnovel", ignoreCase = true) }
    }
}

package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's novelito plugin (noveli.to JSON API). */
class NovelitoSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelito"
    override val name = "Novelito"
    override val baseUrl = "https://noveli.to"
    override val lang = "en"

    override val headers = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Referer" to "https://noveli.to/",
    )

    private val api = "$baseUrl/api"
    private val pageSize = 36

    private fun str(o: JsonObject, k: String): String? =
        (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private suspend fun list(params: Map<String, String>): PagedResult<Entry> {
        val b = "$api/novels".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val arr = json(b.build()).jsonObject["data"] as? JsonArray ?: JsonArray(emptyList())
        val items = arr.mapNotNull { el ->
            val o = el.jsonObject
            val nid = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val title = str(o, "title") ?: return@mapNotNull null
            Entry(id, nid, title, cover(str(o, "cover_image")), ContentType.NOVEL)
        }
        return PagedResult(items, items.size >= pageSize)
    }

    private fun cover(v: String?): String? = when {
        v.isNullOrEmpty() -> null
        v.startsWith("http") -> v
        v.startsWith("//") -> "https:$v"
        else -> baseUrl + (if (v.startsWith("/")) v else "/$v")
    }

    override suspend fun popular(page: Int) =
        list(mapOf("page" to "$page", "limit" to "$pageSize", "sort" to "popular"))

    override suspend fun latest(page: Int) =
        list(mapOf("page" to "$page", "limit" to "$pageSize", "sort" to "newest"))

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val q = query.trim()
        if (q.isEmpty()) return PagedResult(emptyList(), false)
        return list(mapOf("search" to q, "page" to "$page", "limit" to "$pageSize"))
    }

    private fun plain(raw: String): String = Jsoup.parse(
        raw.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
    ).wholeText().replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

    override suspend fun details(entryId: String): EntryDetails {
        val info = json("$api/novels/$entryId").jsonObject["data"]?.jsonObject
            ?: error("Novelito detail missing")
        val status = when (str(info, "status")?.lowercase()) {
            "ongoing", "releasing" -> "Ongoing"
            "completed" -> "Completed"
            "hiatus", "on hiatus", "paused" -> "On Hiatus"
            "dropped", "cancelled", "canceled" -> "Cancelled"
            else -> null
        }
        return EntryDetails(
            entry = Entry(id, entryId, str(info, "title") ?: "Untitled", cover(str(info, "cover_image")), ContentType.NOVEL),
            description = str(info, "description")?.let { plain(it) }?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(str(info, "author")?.takeIf { it.isNotEmpty() }),
            genres = (info["genres"] as? JsonArray)?.mapNotNull { g -> (g as? JsonPrimitive)?.content?.takeIf { it.isNotEmpty() } }.orEmpty(),
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val arr = json("$api/novels/$entryId/chapters").jsonObject["chapters"] as? JsonArray ?: return emptyList()
        val seen = HashSet<String>()
        val out = ArrayList<Episode>()
        arr.forEachIndexed { i, el ->
            val c = el.jsonObject
            val cid = c["id"]?.jsonPrimitive?.content ?: return@forEachIndexed
            if (!seen.add(cid)) return@forEachIndexed
            val num = c["chapter_number"]?.jsonPrimitive?.content?.toFloatOrNull()
            val title = str(c, "title")?.trim().orEmpty()
            out += Episode(
                id = "$entryId/$cid",
                name = title.ifEmpty { if (num != null) "Chapter ${num.toString().removeSuffix(".0")}" else "Chapter" },
                number = num,
                dateMillis = parseDate(str(c, "created_at"), "yyyy-MM-dd HH:mm:ss"),
            )
        }
        return if (out.all { it.number != null }) out.sortedBy { it.number } else out
    }

    private val chrome = listOf("Settings", "Font Size", "A", "A", "16px", "Font Family", "Background Color")
    private val notices = setOf(
        "Provided by Noveli.to",
        "If you want to support more free releases please read on Noveli.to",
    )

    override suspend fun chapterText(episodeId: String): List<String> {
        val (nid, cid) = episodeId.split('/').let { it[0] to it[1] }
        val ch = json("$api/novels/$nid/chapters/$cid").jsonObject["chapter"]?.jsonObject ?: return emptyList()
        val paras = ch["paragraphs"] as? JsonArray ?: return emptyList()
        val texts = ArrayList<String>()
        for (p in paras) {
            val o = p as? JsonObject ?: continue
            val html = str(o, "html").orEmpty()
            if (o["isHoneypot"]?.jsonPrimitive?.content == "true" ||
                o["isMidWatermark"]?.jsonPrimitive?.content == "true" ||
                "watermark-notice" in html || "noveli-canary" in html
            ) continue
            val text = (str(o, "text")?.let { Jsoup.parse(it).text() } ?: plain(html)).trim()
            if (text.isEmpty() || text in notices) continue
            texts += text
        }
        val start = if (texts.size >= chrome.size && texts.take(chrome.size) == chrome) chrome.size else 0
        return texts.drop(start)
    }
}

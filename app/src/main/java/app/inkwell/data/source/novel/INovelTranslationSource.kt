package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's inoveltranslation plugin (Payload CMS API + RSC chapter text). */
class INovelTranslationSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "inoveltranslation"
    override val name = "iNovelTranslation"
    override val baseUrl = "https://inoveltranslation.com"
    override val lang = "en"

    override val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "https://inoveltranslation.com/",
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun str(e: JsonElement?): String? =
        runCatching { e?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotEmpty() && it != "null" }

    private fun docsToEntries(data: JsonElement): PagedResult<Entry> {
        val o = data.jsonObject
        val items = o["docs"]?.jsonArray?.mapNotNull { el ->
            val d = el.jsonObject
            val nid = str(d["id"]) ?: return@mapNotNull null
            val cover = str(d["cover"]?.takeIf { it !is JsonNull }?.jsonObject?.get("url"))?.let { baseUrl + it }
            Entry(id, nid, str(d["title"]).orEmpty(), cover, ContentType.NOVEL)
        } ?: emptyList()
        val hasNext = o["hasNextPage"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: items.isNotEmpty()
        return PagedResult(items, hasNext)
    }

    override suspend fun popular(page: Int) = docsToEntries(json("$baseUrl/api/novels?limit=50&page=$page"))
    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int) =
        docsToEntries(json("$baseUrl/api/novels?where[title][contains]=${enc(query)}&limit=50&page=$page"))

    private fun lexicalToText(node: JsonObject): String {
        val sb = StringBuilder()
        for (c in node["children"]?.jsonArray ?: JsonArray(emptyList())) {
            val o = c.jsonObject
            when (str(o["type"])) {
                "paragraph" -> sb.append(lexicalToText(o)).append("\n\n")
                "text" -> sb.append(str(o["text"]).orEmpty())
                "listitem" -> sb.append("• ").append(lexicalToText(o)).append('\n')
                else -> sb.append(lexicalToText(o))
            }
        }
        return sb.toString()
    }

    override suspend fun details(entryId: String): EntryDetails {
        val o = json("$baseUrl/api/novels/$entryId?depth=1").jsonObject
        val summary = o["sypnosis"]?.takeIf { it !is JsonNull }?.jsonObject?.get("root")?.jsonObject
            ?.let { lexicalToText(it).trim() }?.ifEmpty { null }
        return EntryDetails(
            entry = Entry(
                id, entryId, str(o["title"]) ?: "Untitled",
                str(o["cover"]?.takeIf { it !is JsonNull }?.jsonObject?.get("url"))?.let { baseUrl + it },
                ContentType.NOVEL,
            ),
            description = summary,
            authors = listOfNotNull(str(o["author"]?.takeIf { it !is JsonNull }?.jsonObject?.get("name"))),
            genres = o["tags"]?.takeIf { it !is JsonNull }?.jsonArray?.mapNotNull { str(it.jsonObject["name"]) } ?: emptyList(),
            status = if (str(o["publication"]) == "completed") "Completed" else "Ongoing",
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val out = ArrayList<Episode>()
        var page = 1
        while (true) {
            val o = json("$baseUrl/api/chapters?where[novel][equals]=$entryId&limit=500&page=$page&depth=0&sort=chapter").jsonObject
            val docs = o["docs"]?.jsonArray ?: break
            for (el in docs) {
                val d = el.jsonObject
                val cid = str(d["id"]) ?: continue
                val num = str(d["chapter"])
                val locked = str(d["tier"]) != null
                val title = str(d["title"])?.let { " - $it" }.orEmpty()
                out.add(
                    Episode(
                        cid, "Ch. $num${if (locked) " 🔒" else ""}$title",
                        num?.toFloatOrNull(), parseDate(str(d["updatedAt"])),
                    ),
                )
            }
            if (o["hasNextPage"]?.jsonPrimitive?.content != "true") break
            page++
        }
        return out.sortedBy { it.number ?: 0f }
    }

    private fun lexicalParagraphs(node: JsonObject): List<String> {
        val out = ArrayList<String>()
        fun inline(n: JsonObject): String {
            val sb = StringBuilder()
            for (c in n["children"]?.jsonArray ?: JsonArray(emptyList())) {
                val o = c.jsonObject
                when (str(o["type"])) {
                    "text" -> sb.append(str(o["text"]).orEmpty())
                    "linebreak" -> sb.append('\n')
                    else -> sb.append(inline(o))
                }
            }
            return sb.toString()
        }
        for (c in node["children"]?.jsonArray ?: JsonArray(emptyList())) {
            val o = c.jsonObject
            val t = inline(o).replace(' ', ' ').trim()
            if (t.isNotEmpty()) out.addAll(t.split('\n').map { it.trim() }.filter { it.isNotEmpty() })
        }
        return out
    }

    /** Pulls the first Lexical `{"root":{...}}` object out of an RSC payload. */
    private fun extractRoot(text: String): String? {
        val sig = "\"root\":{\"type\":\"root\""
        val i = text.indexOf(sig)
        if (i < 0) return null
        val start = i + "\"root\":".length
        var depth = 0
        var inStr = false
        var esc = false
        var k = start
        while (k < text.length) {
            val ch = text[k]
            if (esc) { esc = false }
            else if (ch == '\\') esc = true
            else if (ch == '"') inStr = !inStr
            else if (!inStr) {
                if (ch == '{') depth++
                else if (ch == '}') { depth--; if (depth == 0) return text.substring(start, k + 1) }
            }
            k++
        }
        return null
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val cid = episodeId.substringAfterLast('/')
        val url = "$baseUrl/chapters/$cid"
        val rsc = client.newCall(request(url).newBuilder().header("rsc", "1").build()).bodyString()
        extractRoot(rsc)?.let { raw ->
            runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()?.let {
                val ps = lexicalParagraphs(it)
                if (ps.isNotEmpty()) return ps
            }
        }
        val html = Jsoup.parse(fetch(url), url)
        return paragraphsOf(html.selectFirst("main > section[data-sentry-component=RichText]"))
    }
}

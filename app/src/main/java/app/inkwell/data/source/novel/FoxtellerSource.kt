package app.inkwell.data.source.novel

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Port of LNReader's `foxteller` plugin. Chapter bodies are base64-obfuscated
 * JSON payloads served by the site's `/aux_dem` endpoint.
 */
class FoxtellerSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "foxteller"
    override val name = "Foxteller"
    override val baseUrl = "https://www.foxteller.com"
    override val lang = "en"

    override val headers: Map<String, String> = mapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3",
        "Referer" to baseUrl,
    )

    private suspend fun safeFetch(url: String): String {
        val data = fetch(url)
        val title = Regex("<title>(.*?)</title>").find(data)?.groupValues?.get(1)?.trim()
        if (title != null && title in setOf(
                "Bot Verification", "You are being redirected...", "Un instant...",
                "Just a moment...", "Redirecting...",
            )
        ) throw IllegalStateException("Captcha error, please open in webview")
        return data
    }

    private suspend fun postForString(url: String, body: String): String = withContext(Dispatchers.IO) {
        val req = request(url).newBuilder()
            .post(body.toRequestBody("application/json;charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).bodyString()
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val body = safeFetch(absPath("library?sort=popularity"))
        val items = mutableListOf<Entry>()
        for (block in Regex("<div class=\"col-md-6\">([\\s\\S]*?)</div>").findAll(body)) {
            val elements = block.value
            val anchor = Regex("<a href=\"(.*?)\" title=\"(.*?)\">").find(elements)?.groupValues
            val novelUrl = anchor?.get(1)
            val novelName = anchor?.get(2)
            if (novelName == null || novelUrl == null) continue
            val seg = novelUrl.split('/')
            val slug = seg.getOrNull(4) ?: continue
            val cover = Regex("<img class=\"img-fluid\" src=\"(.*?)\".*>").find(elements)?.groupValues?.get(1)
            items += Entry(id, slug, novelName, abs(cover), ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val body = postForString(absPath("search"), "{\"query\":${jstr(query)}}")
        val items = mutableListOf<Entry>()
        for (match in Regex("<a.*>([\\s\\S]*?)</a>").findAll(body)) {
            val elements = match.value
            val novelUrl = Regex("<a href=\"(.*?)\"").find(elements)?.groupValues?.get(1).orEmpty()
            val seg = novelUrl.split('/')
            val path = seg.getOrNull(4) ?: continue
            val novelName = Regex("<span class=\"ellipsis-1\">(.*?)</span>").find(elements)?.groupValues?.get(1) ?: continue
            val cover = Regex("<img src=\"(.*?)\".*>").find(elements)?.groupValues?.get(1)
            items += Entry(id, path, novelName, abs(cover), ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    private fun jstr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val body = safeFetch(absPath("novel/$entryId"))
        val d = Jsoup.parse(body, absPath("novel/$entryId"))
        val chapters = chapterList(d, entryId)

        val img = d.selectFirst("img.img-fluid")
        val name = img?.attr("alt")?.trim().orEmpty()

        val genres = d.select("div.novel-genres li").eachText().joinToString(", ")

        val summary = StringBuilder()
        d.selectFirst("div.novel-description")?.childNodes()?.forEach { node ->
            when (node) {
                is org.jsoup.nodes.TextNode -> summary.append(node.text().trim())
                is org.jsoup.nodes.Element -> {
                    summary.append(node.text().trim())
                    if (node.tagName() == "hr" || node.tagName() == "p") summary.append('\n')
                }
            }
        }

        var status: String? = null
        for (li in d.select("div.novel-tags li")) {
            status = when (li.text().lowercase().trim()) {
                "completed" -> "Completed"
                "ongoing" -> "Ongoing"
                "hiatus" -> "On Hiatus"
                else -> null
            }
        }

        return EntryDetails(
            entry = Entry(id, entryId, name.ifEmpty { "Untitled" }, abs(img?.attr("src")), ContentType.NOVEL),
            description = summary.toString().trim().ifEmpty { null },
            authors = emptyList(),
            genres = genres.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    private fun chapterList(d: Document, novelPath: String): List<Episode> {
        val chapters = mutableListOf<Episode>()
        for (a in d.select("div.col-md-6 a")) {
            val paid = a.select("i").any { it.className().contains("lock") }
            if (paid) continue
            val seg = a.attr("href").split('/')
            val chapterSeg = seg.getOrNull(5) ?: continue
            val name = a.text().trim()
            chapters += Episode(
                id = "$novelPath/$chapterSeg",
                name = name,
                number = (chapters.size + 1).toFloat(),
                dateMillis = null,
            )
        }
        return chapters
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        val body = safeFetch(absPath("novel/$entryId"))
        return chapterList(Jsoup.parse(body, absPath("novel/$entryId")), entryId)
            .also { cachedChapters[entryId] = it }
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val body = safeFetch(absPath("novel/$episodeId"))
        val novelId = episodeId.split('/')[0]
        val chapterId = Regex("'chapter_id': '([\\d]+)'").find(body)?.groupValues?.get(1)
            ?: throw IllegalStateException("No chapter found")

        val root = jsonPost(absPath("aux_dem"), "{\"x1\":${jstr(novelId)},\"x2\":${jstr(chapterId)}}").jsonObject
        val aux = root.str("aux")

        if (!aux.isNullOrEmpty()) {
            val base64 = aux.replace(Regex("%R([a-f])&")) { m ->
                when (m.groupValues[1]) {
                    "a" -> "A"; "c" -> "B"; "b" -> "C"; "d" -> "D"; "f" -> "E"; "e" -> "F"
                    else -> m.value
                }
            }
            val html = percentDecode(decodeBase64(base64))
            return paragraphsOf(Jsoup.parseBodyFragment(html).body())
        }
        throw IllegalStateException("This chapter is closed")
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    /** Faithful port of the plugin's custom base64-to-percent-hex decoder. */
    private fun decodeBase64(encodedString: String): String {
        val base64Characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/="
        val hexcode = mutableListOf<String>()
        var i = 0
        fun at(idx: Int): Int =
            if (idx < encodedString.length) base64Characters.indexOf(encodedString[idx]) else -1
        while (i < encodedString.length) {
            val enc1 = at(i); val enc2 = at(i + 1); val enc3 = at(i + 2); val enc4 = at(i + 3)
            i += 4
            val chr1 = ((enc1 shl 2) or (enc2 shr 4)) and 0xFF
            val chr2 = (((enc2 and 15) shl 4) or (enc3 shr 2)) and 0xFF
            val chr3 = (((enc3 and 3) shl 6) or enc4) and 0xFF
            hexcode += chr1.toString(16).padStart(2, '0')
            if (enc3 != 64) hexcode += chr2.toString(16).padStart(2, '0')
            if (enc4 != 64) hexcode += chr3.toString(16).padStart(2, '0')
        }
        return "%" + hexcode.joinToString("%")
    }

    /** Decodes the '%' + hex-percent-encoding produced above, without '+' handling. */
    private fun percentDecode(s: String): String {
        val bytes = mutableListOf<Byte>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    bytes += v.toByte()
                    i += 3
                    continue
                }
            }
            for (b in c.toString().toByteArray(Charsets.UTF_8)) bytes += b
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}

package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's noveldex plugin (Next.js RSC flight stream + XOR-obfuscated chapters). */
class NoveldexSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "noveldex"
    override val name = "NovelDex"
    override val baseUrl = "https://noveldex.io"
    override val lang = "en"

    override val headers = mapOf("Referer" to "https://noveldex.io", "RSC" to "1")

    private fun s(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull

    private fun findClientId(rsc: String, component: String): String? {
        val esc = Regex.escape(component)
        Regex("""(?m)^([0-9a-fA-F]+):I\[(\d+),\[[^\]]*\],"$esc"\]""").find(rsc)?.let { return it.groupValues[1] }
        val ref = Regex("""(?m)^([0-9a-fA-F]+):"$esc"$""").find(rsc)?.groupValues?.get(1) ?: return null
        return Regex("""(?m)^([0-9a-fA-F]+):I\[(\d+),\[[^\]]*\],"\$$ref"\]""").find(rsc)?.groupValues?.get(1)
    }

    private fun balanced(text: String, start: Int): String? {
        val first = text.getOrNull(start) ?: return null
        if (first != '{' && first != '[') return null
        val close = if (first == '{') '}' else ']'
        var depth = 0
        var inStr = false
        var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (esc) { esc = false; continue }
            if (c == '\\') { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (inStr) continue
            if (c == first) depth++
            else if (c == close) { depth--; if (depth == 0) return text.substring(start, i + 1) }
        }
        return null
    }

    private fun extract(rsc: String, component: String, accept: (JsonObject) -> Boolean): JsonObject? {
        val cid = findClientId(rsc, component) ?: return null
        val marker = "\"\$L$cid\",null,"
        var idx = rsc.indexOf(marker)
        while (idx != -1) {
            balanced(rsc, idx + marker.length)?.let { txt ->
                runCatching { json.parseToJsonElement(txt).jsonObject }.getOrNull()?.let { if (accept(it)) return it }
            }
            idx = rsc.indexOf(marker, idx + marker.length)
        }
        return null
    }

    private fun browse(rsc: String): List<Entry> {
        val o = extract(rsc, "SeriesBrowseGrid") { it["initialSeries"] is JsonArray } ?: return emptyList()
        return o["initialSeries"]!!.jsonArray.mapNotNull {
            val x = it as? JsonObject ?: return@mapNotNull null
            val slug = s(x["urlSlug"]) ?: return@mapNotNull null
            Entry(id, "/series/novel/$slug", s(x["title"]).orEmpty(), s(x["coverImage"])?.let { c -> abs(c) }, ContentType.NOVEL)
        }
    }

    private fun seriesUrl(page: Int, q: String?, sort: String?): String {
        val p = ArrayList<String>()
        if (sort != null) p.add("sort=$sort")
        if (!q.isNullOrBlank()) p.add("q=" + java.net.URLEncoder.encode(q, "UTF-8"))
        if (page > 1) p.add("page=$page")
        return "$baseUrl/series" + if (p.isNotEmpty()) "?" + p.joinToString("&") else ""
    }

    private suspend fun list(page: Int, q: String?, sort: String?): PagedResult<Entry> {
        val items = browse(fetch(seriesUrl(page, q, sort)))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = list(page, null, "views")
    override suspend fun latest(page: Int) = list(page, null, "updated")
    override suspend fun search(query: String, page: Int) =
        if (query.isBlank()) PagedResult(emptyList(), false) else list(page, query, "views")

    private suspend fun seriesPage(path: String, page: Int): JsonObject? =
        extract(fetch("$baseUrl$path?page=$page"), "SeriesDetailClient") { it["series"] != null && it["chapters"] is JsonArray }

    private fun clean(p: String) = p.substringBefore('?')

    override suspend fun details(entryId: String): EntryDetails {
        val base = clean(entryId)
        val first = seriesPage(base, 1) ?: error("NovelDex: could not load series")
        val se = first["series"]!!.jsonObject
        val desc = s(se["description"])?.replace("\\r\\n", "\n")?.replace("\\n", "\n")?.trim()?.takeIf { it.isNotEmpty() }
        val genres = (se["genres"] as? JsonArray)?.mapNotNull { s((it as? JsonObject)?.get("name")) }?.filter { it.isNotEmpty() }.orEmpty()
        val status = when (s(se["status"])?.uppercase()) {
            "ONGOING" -> "Ongoing"
            "COMPLETED" -> "Completed"
            "HIATUS" -> "On Hiatus"
            "DROPPED", "DISCONTINUED", "CANCELLED", "CANCELED" -> "Cancelled"
            else -> null
        }
        return EntryDetails(
            entry = Entry(id, base, s(se["title"]) ?: "Untitled", s(se["coverImage"])?.let { abs(it) }, ContentType.NOVEL),
            description = desc,
            authors = emptyList(),
            genres = genres,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val base = clean(entryId)
        val first = seriesPage(base, 1) ?: return emptyList()
        val total = first["totalPages"]?.jsonPrimitive?.intOrNull ?: 1
        val out = ArrayList<Episode>()
        fun add(o: JsonObject) {
            o["chapters"]?.jsonArray?.forEach {
                val c = it as? JsonObject ?: return@forEach
                val num = s(c["number"]) ?: return@forEach
                val title = s(c["title"])?.takeIf { t -> t.isNotEmpty() } ?: "Untitled"
                val locked = s(c["hasAccess"]) == "false"
                out.add(
                    Episode(
                        "$base/chapter/$num", if (locked) "🔒 $title" else title,
                        num.toFloatOrNull(), parseDate(s(c["publishedAt"])),
                    )
                )
            }
        }
        add(first)
        for (p in 2..total) {
            runCatching { seriesPage(base, p) }.getOrNull()?.let { add(it) }
            delay(100)
        }
        return out.sortedBy { it.number ?: 0f }
    }

    private fun deriveKey(hint: String, timestamp: String, nonce: String): ByteArray {
        val material = "$hint|$timestamp|$nonce"
        val key = ByteArray(32)
        var i = 0x811c9dc5.toInt()
        for (ch in material) {
            i = i xor ch.code
            i *= 0x1000193
        }
        for (k in 0 until 32) {
            i = i xor (k * 0x9e3779b9.toInt())
            i *= 0x1000193
            key[k] = (i and 0xff).toByte()
        }
        return key
    }

    private fun deferredText(rsc: String, ref: String): String? {
        val m = Regex("""(?:^|\n)${Regex.escape(ref)}:T([0-9a-fA-F]+),""").find(rsc) ?: return null
        val start = m.range.last + 1
        val len = m.groupValues[1].toInt(16)
        val bytes = rsc.substring(start).toByteArray(Charsets.UTF_8)
        return String(bytes, 0, minOf(len, bytes.size), Charsets.UTF_8)
    }

    override suspend fun chapterText(episodeId: String): List<String> = withContext(Dispatchers.IO) {
        val rsc = fetch("$baseUrl$episodeId")
        if (Regex(""""isUnlocked"\s*:\s*false""").containsMatchIn(rsc)) return@withContext emptyList()
        val xm = Regex(""""xorEncryption"\s*:\s*(\{[^}]+\})""").find(rsc)?.groupValues?.get(1)
            ?: return@withContext emptyList()
        val xor = json.parseToJsonElement(xm).jsonObject
        val enc = s(xor["encryptedBase64"]).orEmpty()
        val ref = Regex("""^\$([0-9a-zA-Z]+)$""").find(enc)?.groupValues?.get(1)
        val b64 = if (ref != null) deferredText(rsc, ref) ?: return@withContext emptyList() else enc
        val cipher = java.util.Base64.getDecoder().decode(b64.filter { (it in 'A'..'Z') || (it in 'a'..'z') || (it in '0'..'9') || it == '+' || it == '/' })
        val key = deriveKey(s(xor["partialKeyHint"]).orEmpty(), s(xor["timestamp"]).orEmpty(), s(xor["clientNonce"]).orEmpty())
        val plain = ByteArray(cipher.size) { (cipher[it].toInt() xor key[it % key.size].toInt()).toByte() }
        val content = String(plain, Charsets.UTF_8)
        if (content.isBlank()) return@withContext emptyList()
        val html = content.replace("\r\n", "\n").replace("\r", "\n")
            .split(Regex("\n{2,}")).map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("") { p ->
                if (Regex("^<(?:p|div|figure|img|h[1-6]|blockquote|hr|ul|ol)\\b", RegexOption.IGNORE_CASE).containsMatchIn(p)) p
                else "<p>${p.replace("\n", "<br>")}</p>"
            }
        paragraphsOf(Jsoup.parse(html).body())
    }
}

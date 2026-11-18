package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/** Port of LNReader's penguinsquad plugin (Next.js site; chapter list from RSC flight payload). */
class PenguinSquadSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "penguinsquad"
    override val name = "Penguin Squad"
    override val baseUrl = "https://penguin-squad.com"
    override val lang = "en"

    private val novelCardPath = Regex("^novels/[^/]+$")
    private val chapterPrefix = Regex("^(?:chapter|ch\\.?|episode|ep\\.?|prologue|epilogue)\\b", RegexOption.IGNORE_CASE)

    private fun cards(d: Document): List<Entry> {
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        for (a in d.select("a.group[href^=/novels/]")) {
            val path = a.attr("href").trimStart('/')
            if (!novelCardPath.matches(path) || !seen.add(path)) continue
            val title = a.selectFirst("h3")?.text()?.trim().orEmpty()
            if (title.isEmpty()) continue
            out += Entry(id, path, title, abs(a.selectFirst("img")?.attr("src")?.trim()), ContentType.NOVEL)
        }
        return out
    }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(cards(doc("$baseUrl/novels?sort=trending")), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(cards(doc("$baseUrl/novels")), false)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val q = query.trim()
        if (page > 1 || q.isEmpty()) return PagedResult(emptyList(), false)
        val url = "$baseUrl/search".toHttpUrl().newBuilder().addQueryParameter("q", q).build()
        return PagedResult(cards(doc(url)), false)
    }

    private val chapterCache = HashMap<String, List<Episode>>()

    private fun findPayload(node: JsonElement): JsonObject? = when (node) {
        is JsonArray -> node.firstNotNullOfOrNull { findPayload(it) }
        is JsonObject -> {
            if ((node["novelSlug"] as? JsonPrimitive)?.isString == true && node["chapters"] is JsonArray) node
            else node.values.firstNotNullOfOrNull { findPayload(it) }
        }
        else -> null
    }

    private fun payloadFrom(text: String): JsonObject? {
        for (line in text.split('\n')) {
            if (!line.contains("\"chapters\":[")) continue
            val colon = line.indexOf(':')
            if (colon == -1) continue
            val parsed = runCatching { json.parseToJsonElement(line.substring(colon + 1)) }.getOrNull() ?: continue
            findPayload(parsed)?.let { return it }
        }
        return null
    }

    private fun extractPayload(html: String): JsonObject? {
        val chunks = StringBuilder()
        val marker = "self.__next_f.push([1,\""
        var from = 0
        while (true) {
            val st = html.indexOf(marker, from)
            if (st == -1) break
            val q0 = st + marker.length - 1
            var i = q0 + 1
            while (i < html.length) {
                val c = html[i]
                if (c == '\\') i += 2 else if (c == '"') break else i++
            }
            if (i >= html.length) break
            from = i + 1
            val decoded = runCatching { json.parseToJsonElement(html.substring(q0, i + 1)).jsonPrimitive.content }.getOrNull() ?: continue
            if (decoded.contains("\"chapters\":[")) payloadFrom(decoded)?.let { return it }
            chunks.append(decoded)
        }
        return payloadFrom(chunks.toString())
    }

    private fun chapterName(rawTitle: String, num: Float?, locked: Boolean): String {
        val title = rawTitle.trim()
        val n = num?.toString()?.removeSuffix(".0")
        val base = when {
            title.isEmpty() -> "Chapter ${n ?: ""}".trim()
            chapterPrefix.containsMatchIn(title) || n == null -> title
            else -> "Chapter $n - $title"
        }
        return if (locked) "🔒 $base" else base
    }

    override suspend fun details(entryId: String): EntryDetails {
        val path = entryId.trim('/')
        val html = fetch("$baseUrl/$path")
        val d = org.jsoup.Jsoup.parse(html.replace(Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), ""), baseUrl)
        val genres = d.select("a[href^=/novels?genre=], a[href^=/novels?tag=]").map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()
        var status: String? = null
        for (b in d.select("span[data-slot=badge]")) {
            when (b.text().trim().lowercase()) {
                "ongoing" -> status = "Ongoing"
                "completed" -> status = "Completed"
                "dropped" -> status = "Cancelled"
            }
        }
        val cover = d.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotEmpty() }
            ?: d.selectFirst("img[src*=/covers/]")?.attr("src")
        val eps = ArrayList<Episode>()
        val payload = extractPayload(html)
        val chs = payload?.get("chapters") as? JsonArray
        val slug = payload?.get("novelSlug")?.jsonPrimitive?.contentOrNull
        if (chs != null && slug != null) {
            for (el in chs) {
                val c = el.jsonObject
                val cs = (c["slug"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
                val num = c["chapter_number"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                eps += Episode(
                    id = "novels/$slug/$cs",
                    name = chapterName((c["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(), num, c["premium"]?.jsonPrimitive?.contentOrNull == "true"),
                    number = num,
                    dateMillis = parseDate((c["published_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content),
                )
            }
        }
        if (eps.isEmpty()) {
            for (a in d.select("a[href^=/$path/]")) {
                val num = a.selectFirst("span")?.text()?.trim()?.toFloatOrNull()
                eps += Episode(
                    id = a.attr("href").trimStart('/'),
                    name = chapterName(a.selectFirst("p")?.text()?.trim().orEmpty(), num, a.selectFirst("svg.lucide-lock") != null),
                    number = num,
                    dateMillis = null,
                )
            }
        }
        chapterCache[entryId] = eps
        return EntryDetails(
            entry = Entry(id, entryId, d.selectFirst("h1")?.text()?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled", abs(cover), ContentType.NOVEL),
            description = d.selectFirst("p.line-clamp-10")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: d.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(d.selectFirst("a[href^=/search?q=]")?.text()?.trim()?.takeIf { it.isNotEmpty() }),
            genres = genres,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        chapterCache[entryId]?.let { return it }
        details(entryId)
        return chapterCache[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc("$baseUrl/${episodeId.trimStart('/')}")
        if (d.select("h2").any { Regex("unlock this chapter", RegexOption.IGNORE_CASE).containsMatchIn(it.text()) }) {
            error("This chapter is locked. Unlock it on Penguin Squad to read it.")
        }
        return paragraphsOf(d.selectFirst(".reader-content"))
    }
}

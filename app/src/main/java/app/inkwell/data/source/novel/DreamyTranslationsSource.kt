package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Dreamy Translations is a Next.js app. Requests carrying the RSC header get
 * a React Server Component Flight response holding the actual page data, so
 * every call parses RSC records instead of HTML.
 */
class DreamyTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "dreamyTranslations"
    override val name = "Dreamy Translations"
    override val baseUrl = "https://dreamy-translations.com"
    override val lang = "en"

    override val userAgent: String =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"

    override val headers: Map<String, String> = mapOf(
        "Referer" to baseUrl,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "RSC" to "1",
    )

    // ---- RSC (Flight) parsing ---------------------------------------------

    private suspend fun fetchRsc(url: String): String = fetch(url)

    /**
     * Extract an object from a normal JSON RSC record; Flight records commonly
     * carry the application data at index 3 of the parsed array.
     */
    private fun extractRscObject(rscText: String, marker: String): JsonElement {
        val line = rscText.split('\n').firstOrNull { it.contains(marker) }
            ?: throw IllegalStateException("Could not find \"$marker\" in Dreamy Translations RSC response")
        val colonIndex = line.indexOf(':')
        if (colonIndex == -1) throw IllegalStateException("Invalid Dreamy Translations RSC record")
        val parsed = json.parseToJsonElement(line.substring(colonIndex + 1))
        if (parsed is JsonArray && parsed.size > 3) return parsed[3]
        return parsed
    }

    /** Slice a streamed "ref:T<hexlen>," record by UTF-8 byte length. */
    private fun extractDeferredText(rscText: String, refId: String): String {
        val escaped = Regex.escape(refId)
        val match = Regex("(?:^|\\n)$escaped:T([0-9a-fA-F]+),")
            .find(rscText) ?: throw IllegalStateException("Could not locate chapter content in server response")
        val start = match.range.first + match.value.length
        val byteLength = match.groupValues[1].toInt(16)
        val bytes = rscText.substring(start).toByteArray(Charsets.UTF_8)
        val slice = if (byteLength < bytes.size) bytes.copyOfRange(0, byteLength) else bytes
        return slice.toString(Charsets.UTF_8)
    }

    private fun findDeferredText(rscText: String, refId: String): String? = runCatching {
        extractDeferredText(rscText, refId)
    }.getOrNull()

    private fun hasChapterKey(o: JsonElement): Boolean =
        o is JsonObject && o.containsKey("chapter")

    /** Chapter-specific extraction: normal record, direct scan, then fallbacks. */
    private fun extractChapterObject(rscText: String): JsonObject {
        val lines = rscText.split('\n')

        // 1) Normal Flight record line holding both keys.
        val chapterLine = lines.firstOrNull { it.contains("\"chapter\"") && it.contains("\"hasAccess\"") }
        if (chapterLine != null) {
            val colonIndex = chapterLine.indexOf(':')
            if (colonIndex != -1) {
                val parsed = runCatching { json.parseToJsonElement(chapterLine.substring(colonIndex + 1)) }.getOrNull()
                if (parsed != null) {
                    if (parsed is JsonArray && parsed.size > 3 && parsed[3] is JsonObject) return parsed[3].jsonObject
                    if (parsed is JsonObject) return parsed
                }
            }
        }

        // 2) Locate the chapter object directly and parse a balanced object.
        val marker = "\"chapter\":{"
        val markerIndex = rscText.indexOf(marker)
        if (markerIndex != -1) {
            var start = -1
            var i = markerIndex
            while (i >= 0) {
                if (rscText[i] == '{' &&
                    rscText.substring(i, markerIndex + marker.length).contains("\"chapter\"")
                ) {
                    start = i
                    break
                }
                i--
            }
            if (start != -1) {
                var depth = 0
                var inString = false
                var escaped = false
                for (j in start until rscText.length) {
                    val c = rscText[j]
                    if (inString) {
                        when {
                            escaped -> escaped = false
                            c == '\\' -> escaped = true
                            c == '"' -> inString = false
                        }
                        continue
                    }
                    when (c) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                val parsed = runCatching {
                                    json.parseToJsonElement(rscText.substring(start, j + 1))
                                }.getOrNull()
                                if (parsed is JsonObject) return parsed
                                break
                            }
                        }
                    }
                }
            }
        }

        // 3) Fall back to any JSON-looking Flight record holding a "chapter".
        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon == -1) continue
            val payload = line.substring(colon + 1).trimStart()
            if (!payload.startsWith("[") && !payload.startsWith("{")) continue
            val parsed = runCatching { json.parseToJsonElement(payload) }.getOrNull() ?: continue
            if (parsed is JsonArray) {
                for (value in parsed) if (hasChapterKey(value)) return value.jsonObject
            }
            if (hasChapterKey(parsed)) return parsed.jsonObject
        }
        throw IllegalStateException("Could not parse chapter data from Dreamy Translations response")
    }

    // ---- listing -----------------------------------------------------------

    private suspend fun fetchAllNovels(): List<Entry> {
        val rscText = fetchRsc("$baseUrl/series")
        val data = extractRscObject(rscText, "\"projects\"").jsonObject
        val projects = data["projects"]?.jsonArray ?: emptyList()
        val covers = data["squareImageUrls"]?.jsonObject
        return projects.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val slug = o["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val cover = o["id"]?.jsonPrimitive?.contentOrNull?.let { covers?.get(it)?.jsonPrimitive?.contentOrNull }
            Entry(id, "novel/$slug", title, abs(cover), ContentType.NOVEL)
        }
    }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(fetchAllNovels(), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val term = query.lowercase()
        return PagedResult(fetchAllNovels().filter { it.title.lowercase().contains(term) }, false)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val rscText = fetchRsc(absPath(entryId))
        val data = extractRscObject(rscText, "\"chapters\":[").jsonObject
        val project = data["project"]?.jsonObject
        val title = project?.get("title")?.jsonPrimitive?.contentOrNull?.takeUnless { it.isEmpty() } ?: "Untitled"
        val chaptersRoot = data["chapters"]?.jsonArray ?: emptyList()
        val chapters = chaptersRoot.mapNotNull { el ->
            val ch = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val index = ch["index"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: return@mapNotNull null
            val chTitle = ch["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val free = ch["free"]?.jsonPrimitive?.contentOrNull == "true"
            Episode(
                id = "$entryId/chapter/${index.toInt()}",
                name = if (free) chTitle else "🔒 $chTitle",
                number = index,
                dateMillis = null,
            )
        }
        return EntryDetails(
            entry = Entry(id, entryId, title, abs(data["coverUrl"]?.jsonPrimitive?.contentOrNull), ContentType.NOVEL),
            description = (project?.get("synopsis")?.jsonPrimitive?.contentOrNull
                ?: project?.get("short_synopsis")?.jsonPrimitive?.contentOrNull)?.takeUnless { it.isEmpty() },
            authors = listOfNotNull(project?.get("author")?.jsonPrimitive?.contentOrNull?.takeUnless { it.isEmpty() }),
            genres = project?.get("genres")?.jsonArray
                ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
                .orEmpty(),
            status = if (project?.get("completed")?.jsonPrimitive?.contentOrNull == "true") "Completed" else "Ongoing",
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val rscText = fetchRsc(absPath(episodeId))
        val data = extractChapterObject(rscText)
        val hasAccess = data["hasAccess"]?.jsonPrimitive?.contentOrNull == "true"
        if (!hasAccess) return emptyList()

        var content = data["chapter"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
        // Content may be a reference to a streamed RSC text record.
        val refMatch = Regex("^\\$([0-9a-zA-Z]+)$").find(content)
        if (refMatch != null) {
            findDeferredText(rscText, refMatch.groupValues[1])?.let { content = it }
        }
        if (content.isBlank()) return emptyList()

        val normalized = content
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(
                Regex("(\\bsrc\\s*=\\s*[\"'])\\[([^\\]]+)]\\(\\2\\)([\"'])", RegexOption.IGNORE_CASE),
                "$1$2$3",
            )
        val html = normalized.split(Regex("\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("") { "<p>${it.replace("\n", "<br>")}</p>" }
        return paragraphsOf(Jsoup.parseBodyFragment(html).body())
    }
}

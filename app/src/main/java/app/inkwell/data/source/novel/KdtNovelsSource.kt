package app.inkwell.data.source.novel

import kotlinx.serialization.json.contentOrNull

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * KDT Novels (kdtnovels.net) — port of LNReader's kdtnovels plugin. The site
 * is its own Next.js app: listing/search at `/search/`, novel pages carry the
 * chapter list in the React Server Components flight payload.
 */
class KdtNovelsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "kdtnovels"
    override val name = "KDT Novels"
    override val baseUrl = "https://kdtnovels.net"
    override val lang = "en"

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cachedEpisodes = HashMap<String, List<Episode>>()

    private data class KdtChapter(
        val slug: String,
        val no: String,
        val subtitle: String?,
        val locked: Boolean,
    )

    private data class KdtGroup(val label: String, val chapters: List<KdtChapter>)

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> = listPage(page, null)
    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> =
        listPage(page, query.trim())

    private suspend fun listPage(page: Int, query: String?): PagedResult<Entry> {
        val url = absPath("search/") + "?page=$page" +
            (query?.let { "&q=" + enc(it) } ?: "")
        val d = doc(url)
        val items = d.select("a.group[href^=/series/]").mapNotNull { card ->
            val href = card.attr("href")
            if (!href.startsWith("/series/")) return@mapNotNull null
            val title = card.selectFirst("h3") ?: return@mapNotNull null
            val nm = title.attr("title").takeIf { it.isNotBlank() } ?: title.text().trim()
            Entry(id, href.removePrefix("/"), nm, absolute(card.selectFirst("img")?.attr("src")), ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val html = fetch(absPath(entryId))
        val d = Jsoup.parse(html, absPath(entryId))
        val title = d.selectFirst("main h1")

        val genres = d.select("main a[href^=\"/search/?genre=\"]").eachText()
            .map { it.trim() }.filter { it.isNotEmpty() }

        val summaryEl = d.selectFirst("main .reader-content")
        val paragraphs = summaryEl?.select("p")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val summary = if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n")
        else summaryEl?.text()?.trim()

        // "<author> · Art: <artist>", right below the title; the view counter
        // that follows is a `.flex` paragraph.
        var author: String? = null
        var artist: String? = null
        var sib: Element? = title?.parent()?.nextElementSibling()
        while (sib != null) {
            if (sib.tagName() == "p" && !sib.hasClass("flex")) {
                val credits = sib.text().split(" · Art: ")
                author = credits.getOrNull(0)?.trim()?.takeIf { it.isNotEmpty() }
                artist = credits.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                break
            }
            sib = sib.nextElementSibling()
        }

        // Only finished or paused series carry a badge next to the title.
        val badge = title?.parent()?.children()?.firstOrNull { it.tagName() == "span" }?.text()?.trim()?.lowercase().orEmpty()
        val status = when (badge) {
            "" -> "Ongoing"
            "completed" -> "Completed"
            "hiatus" -> "On Hiatus"
            else -> null
        }

        val episodes = mutableListOf<Episode>()
        val groups = parseChapterGroups(html)
        if (groups != null) {
            for (group in groups) {
                for (chapter in group.chapters) {
                    var nm = group.label + " · Ch. " + chapter.no
                    if (chapter.subtitle != null) nm += ": " + chapter.subtitle
                    if (chapter.locked) nm = "🔒 $nm"
                    episodes.add(
                        Episode(
                            id = chapter.slug + "/",
                            name = nm,
                            number = (episodes.size + 1).toFloat(),
                            dateMillis = null,
                        ),
                    )
                }
            }
        }

        return EntryDetails(
            entry = Entry(
                id,
                entryId,
                title?.text()?.trim()?.ifEmpty { "Untitled" } ?: "Untitled",
                absolute(d.selectFirst("meta[property=og:image]")?.attr("content")),
                ContentType.NOVEL,
            ),
            description = summary?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(author, artist),
            genres = genres,
            status = status,
        ).also { cachedEpisodes[entryId] = episodes }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedEpisodes[entryId]?.let { return it }
        details(entryId)
        return cachedEpisodes[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.selectFirst("article.reader-content") ?: return emptyList()
        content.select("script, style, noscript").remove()
        // Illustrations are placeholders that the site's script swaps for images.
        content.select(".kdt-ilus[data-ilus]").forEach { el ->
            val src = absolute(el.attr("data-ilus"))
            if (src == null) el.remove()
            else el.replaceWith(Element("img").attr("src", src))
        }
        // Drop event handlers and URLs that would run script.
        content.select("*").forEach { node ->
            node.attributes().toList().forEach { attr ->
                val nm = attr.key
                when {
                    Regex("^on", RegexOption.IGNORE_CASE).containsMatchIn(nm) -> node.removeAttr(nm)
                    nm == "href" || nm == "src" -> {
                        val v = attr.value.filter { it.code > 32 }.lowercase()
                        if (v.startsWith("javascript:") || v.startsWith("vbscript:") ||
                            (v.startsWith("data:") && !v.startsWith("data:image/"))
                        ) {
                            node.removeAttr(nm)
                        }
                    }
                }
            }
        }
        return paragraphsOf(content)
    }

    // ---- flight payload -----------------------------------------------------

    /**
     * Reads the volume groups from the Next.js flight payload, which the page
     * streams as `self.__next_f.push([1, "<chunk>"])` script calls.
     */
    private fun parseChapterGroups(html: String): List<KdtGroup>? {
        val flight = StringBuilder()
        // Scan string literals by hand: a regex over the big payload overflows the stack.
        val marker = "self.__next_f.push([1,\""
        var from = 0
        while (true) {
            val start = html.indexOf(marker, from)
            if (start < 0) break
            val litStart = start + marker.length - 1
            var i = litStart + 1
            while (i < html.length) {
                val ch = html[i]
                if (ch == '\\') i += 2 else if (ch == '"') break else i++
            }
            if (i >= html.length) break
            val literal = html.substring(litStart, i + 1)
            from = i + 1
            val chunk = runCatching { jsonParser.parseToJsonElement(literal) }.getOrNull() ?: continue
            flight.append(chunk.jsonPrimitive.content)
        }
        for (line in flight.toString().split('\n')) {
            if (!line.contains("\"groups\":[")) continue
            val row = runCatching { jsonParser.parseToJsonElement(line.substringAfter(':')) }
                .getOrNull() ?: continue
            findGroups(row)?.let { return it }
        }
        return null
    }

    /** Finds the first `groups` array of chapter groups in a flight row. */
    private fun findGroups(node: JsonElement?): List<KdtGroup>? {
        when (node) {
            is JsonObject -> {
                val groups = node["groups"]
                if (groups is JsonArray && groups.all { (it as? JsonObject)?.get("chapters") is JsonArray }) {
                    return groups.mapNotNull { g -> toGroup(g as JsonObject) }
                }
                for (value in node.values) {
                    findGroups(value)?.let { return it }
                }
            }
            is JsonArray -> {
                for (value in node) {
                    findGroups(value)?.let { return it }
                }
            }
            else -> return null
        }
        return null
    }

    private fun toGroup(o: JsonObject): KdtGroup? {
        val chapters = (o["chapters"] as? JsonArray)?.mapNotNull { el ->
            val c = el as? JsonObject ?: return@mapNotNull null
            KdtChapter(
                slug = c["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                no = c["chapter_no"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                subtitle = c["subtitle"]?.jsonPrimitive?.contentOrNull,
                locked = c["locked"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        } ?: return null
        return KdtGroup(o["label"]?.jsonPrimitive?.content ?: "", chapters)
    }

    // ---- helpers -----------------------------------------------------------

    private fun absolute(url: String?): String? {
        url ?: return null
        return if (url.startsWith("/")) baseUrl.trimEnd('/') + url else url
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

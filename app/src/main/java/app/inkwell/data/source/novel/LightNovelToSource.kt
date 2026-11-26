package app.inkwell.data.source.novel


import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonNull

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * LightNovel.to — port of LNReader's lightnovelto plugin. Catalogue at
 * /novels, chapter list at /api/novel-chapters?slug=, chapter body inside
 * `div.ln-prose`.
 */
class LightNovelToSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "lightnovelto"
    override val name = "LightNovel.to"
    override val baseUrl = "https://lightnovel.to"
    override val lang = "en"

    private val cachedEpisodes = HashMap<String, List<Episode>>()
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int) = listPage(page, "popular")
    override suspend fun latest(page: Int) = listPage(page, "latest")

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val d = doc(absPath("novels") + "?search=" + enc(query) + "&page=$page")
        val items = parseCards(d)
        return PagedResult(items, items.isNotEmpty())
    }

    private suspend fun listPage(page: Int, sort: String): PagedResult<Entry> {
        val d = doc(absPath("novels") + "?sort=$sort&page=$page")
        val items = parseCards(d)
        return PagedResult(items, items.isNotEmpty())
    }

    /**
     * Catalog / search cards: <a class="ln-ncard" href="/novel/<slug>">
     * wrapping a cover <img> and an <h3> title.
     */
    private fun parseCards(d: org.jsoup.nodes.Document): List<Entry> {
        val seen = HashSet<String>()
        return d.select("a.ln-ncard").mapNotNull { a ->
            val href = a.attr("href")
            if (!href.startsWith("/novel/")) return@mapNotNull null
            val path = href.removePrefix("/")
            if (!seen.add(path)) return@mapNotNull null
            var name = a.selectFirst("h3")?.text()?.trim().orEmpty()
            val img = a.selectFirst("img")
            if (name.isEmpty() && img != null) name = img.attr("alt").trim()
            if (name.isEmpty()) return@mapNotNull null
            Entry(id, path, name, abs(img?.attr("src")), ContentType.NOVEL,
                app.inkwell.data.model.chapterNumber(a.selectFirst("p:contains(chapters)")?.text()))
        }
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.removePrefix("novel/").trim('/')
        val d = doc(absPath(entryId))
        val book = findLdBook(d)

        var name = book?.get("name")?.cleanText().orEmpty()
        if (name.isEmpty()) {
            name = d.selectFirst("h1.ln-dtitle")?.text()?.trim().orEmpty()
        }
        var cover = book?.get("image")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (cover.isEmpty()) {
            cover = d.selectFirst("div.ln-dcv img")?.attr("src").orEmpty()
        }
        val authors = mutableListOf<String>()
        (book?.get("author") as? JsonArray)?.forEach { el ->
            (el as? JsonObject)?.get("name")?.cleanText()?.takeIf { it.isNotEmpty() }?.let { authors.add(it) }
        }
        val genres = (book?.get("genre") as? JsonArray)
            ?.mapNotNull { it.cleanText() }?.filter { it.isNotEmpty() }.orEmpty()
        val statusRaw = book?.get("creativeWorkStatus")?.jsonPrimitive?.contentOrNull.orEmpty()

        val episodes = chapterList(slug)
        return EntryDetails(
            entry = Entry(id, entryId, name.ifBlank { "Untitled" }, abs(cover.ifEmpty { null }), ContentType.NOVEL),
            description = book?.get("description")?.cleanText()?.ifBlank { null },
            authors = authors,
            genres = genres,
            // schema.org creativeWorkStatus: "Completed" | "Incomplete" (ongoing).
            status = when (statusRaw) {
                "Completed" -> "Completed"
                "Incomplete" -> "Ongoing"
                else -> null
            },
        ).also { cachedEpisodes[entryId] = episodes }
    }

    /** The novel page embeds the Book schema object in JSON-LD. */
    private fun findLdBook(d: org.jsoup.nodes.Document): JsonObject? {
        for (script in d.select("script[type=application/ld+json]")) {
            val data = runCatching { jsonParser.parseToJsonElement(script.data()) }.getOrNull() ?: continue
            val graph = (data as? JsonObject)?.get("@graph") as? JsonArray ?: listOf(data)
            for (node in graph) {
                val o = node as? JsonObject ?: continue
                if (o["@type"]?.jsonPrimitive?.contentOrNull == "Book") return o
            }
        }
        return null
    }

    /**
     * Chapter list from GET /api/novel-chapters?slug=<slug>, sorted ascending
     * by chapter number; the chapter's site path is /novel/<novel>/<entry>.
     */
    private suspend fun chapterList(slug: String): List<Episode> {
        val root = runCatching {
            json(absPath("api/novel-chapters") + "?slug=" + enc(slug))
        }.getOrNull() ?: return emptyList()
        val raw = when (root) {
            is JsonArray -> root
            is JsonObject -> root["chapters"] as? JsonArray ?: return emptyList()
            else -> return emptyList()
        }
        val entries = mutableListOf<Triple<Int, Episode, Int>>() // number, episode, original index
        raw.forEachIndexed { index, el ->
            val o = el as? JsonObject ?: return@forEachIndexed
            val entrySlug = o["slug"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
                ?: return@forEachIndexed
            val number = o["number"]?.jsonPrimitive?.floatOrNull
                ?: entrySlug.replace(Regex("\\D+"), "").toFloatOrNull()
                ?: (index + 1).toFloat()
            val title = o["title"]?.cleanText()
                ?.takeIf { it.isNotEmpty() } ?: "Chapter ${number.toInt()}"
            val createdAt = o["createdAt"]?.jsonPrimitive?.contentOrNull
            entries.add(
                Triple(
                    number.toInt(),
                    Episode(
                        id = "novel/$slug/$entrySlug",
                        name = title,
                        number = number,
                        dateMillis = parseDate(createdAt),
                    ),
                    index,
                ),
            )
        }
        return entries.sortedWith(compareBy({ it.first }, { it.third })).map { it.second }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedEpisodes[entryId]?.let { return it }
        val slug = entryId.removePrefix("novel/").trim('/')
        return chapterList(slug).also { cachedEpisodes[entryId] = it }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.selectFirst("div.ln-prose") ?: return emptyList()
        return paragraphsOf(content)
    }

    // ---- helpers -----------------------------------------------------------

    /** Strip tags, decode entities and collapse whitespace. */
    private fun JsonElement?.cleanText(): String? {
        val p = this?.takeIf { it !is kotlinx.serialization.json.JsonNull } ?: return null
        val raw = runCatching { p.jsonPrimitive.content }.getOrNull() ?: return null
        return Jsoup.parse(raw).text().replace(Regex("\\s+"), " ").trim()
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

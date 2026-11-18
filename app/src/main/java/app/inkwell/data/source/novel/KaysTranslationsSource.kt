package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Kay's Translations (kaystls.site) — port of LNReader's kaystranslations
 * plugin. Listing/search go through the site's admin-ajax actions with the
 * nonce printed on the Browse page; a series page embeds its chapter list as
 * `KaySeriesTOCData`.
 */
class KaysTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "kaystranslations"
    override val name = "Kay's Translations"
    override val baseUrl = "https://kaystls.site"
    override val lang = "en"

    private val ajaxUrl = "https://kaystls.site/wp-admin/admin-ajax.php"
    private var nonce: String? = null
    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cachedEpisodes = HashMap<String, List<Episode>>()

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int) = browsePage(page, latest = false)
    override suspend fun latest(page: Int) = browsePage(page, latest = true)

    private suspend fun browsePage(page: Int, latest: Boolean): PagedResult<Entry> {
        val sort = if (latest) "updated_free" else "popular"
        // The cached nonce may expire: read a fresh one and retry once.
        var html = browse(page, "all", sort, refreshNonce = false)
        if (html == null) html = browse(page, "all", sort, refreshNonce = true)
        html ?: return PagedResult(emptyList(), false)
        val d = Jsoup.parse(html)
        val items = d.select("article.kay-card").mapNotNull { card ->
            val link = card.selectFirst("h3.kay-card-title a") ?: return@mapNotNull null
            val path = toPath(link.attr("href"))
            val nm = link.text().trim()
            if (path.isEmpty() || nm.isEmpty()) return@mapNotNull null
            Entry(id, path, nm, imageOrDefault(card.selectFirst("img")?.attr("src")), ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        // The site's search answers with every match at once and has no pages.
        if (page > 1) return PagedResult(emptyList(), false)
        val q = query.trim()
        if (q.length < 2) return PagedResult(emptyList(), false)
        val root = ajax(
            mapOf(
                "action" to "kay_series_search",
                "q" to q,
            ),
        )
        val hits = (root as? JsonArray) ?: return PagedResult(emptyList(), false)
        val items = hits.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val path = toPath(o["url"]?.jsonPrimitive?.contentOrNull.orEmpty())
            val title = normalize(o["title"]?.jsonPrimitive?.contentOrNull.orEmpty())
            if (path.isEmpty() || title.isEmpty()) return@mapNotNull null
            Entry(id, path, title, imageOrDefault(o["image"]?.jsonPrimitive?.contentOrNull), ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val name = normalize(d.selectFirst(".wp-block-column h1.wp-block-heading")?.text().orEmpty())
            .ifBlank { titleFromDocument(d).ifBlank { "Untitled" } }
        val cover = imageOrDefault(d.selectFirst(".wp-block-cover__image-background")?.attr("src"))
        val synopsis = readSynopsis(d)

        val html = fetch(absPath(entryId))
        val toc = readToc(html)
        val status = toc?.statusKey?.let { toStatus(it) }

        // Early-access chapters are listed on the page but need a sign-in.
        val locked = d.selectFirst("input.lockedChapters")?.attr("value").orEmpty()
            .split(',').map { toPath(it) }.filter { it.isNotEmpty() }.toSet()

        val episodes = mutableListOf<Episode>()
        toc?.chapters.orEmpty().forEach { item ->
            val path = toPath(item.href)
            if (path.isEmpty() || path in locked) return@forEach
            val numbered = Regex("^Chapter\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(item.title)
            episodes.add(
                Episode(
                    id = path,
                    name = normalize(item.title),
                    number = numbered?.groupValues?.get(1)?.toFloatOrNull()
                        ?: (episodes.size + 1).toFloat(),
                    dateMillis = item.ts?.let { it * 1000 },
                ),
            )
        }
        return EntryDetails(
            entry = Entry(id, entryId, name, cover, ContentType.NOVEL),
            description = synopsis.ifBlank { null },
            authors = emptyList(),
            genres = emptyList(),
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
        // The story text is the run of paragraphs under the chapter heading;
        // the next heading ("TOC | Next chapter") and donation blocks follow.
        val firstP = d.selectFirst(".wp-block-column > h3.wp-block-heading + p.wp-block-paragraph")
            ?: return emptyList()
        val column = firstP.parent() ?: return emptyList()
        val parts = mutableListOf<String>()
        val children = column.children()
        for (i in 1 until children.size) {
            val node = children[i]
            if (node.tagName() == "h3") break
            if (node.tagName() == "p" && node.hasClass("wp-block-paragraph")) {
                val text = node.text().trim()
                if (text.isNotEmpty() || node.select("img").isNotEmpty()) parts.add(text)
            }
        }
        return parts.filter { it.isNotEmpty() }
    }

    // ---- ajax ---------------------------------------------------------------

    /**
     * Run an admin-ajax action. Resolves to the response JSON, or to `null`
     * when the site rejected the request (e.g. an expired nonce).
     */
    private suspend fun ajax(form: Map<String, String>): JsonElement? =
        withContext(Dispatchers.IO) {
            val fb = FormBody.Builder()
            form.forEach { (k, v) -> fb.add(k, v) }
            val req = request(ajaxUrl)
                .newBuilder()
                .header("X-Requested-With", "XMLHttpRequest")
                .post(fb.build())
                .build()
            runCatching { jsonParser.parseToJsonElement(client.newCall(req).execute().use { r -> r.body?.string().orEmpty() }) }
                .getOrNull()
        }

    /** Run the Browse page's `kay_browse_query` action; `null` when rejected. */
    private suspend fun browse(pageNo: Int, status: String, sort: String, refreshNonce: Boolean): String? {
        if (refreshNonce || nonce == null) nonce = fetchNonce()
        val data = ajax(
            mapOf(
                "action" to "kay_browse_query",
                "status" to status,
                "sort" to sort,
                "page" to pageNo.toString(),
                "_wpnonce" to nonce.orEmpty(),
            ),
        ) as? JsonObject ?: return null
        return data["html"]?.jsonPrimitive?.contentOrNull
    }

    private suspend fun fetchNonce(): String? {
        val d = doc(absPath("browse") + "/")
        return d.selectFirst("#kay-browse-grid")?.attr("data-nonce")?.takeIf { it.isNotEmpty() }
    }

    // ---- helpers -----------------------------------------------------------

    /** The synopsis is the paragraphs between its heading and the TOC. */
    private fun readSynopsis(d: Document): String {
        val heading = d.select(".wp-block-column h4.wp-block-heading")
            .firstOrNull { Regex("synopsis", RegexOption.IGNORE_CASE).containsMatchIn(it.text()) }
            ?: return ""
        val paragraphs = mutableListOf<String>()
        var node: org.jsoup.nodes.Element? = heading.nextElementSibling()
        while (node != null && !Regex("^h\\d$").matches(node.tagName())) {
            if (node.tagName() == "p" && node.hasClass("wp-block-paragraph")) {
                val text = normalize(node.text())
                // The closing paragraph asks readers to rate the series.
                if (text.isNotEmpty() && !Regex("novel\\s*updates", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                    paragraphs.add(text)
                }
            }
            node = node.nextElementSibling()
        }
        return paragraphs.joinToString("\n\n")
    }

    /** Read the `KaySeriesTOCData = {...};` object out of the page's scripts. */
    private fun readToc(html: String): TocData? {
        val marker = html.indexOf("KaySeriesTOCData")
        if (marker == -1) return null
        val eq = html.indexOf('=', marker)
        val start = html.indexOf('{', eq)
        if (start == -1) return null
        var depth = 0
        var inString = false
        var i = start
        while (i < html.length) {
            val c = html[i]
            if (inString) {
                if (c == '\\') i++
                else if (c == '"') inString = false
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            val parsed = runCatching {
                                jsonParser.parseToJsonElement(html.substring(start, i + 1))
                            }.getOrNull() as? JsonObject ?: return null
                            return TocData(
                                chapters = (parsed["chapters"] as? JsonArray).orEmpty().mapNotNull { el ->
                                    val o = el as? JsonObject ?: return@mapNotNull null
                                    TocChapter(
                                        href = o["href"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                                        ts = o["ts"]?.jsonPrimitive?.longOrNull,
                                        title = o["title"]?.jsonPrimitive?.contentOrNull ?: "",
                                    )
                                },
                                statusKey = parsed["statusKey"]?.jsonPrimitive?.contentOrNull,
                            )
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private data class TocChapter(val href: String, val ts: Long?, val title: String)
    private data class TocData(val chapters: List<TocChapter>, val statusKey: String?)

    private fun toStatus(key: String?): String? = when (key) {
        "ongoing" -> "Ongoing"
        "completed" -> "Completed"
        "hiatus" -> "On Hiatus"
        "dropped" -> "Cancelled"
        else -> null
    }

    private fun titleFromDocument(d: Document): String =
        normalize(d.title().split(Regex("[–|-]\\s*Kay")).first())

    /** The site fills missing covers with a shared "no-image" placeholder. */
    private fun imageOrDefault(src: String?): String? {
        if (src == null || Regex("no-image", RegexOption.IGNORE_CASE).containsMatchIn(src)) return null
        return src
    }

    /** Reduce an address on this site to the path stored for novels/chapters. */
    private fun toPath(href: String?): String {
        href ?: return ""
        return runCatching {
            val url = baseUrl.toHttpUrl().resolve(href.trim()) ?: return ""
            if (url.host.removePrefix("www.") != "kaystls.site") "" else url.encodedPath.trimStart('/')
        }.getOrDefault("")
    }

    private fun normalize(text: String): String = text
        .replace('\u3000', ' ')
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex(" *\\n *"), "\n")
        .trim()
}

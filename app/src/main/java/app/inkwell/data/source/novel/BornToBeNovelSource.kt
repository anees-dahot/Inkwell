package app.inkwell.data.source.novel

import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperNovelSource
import app.inkwell.data.source.paragraphsOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode

/**
 * BornToBeNovel (borntobenovel.com). A custom server-rendered site: the
 * catalog is the single `/library/` page, ordering comes from
 * `/static/sliders.json` (`popular` / `recent` slug lists), the chapter list
 * is the inline `window.chapters = {...}` object on the novel page, and the
 * chapter text is a base64 string in `const contentData` rendered as light
 * markdown.
 */
class BornToBeNovelSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "borntobenovel"
    override val name = "BornToBeNovel"
    override val baseUrl = "https://borntobenovel.com"
    override val lang = "en"

    override val userAgent =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    override val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer" to "$baseUrl/",
    )

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject?.str(key: String): String? = this?.get(key).str()

    private val premiumNotice = "This chapter is available only with premium"

    private fun relPath(href: String): String {
        val a = abs(href) ?: return href.trim('/')
        return a.removePrefix(baseUrl).trim('/')
    }

    private fun resolveAsset(src: String): String = abs(src) ?: src

    private data class CatalogItem(val entry: Entry, val search: String)

    private suspend fun fetchCatalog(): List<CatalogItem> {
        val d = doc(absPath("library/"))
        return d.select("a.novel-card").mapNotNull { card ->
            val path = relPath(card.attr("href"))
            val name = card.selectFirst(".novel-title")?.text()?.trim().orEmpty()
            if (path.isEmpty() || name.isEmpty()) return@mapNotNull null
            val src = card.selectFirst("img.cover-icon")?.attr("src")
            CatalogItem(
                Entry(id, path, name, src?.let { resolveAsset(it) }, ContentType.NOVEL),
                card.attr("data-search-text").lowercase(),
            )
        }
    }

    private suspend fun fetchRanking(list: String): List<String> = runCatching {
        val root = json(absPath("static/sliders.json"))
        val arr = (root as? JsonObject)?.get(list) as? JsonArray ?: JsonArray(emptyList())
        arr.mapNotNull { el -> (el as? JsonObject)?.str("id")?.let { "novel/$it" } }
    }.getOrDefault(emptyList())

    private suspend fun listPage(list: String): PagedResult<Entry> {
        val catalog = fetchCatalog()
        val ranked = fetchRanking(list)
        val items = catalog
            .mapIndexed { idx, it -> Triple(ranked.indexOf(it.entry.id).takeIf { r -> r >= 0 } ?: ranked.size, idx, it) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .map { it.third.entry }
        return PagedResult(items, false)
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return listPage("popular")
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return listPage("recent")
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val term = query.trim().lowercase()
        val items = fetchCatalog().filter { it.search.contains(term) }.map { it.entry }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private fun parseChapters(html: String): List<Episode> {
        val marker = "window.chapters = "
        val start = html.indexOf(marker)
        if (start < 0) return emptyList()
        val begin = html.indexOf('{', start)
        val end = html.indexOf("};", begin)
        if (begin < 0 || end < 0) return emptyList()
        val obj = runCatching {
            jsonParser.parseToJsonElement(html.substring(begin, end + 1)).jsonObject
        }.getOrNull() ?: return emptyList()
        return obj.keys.mapNotNull { it.toIntOrNull() }.sorted().mapNotNull { n ->
            val item = obj[n.toString()] as? JsonObject ?: return@mapNotNull null
            val url = item.str("url")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val date = item.str("date").orEmpty().split('.')
            val rel = if (date.size >= 3) "${date[2]}-${date[1]}-${date[0]}" else null
            Episode(
                id = url.trim('/'),
                name = "Chapter $n" + if (item.str("is_free") == "false") " (Premium)" else "",
                number = n.toFloat(),
                dateMillis = parseDate(rel, "yyyy-M-d"),
            )
        }
    }

    override suspend fun details(entryId: String): EntryDetails {
        val path = if (entryId.endsWith("/")) entryId.trimEnd('/') else entryId.trim('/')
        val html = fetch(absPath(path) + "/")
        if (html.isBlank()) throw IllegalStateException("The site returned an empty page for $path")
        val d = Jsoup.parse(html, absPath(path) + "/")
        val titleEl = d.selectFirst("h1.desktop-title")?.clone()
        titleEl?.select(".alt-names")?.remove()
        val title = titleEl?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled"
        val cover = d.selectFirst("img.desktop-cover")?.attr("src")?.let { resolveAsset(it) }
        val tags = d.select("#desktopGenres .genre-tag")
        val statusClass = tags.firstOrNull { it.hasClass("status-tag") }?.attr("class").orEmpty()
        val status = when {
            statusClass.contains("status-completed") -> "Completed"
            statusClass.contains("status-ongoing") -> "Ongoing"
            statusClass.contains("status-dropped") -> "Dropped"
            else -> null
        }
        val genres = tags.filter { el ->
            !el.hasClass("rating-tag") && !el.hasClass("status-tag") &&
                !el.hasClass("chapters-tag") && !el.hasClass("genre-more-button")
        }.map { it.text().trim() }.filter { it.isNotEmpty() }
        val summaryEl = d.selectFirst(".desktop-description .novel-description")
        summaryEl?.select("br")?.forEach { it.replaceWith(TextNode("\n")) }
        val summary = summaryEl?.wholeText()?.trim()?.takeIf { it.isNotEmpty() }
        return EntryDetails(
            entry = Entry(id, path, title, cover, ContentType.NOVEL),
            description = summary,
            authors = emptyList(),
            genres = genres,
            status = status,
        ).also { cachedChapters[path] = parseChapters(html) }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        val key = entryId.trim('/')
        cachedChapters[key]?.let { return it }
        return parseChapters(fetch(absPath(key) + "/")).also { cachedChapters[key] = it }
    }

    // ---- chapter -----------------------------------------------------------

    private fun decodeBase64Utf8(b64: String): String = runCatching {
        String(java.util.Base64.getDecoder().decode(b64), Charsets.UTF_8)
    }.getOrElse {
        String(java.util.Base64.getDecoder().decode(b64), Charsets.ISO_8859_1)
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun formatInline(text: String): String = escapeHtml(text)
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "<strong>$1</strong>")
        .replace(Regex("(^|[^*])\\*(?!\\*)([^*]+?)\\*(?!\\*)"), "$1<em>$2</em>")

    private fun markdownToHtml(text: String): String = text.split('\n')
        .mapNotNull { line ->
            val t = line.replace("\r", "").trim()
            when {
                t.isEmpty() -> null
                Regex("^\\*\\s*\\*\\s*\\*$").matches(t) -> "<hr>"
                else -> "<p>" + formatInline(t.removePrefix("~").removeSuffix("~").trim()) + "</p>"
            }
        }.joinToString("\n")

    override suspend fun chapterText(episodeId: String): List<String> {
        val html = fetch(absPath(episodeId))
        val b64 = Regex("const contentData = '([^']*)'").find(html)?.groupValues?.get(1)
            ?: return emptyList()
        val text = decodeBase64Utf8(b64)
        if (text.startsWith(premiumNotice)) return emptyList()
        val body = Jsoup.parseBodyFragment(markdownToHtml(text)).body()
        return paragraphsOf(body)
    }
}

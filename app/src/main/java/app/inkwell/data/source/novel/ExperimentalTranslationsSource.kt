package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Experimental Translations (experimentaltranslations.com) — a WordPress.com
 * blog read through its public REST API
 * (`public-api.wordpress.com/wp/v2/sites/experimentaltranslations.com`).
 */
class ExperimentalTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "experimentaltranslations"
    override val name = "Experimental Translations"
    override val baseUrl = "https://experimentaltranslations.com"
    override val lang = "en"

    private val api = "https://public-api.wordpress.com/wp/v2/sites/experimentaltranslations.com"

    /** Pages of the site menu that are not novels. */
    private val nonNovelSlugs = listOf("ongoing", "finished", "oneshots", "about", "contact")

    /** Matches `/YYYY/MM/DD/slug/`, the permalink form of every chapter post. */
    private val postPath = Regex("^/(\\d{4})/(\\d{2})/(\\d{2})/([^/]+)/$")

    private fun normalizeText(text: String) = text.replace(Regex("\\s+"), " ").trim()

    /** Site-relative path with a trailing slash, e.g. `/2020/12/25/slug/`. */
    private fun toPath(href: String): String {
        val raw = href.trim()
        val sameSite = Regex(
            "^https?://(?:www\\.)?experimentaltranslations\\.(?:com|wordpress\\.com)(/.*)?$",
            RegexOption.IGNORE_CASE,
        ).find(raw)
        val clean = (sameSite?.groupValues?.get(1) ?: raw).split(Regex("[?#]"))[0]
        val path = if (clean.startsWith("/")) clean else "/$clean"
        return if (path.endsWith("/")) path else "$path/"
    }

    private fun isSiteUrl(url: String): Boolean = Regex(
        "^https?://(?:www\\.)?experimentaltranslations\\.(?:com|wordpress\\.com)(?:[/?#]|$)",
        RegexOption.IGNORE_CASE,
    ).containsMatchIn(url)

    /** Site-relative path for a link on this site, or "" for any other host. */
    private fun sitePath(href: String): String {
        val raw = href.trim()
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw) && !isSiteUrl(raw)) return ""
        return toPath(raw)
    }

    /** ISO date taken from a dated chapter permalink. */
    private fun dateOfPath(path: String): Long? {
        val post = postPath.find(path) ?: return null
        return parseDate(
            "${post.groupValues[1]}-${post.groupValues[2]}-${post.groupValues[3]}T00:00:00",
            "yyyy-MM-dd'T'HH:mm:ss",
        )
    }

    /** Lowercase with typographic quotes flattened so "Parent's" matches. */
    private fun searchKey(text: String): String = normalizeText(text)
        .lowercase()
        .replace("‘", "'")
        .replace("’", "'")
        .replace("“", "\"")
        .replace("”", "\"")

    private fun decode(html: String): String = normalizeText(Jsoup.parseBodyFragment(html).text())

    /** Fetch a REST collection; WordPress answers 400/404 for a missing page. */
    private suspend fun fetchList(route: String): JsonArray = withContext(Dispatchers.IO) {
        val res = client.newCall(request(api + route)).execute()
        res.use { r ->
            if (r.code == 400 || r.code == 404) return@withContext JsonArray(emptyList())
            check(r.isSuccessful) { "$name HTTP ${r.code}" }
            json.parseToJsonElement(r.body?.string().orEmpty()) as? JsonArray ?: JsonArray(emptyList())
        }
    }

    private data class TocLink(val title: String, val path: String)

    private fun entryTitle(el: kotlinx.serialization.json.JsonElement): String =
        runCatching {
            el.jsonObject["title"]!!.jsonObject["rendered"]!!.jsonPrimitive.content
        }.getOrDefault("")

    private fun entryField(el: kotlinx.serialization.json.JsonElement, field: String): String =
        runCatching {
            el.jsonObject[field]!!.jsonPrimitive.content
        }.getOrDefault("")

    private fun entryContent(el: kotlinx.serialization.json.JsonElement): String =
        runCatching {
            el.jsonObject["content"]!!.jsonObject["rendered"]!!.jsonPrimitive.content
        }.getOrDefault("")

    /** Series linked from the `Finished` and `Oneshots` pages. */
    private suspend fun seriesLinks(): List<TocLink> {
        val pages = fetchList("/pages?slug=finished,oneshots&_fields=id,slug,link,title,content")
        val links = mutableListOf<TocLink>()
        for (page in pages) {
            val root = Jsoup.parseBodyFragment(entryContent(page)).body()
            for (a in root.select("a")) {
                val path = sitePath(a.attr("href"))
                val title = normalizeText(a.text())
                if (path.isNotEmpty() && title.isNotEmpty()) links.add(TocLink(title, path))
            }
        }
        return links
    }

    private suspend fun finishedPaths(): Set<String> {
        val pages = fetchList("/pages?slug=finished&_fields=id,slug,link,title,content")
        val paths = mutableSetOf<String>()
        if (pages.isEmpty()) return paths
        val root = Jsoup.parseBodyFragment(entryContent(pages[0])).body()
        for (a in root.select("a")) {
            val path = sitePath(a.attr("href"))
            if (path.isNotEmpty()) paths.add(path)
        }
        return paths
    }

    /** Chapter links of a table of contents, in site order. */
    private fun parseToc(html: String): List<TocLink> {
        val root = Jsoup.parseBodyFragment(html).body()
        val links = mutableListOf<TocLink>()
        val seen = mutableSetOf<String>()
        for (a in root.select("a")) {
            val path = sitePath(a.attr("href"))
            val title = normalizeText(a.text())
            val post = postPath.find(path)
            // Image galleries are attachment pages or "…-images" posts, not text.
            if (post == null || title.isEmpty() || post.groupValues[4].endsWith("-images") || !seen.add(path)) {
                continue
            }
            links.add(TocLink(title, path))
        }
        return links
    }

    /** Every novel: non-menu pages, then series linked from Finished/Oneshots. */
    private suspend fun listNovels(searchTerm: String): List<Entry> {
        val pages = fetchList("/pages?per_page=100&orderby=date&order=desc&_fields=id,slug,link,title")
        val novels = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        fun add(name: String, path: String) {
            if (name.isNotEmpty() && seen.add(path)) novels.add(Entry(id, path.trimStart('/'), name, null, ContentType.NOVEL))
        }
        for (page in pages) {
            val slug = entryField(page, "slug")
            if (slug !in nonNovelSlugs && !slug.endsWith("-images")) {
                add(decode(entryTitle(page)), toPath(entryField(page, "link")))
            }
        }
        for (link in seriesLinks()) add(link.title, link.path)
        val needle = searchKey(searchTerm)
        return novels.filter { searchKey(it.title).contains(needle) }
    }

    /** The page or post that lives at `path`. */
    private suspend fun findEntry(path: String, kind: String? = null): kotlinx.serialization.json.JsonElement? {
        val wanted = toPath(path)
        val slug = wanted.split('/').filter { it.isNotEmpty() }.lastOrNull().orEmpty()
        val fields = "&_fields=id,slug,link,date,title,content"
        val kinds = if (kind != null) listOf(kind) else listOf("pages", "posts")
        for (type in kinds) {
            val found = fetchList("/$type?slug=$slug$fields")
            val match = found.firstOrNull { toPath(entryField(it, "link")) == wanted } ?: found.firstOrNull()
            if (match != null) return match
        }
        return null
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(listNovels(""), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(listNovels(query), false)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val wanted = "/" + entryId.trim('/')
        val entry = findEntry(wanted)
            ?: throw IllegalStateException("$name: no novel at $wanted")
        val path = toPath(entryField(entry, "link"))
        val name = decode(entryTitle(entry))
        val root = Jsoup.parseBodyFragment(entryContent(entry)).body()

        // The series header: author line(s), then the synopsis, up to the TOC.
        val synopsis = mutableListOf<String>()
        var author: String? = null
        var header = true
        for (el in root.children()) {
            if (!header) break
            if (el.tagName().matches(Regex("^h[1-6]$"))) {
                if (Regex("table of contents|contents", RegexOption.IGNORE_CASE).containsMatchIn(el.text())) {
                    header = false
                }
                continue
            }
            if (el.tagName() != "p" && el.tagName() != "blockquote") continue
            el.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
            val text = el.wholeText().trim()
            if (text.isEmpty() || Regex("^[―\\-–—\\s]+$").matches(text)) continue
            val authorMatch = Regex("^By:\\s*([^\\n]+)", RegexOption.IGNORE_CASE).find(text)
            if (authorMatch != null) {
                author = normalizeText(authorMatch.groupValues[1])
                continue
            }
            // The raw-source link paragraph is not part of the synopsis.
            if (text.replace(Regex("https?://\\S+"), "").isBlank()) continue
            synopsis.add(text.replace(Regex("[ \\t]*\\n[ \\t]*"), "\n"))
        }

        val finished = finishedPaths()
        val status = if (path in finished) "Completed" else null

        val chapters = parseToc(entryContent(entry)).mapIndexed { index, link ->
            Episode(id = link.path.trimStart('/'), name = link.title, number = (index + 1).toFloat(), dateMillis = dateOfPath(link.path))
        }.ifEmpty {
            // A oneshot has no TOC: the post itself is its only chapter.
            if (postPath.matches(path)) {
                listOf(Episode(path.trimStart('/'), name, 1f, parseDate(entryField(entry, "date"), "yyyy-MM-dd'T'HH:mm:ss")))
            } else emptyList()
        }

        return EntryDetails(
            entry = Entry(id, entryId, name, null, ContentType.NOVEL),
            description = synopsis.joinToString("\n\n").ifBlank { null }.takeIf { chapters.isNotEmpty() },
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = emptyList(),
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val entry = findEntry("/" + episodeId.trim('/'), kind = "posts")
            ?: return emptyList()
        val html = entryContent(entry)
        if (html.isBlank()) return emptyList()
        val root = Jsoup.parseBodyFragment(html).body()
        root.select("script, style, .sharedaddy, .jp-relatedposts").remove()
        // Posts open with a bare link to the raw source; it is not story text.
        val first = root.children().firstOrNull()
        if (first?.tagName() == "p" && first.select("a").isNotEmpty() &&
            first.text().replace(Regex("https?://\\S+"), "").isBlank()
        ) first.remove()
        return paragraphsOf(root)
    }
}

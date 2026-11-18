package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class LupusSenseiSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "lupussensei"
    override val name = "Lupus-sensei Translations"
    override val baseUrl = "https://www.lupus-sensei.com"
    override val lang = "en"

    private data class CatNovel(val path: String, val novelName: String)

    // Chapters of the oldest series live on the translator's earlier blog.
    private val chapterHost =
        Regex("^(?:(?:www\\.)?lupus-sensei\\.com|isekaimynoghra\\.blogspot\\.com)$", RegexOption.IGNORE_CASE)
    private val siteHost = Regex("^(?:www\\.)?lupus-sensei\\.com$", RegexOption.IGNORE_CASE)
    // Blogger post URLs look like /2021/02/slug.html.
    private val postPath = Regex("^/\\d{4}/\\d{2}/[^/]+\\.html$")
    // Entries in a chapter list that are not chapters of the novel.
    private val notChapter = Regex("^(?:spoilers?\\b|synopsis$|.*\\bchapters$)", RegexOption.IGNORE_CASE)
    private val keepAttributes = setOf("src", "href", "alt", "width", "height")
    private val chapterGroup = Regex("^\\s*(Chapter\\s+\\d+)\\b", RegexOption.IGNORE_CASE)

    private fun cleanText(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    // Allows http(s) URLs and scheme-less (relative) ones; anything else, such
    // as `javascript:` or `data:`, is rejected.
    private fun isSafeUrl(value: String): Boolean =
        Regex("^\\s*https?:", RegexOption.IGNORE_CASE).containsMatchIn(value) ||
            !value.split(Regex("[/?#]")).first().contains(':')

    /** Site-relative path for the blog's own posts, an absolute URL for posts hosted on the sibling blog. */
    private fun postPathOf(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val url = baseUrl.toHttpUrlOrNull()?.resolve(href.trim()) ?: return null
        if (!chapterHost.matches(url.host) || !postPath.matches(url.encodedPath)) return null
        return if (siteHost.matches(url.host)) url.encodedPath else "https://${url.host}${url.encodedPath}"
    }

    /**
     * The novels are the entries of the site's "Novels" menu: a title entry
     * followed by that novel's "Chapter List" page.
     */
    private suspend fun catalogue(): List<CatNovel> {
        val d = doc(baseUrl)
        val novels = mutableListOf<CatNovel>()
        var title: String? = null
        for (a in d.select("#menu li a")) {
            val href = a.attr("href")
            val text = cleanText(a.text())
            if (href == "#") {
                title = if (Regex("^_(?!_)").containsMatchIn(text)) text.removePrefix("_").trim() else null
                continue
            }
            if (title == null || !Regex("^__\\s*chapter", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                title = null
                continue
            }
            val path = baseUrl.toHttpUrlOrNull()?.resolve(href)?.let { url ->
                if (siteHost.matches(url.host)) url.encodedPath else null
            }
            if (path != null && novels.none { it.path == path }) {
                novels += CatNovel(path, title)
            }
            title = null
        }
        return novels
    }

    /**
     * The menu has no images, so covers come from the blog's label feed: one
     * request lists every chapter-list post with its thumbnail.
     */
    private suspend fun withCovers(novels: List<CatNovel>): List<Entry> {
        val covers = HashMap<String, String>()
        runCatching {
            val root = json("$baseUrl/feeds/posts/default/-/Chapterlist?alt=json&max-results=50").jsonObject
            for (entry in root["feed"]?.jsonObject?.get("entry")?.jsonArray ?: emptyList()) {
                val o = entry.jsonObject
                val href = (o["link"]?.jsonArray ?: emptyList())
                    .firstOrNull { it.jsonObject["rel"]?.jsonPrimitive?.contentOrNull == "alternate" }
                    ?.jsonObject?.get("href")?.jsonPrimitive?.contentOrNull
                val thumbnail = o["media\$thumbnail"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                if (href.isNullOrBlank() || thumbnail.isNullOrBlank()) continue
                val path = baseUrl.toHttpUrlOrNull()?.resolve(href)?.encodedPath ?: continue
                covers[path] = Regex("/s\\d+(-w\\d+-h\\d+)?(-c)?/").replace(thumbnail, "/s640/")
            }
        }
        return novels.map {
            Entry(id, it.path.trimStart('/'), it.novelName, abs(covers[it.path]), ContentType.NOVEL)
        }
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(withCovers(catalogue()), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || page > 1) return PagedResult(emptyList(), false)
        val matches = catalogue().filter { novel -> tokens.all { novel.novelName.lowercase().contains(it) } }
        return PagedResult(if (matches.isEmpty()) emptyList() else withCovers(matches), false)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val body = d.selectFirst(".post-body") ?: org.jsoup.Jsoup.parse("<div class='post-body'></div>")
        body.select("script, style").remove()

        val listed = runCatching { catalogue().firstOrNull { it.path == toPath(entryId) } }.getOrNull()
        val pageTitle = cleanText(d.selectFirst("h3.post-title, h1.post-title")?.text() ?: "")
            .replace(Regex("\\s*[-–]?\\s*Chapter List$", RegexOption.IGNORE_CASE), "").trim()

        val chapters = mutableListOf<Episode>()
        val seen = HashSet<String>()
        var group = ""
        fun walk(node: org.jsoup.nodes.Node) {
            when (node) {
                is TextNode -> {
                    chapterGroup.find(cleanText(node.wholeText))?.let { m -> group = m.groupValues[1] }
                }
                is Element -> {
                    if (node.tagName() == "a") {
                        val path = postPathOf(node.attr("href"))
                        val name = cleanText(node.text())
                        if (path != null && name.isNotEmpty() && !notChapter.containsMatchIn(name) && seen.add(path)) {
                            val chapterName =
                                if (group.isNotBlank() && Regex("^ep\\b", RegexOption.IGNORE_CASE).containsMatchIn(name))
                                    "$group - $name" else name
                            chapters += Episode(path.trimStart('/'), chapterName, null, null)
                        }
                        return
                    }
                    node.childNodes().forEach { walk(it) }
                }
            }
        }
        body.childNodes().forEach { walk(it) }

        val cover = body.select("img[src]").map { it.attr("src") }
            .firstOrNull { Regex("^https?://").containsMatchIn(it) }

        return EntryDetails(
            entry = Entry(id, entryId.trimStart('/'), listed?.novelName ?: pageTitle.ifBlank { "Untitled" }, abs(cover), ContentType.NOVEL),
            description = null,
            authors = emptyList(),
            genres = emptyList(),
            status = null,
        ).also { cachedChapters[entryId] = chapters }
    }

    private fun toPath(episodeId: String): String =
        if (episodeId.startsWith("http")) {
            baseUrl.toHttpUrlOrNull()?.resolve(episodeId)?.encodedPath ?: episodeId
        } else {
            "/" + episodeId.trimStart('/')
        }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId] ?: emptyList()
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val body = d.selectFirst(".post-body") ?: return emptyList()
        body.select("script, style, noscript, iframe, object, embed, form, svg, base, meta, .adsbygoogle").remove()
        // Word-export leftovers (conditional comments) sit inside the post.
        for (el in body.getAllElements()) {
            for (node in el.childNodes().toList()) {
                if (node is Comment) node.remove()
            }
        }
        // Pasted Word markup carries megabytes of inline styling: keep only
        // the attributes that matter for reading.
        body.select("span, font").forEach { it.unwrap() }
        for (el in body.getAllElements()) {
            for (attr in el.attributes().toList()) {
                val key = attr.key
                val value = attr.value
                if (key !in keepAttributes ||
                    ((key == "href" || key == "src") && !isSafeUrl(value))
                ) el.removeAttr(key)
            }
        }
        body.select("p, div").forEach { el ->
            if (el.select("img, hr").isEmpty() &&
                cleanText(el.text()).isEmpty() &&
                el.select("p, div").isEmpty()
            ) el.remove()
        }
        // Posts often open with a few blank lines.
        while (body.childNodeSize() > 0) {
            val first = body.childNode(0)
            val blank = (first is Element && first.tagName() == "br") ||
                (first is TextNode && cleanText(first.wholeText).isEmpty())
            if (!blank) break
            first.remove()
        }
        if (cleanText(body.text()).length < 100) return emptyList()
        return paragraphsOf(body)
    }
}

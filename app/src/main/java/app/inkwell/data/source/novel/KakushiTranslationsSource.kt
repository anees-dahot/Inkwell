package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Kakushi Translations (kakushitranslations.wordpress.com) — port of
 * LNReader's kakushitranslations plugin. Every project is a WordPress page
 * below "Ongoing Projects" or "Dropped Projects"; chapters are child pages.
 */
class KakushiTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "kakushitranslations"
    override val name = "Kakushi Translations"
    override val baseUrl = "https://kakushitranslations.wordpress.com"
    override val lang = "en"

    /** A project page: `/ongoing-projects/<slug>/` or `/dropped-projects/<slug>/`. */
    private val novelPathRe = Regex("^/(ongoing|dropped)-projects/[^/]+/$")

    /** Previous / Table Of Contents / Next navigation words under a chapter. */
    private val navWords = Regex("(prev|previous|next|table of contents|toc|->|<-)", RegexOption.IGNORE_CASE)

    private data class NovelPage(val path: String, val name: String, val cover: String?)

    private var catalogueCache: List<NovelPage>? = null
    private val cachedEpisodes = HashMap<String, List<Episode>>()

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val items = runCatching { catalogue() }.getOrDefault(emptyList()).map { toEntry(it) }
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val term = query.trim().lowercase()
        val items = runCatching { catalogue() }.getOrDefault(emptyList())
            .filter { it.name.lowercase().contains(term) }
            .map { toEntry(it) }
        return PagedResult(items, false)
    }

    private fun toEntry(novel: NovelPage) =
        Entry(id, idOf(novel.path), novel.name, novel.cover, ContentType.NOVEL)

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val path = sitePath(entryId)
        val d = loadNovelPage(path)
        val page = toNovelPage(path, d)
        val credits = novelCredits(d)
        val summary = novelSummary(d)
        val chapters = novelChapters(d, path)
        return EntryDetails(
            entry = Entry(id, entryId, page.name, page.cover, ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = listOfNotNull(credits.first.takeIf { it.isNotBlank() }),
            genres = emptyList(),
            status = if (path.startsWith("/dropped-projects/")) "Cancelled" else "Ongoing",
        ).also { cachedEpisodes[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedEpisodes[entryId]?.let { return it }
        val path = sitePath(entryId)
        val d = loadNovelPage(path)
        return novelChapters(d, path).also { cachedEpisodes[entryId] = it }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.selectFirst(".entry-content") ?: return emptyList()

        content.select("script, style, .sharedaddy, .jp-relatedposts, [id^=atatags], #wordads-inline-marker, .wp-block-spacer").remove()

        // Drop the Prev / Table Of Contents / Next paragraph, which holds
        // nothing but those words and their links, and the separator above it.
        val navs = content.select("p").filter { el ->
            el.select("a[href]").isNotEmpty() &&
                Regex("\\b(prev|previous|next)\\b", RegexOption.IGNORE_CASE).containsMatchIn(el.text()) &&
                el.text().replace(navWords, "").replace(Regex("[|\\s]"), "").isEmpty()
        }
        navs.forEach { el ->
            el.previousElementSibling()?.takeIf { it.tagName() == "hr" }?.remove()
            el.remove()
        }
        return paragraphsOf(content)
    }

    // ---- helpers -----------------------------------------------------------

    /** Projects are the menu pages that link to at least one chapter page. */
    private suspend fun catalogue(): List<NovelPage> {
        catalogueCache?.let { return it }
        val d = doc(baseUrl)
        val paths = mutableListOf<String>()
        d.select("li.menu-item > a").forEach { a ->
            val path = toPath(a.attr("href"))
            if (novelPathRe.matches(path) && !paths.contains(path)) paths.add(path)
        }
        val pages = mutableListOf<NovelPage>()
        for (path in paths) {
            val page = loadNovelPage(path)
            if (novelChapters(page, path).isNotEmpty()) pages.add(toNovelPage(path, page))
        }
        catalogueCache = pages
        return pages
    }

    private suspend fun loadNovelPage(path: String): Document {
        val d = doc(absPath(path))
        d.select(".jp-relatedposts, .sharedaddy, script, style").remove()
        return d
    }

    private fun toNovelPage(path: String, d: Document): NovelPage {
        val name = normalize(d.selectFirst("h1.entry-title")?.text().orEmpty()).ifBlank { "Untitled" }
        // The first image inside the page is the volume cover.
        val image = d.selectFirst(".entry-content")?.select("img[src]")?.firstOrNull()?.attr("src")
        return NovelPage(path, name, abs(image))
    }

    /** Chapter links in page order, labelled with their "Volume NN" heading. */
    private fun novelChapters(d: Document, novelPath: String): List<Episode> {
        val content = d.selectFirst(".entry-content") ?: return emptyList()
        val chapters = mutableListOf<Episode>()
        val seen = HashSet<String>()
        var volume = ""

        content.select("[id^=vol], li > a[href]").forEach { el ->
            if (el.tagName() != "a") {
                volume = normalize(el.text())
                return@forEach
            }
            val path = toPath(el.attr("href"))
            if (path == novelPath || !path.startsWith(novelPath) ||
                !path.startsWith("/") || !seen.add(path)
            ) {
                return@forEach
            }
            val label = normalize(el.text())
            val name = when {
                volume.isNotEmpty() && label.isNotEmpty() -> "$volume - $label"
                else -> label.ifEmpty { path }
            }
            chapters.add(
                Episode(
                    id = idOf(path),
                    name = name,
                    number = (chapters.size + 1).toFloat(),
                    dateMillis = null,
                ),
            )
        }
        return chapters
    }

    /** The "Author: ...<br>Illustrator: ..." line below the cover. */
    private fun novelCredits(d: Document): Pair<String, String> {
        var author = ""
        var artist = ""
        val authorRe = Regex("^(author|illustrator)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        d.selectFirst(".entry-content")?.select("p")?.forEach { element ->
            element.select("br").forEach { br ->
                br.replaceWith(TextNode("\n"))
            }
            for (line in pLines(element)) {
                val m = authorRe.find(normalize(line)) ?: continue
                if (m.groupValues[1].startsWith("author", true)) author = m.groupValues[2]
                else artist = m.groupValues[2]
            }
        }
        return Pair(author, artist)
    }

    /** Text of a paragraph with <br> kept as newlines (Jsoup flattens them). */
    private fun pLines(p: Element): List<String> {
        val sb = StringBuilder()
        fun walk(node: org.jsoup.nodes.Node) {
            when {
                node is TextNode -> sb.append(node.text())
                node is Element && node.tagName() == "br" -> sb.append('\n')
                node is Element -> node.childNodes().forEach { walk(it) }
            }
        }
        p.childNodes().forEach { walk(it) }
        return sb.toString().split('\n')
    }

    /** Paragraphs after the "Description" heading. */
    private fun novelSummary(d: Document): String {
        val lines = mutableListOf<String>()
        var inDescription = false
        val children = d.selectFirst(".entry-content")?.children() ?: return ""
        loop@ for (element in children) {
            val text = normalize(element.text())
            if (!inDescription) {
                inDescription = element.tagName() == "p" && Regex("^Description\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
                continue
            }
            if (element.tagName() != "p") break@loop
            if (text.isNotEmpty() && !element.hasClass("has-text-color")) lines.add(text)
        }
        return lines.joinToString("\n\n")
    }

    /** Normalize a URL or href to a site-relative path with a trailing slash. */
    private fun toPath(href: String): String {
        val raw = href.trim()
        val sameSite = Regex("^https?://kakushitranslations\\.wordpress\\.com(/.*)?$", RegexOption.IGNORE_CASE)
            .find(raw)
        if (raw.startsWith("http", true) && sameSite == null) return raw
        val clean = (sameSite?.groupValues?.get(1)?.ifEmpty { "/" } ?: raw).split('?', '#').first()
        val path = if (clean.startsWith("/")) clean else "/$clean"
        return if (path.endsWith("/")) path else "$path/"
    }

    /** TS path form -> stored id (no leading slash; external URLs verbatim). */
    private fun idOf(path: String): String = path.removePrefix("/")

    /** Stored id -> TS site path form. */
    private fun sitePath(entryId: String): String =
        if (entryId.startsWith("http")) entryId else "/" + entryId.trimStart('/')

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}

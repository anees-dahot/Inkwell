package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Port of LNReader's `firsttrytranslations` plugin. Each novel is a WordPress
 * page whose content holds the synopsis and a hand-written list of links to
 * dated chapter posts; the three novels are all linked from the main menu.
 */
class FirstTryTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "firsttrytranslations"
    override val name = "First Try Translations"
    override val baseUrl = "https://firsttrytranslations.wordpress.com"
    override val lang = "en"

    private data class NovelPage(val path: String, val name: String, val cover: String?)

    /** Dated permalink every chapter post uses, e.g. `2022/02/25/<slug>/`. */
    private val chapterPath = Regex("^(\\d{4})/(\\d{2})/(\\d{2})/[^/]+/$")

    /** Previous / TOC / Next navigation words at the top or bottom of a chapter. */
    private val navWords = Regex("\\[?\\s*(previous|next|toc)\\s*(chapter)?\\s*\\]?", RegexOption.IGNORE_CASE)

    /** Metadata lines in a novel page that are not part of the synopsis. */
    private val metaLine = Regex("^(raw title|author|artist|illustrations|manga)\\s*:", RegexOption.IGNORE_CASE)

    // ---- helpers -----------------------------------------------------------

    private suspend fun fetchHtml(url: String): String = fetch(url)

    private fun entryContent(d: Document): Element? =
        d.selectFirst(".entry-content") ?: d.selectFirst("article .entry-content")

    /** Site-relative path (no leading slash, trailing slash) or an external URL. */
    private fun toPath(href: String): String {
        val raw = href.trim()
        val sameSite = Regex("^https?://firsttrytranslations\\.wordpress\\.com(/.*)?$", RegexOption.IGNORE_CASE).find(raw)
        if ((raw.startsWith("http://") || raw.startsWith("https://")) && sameSite == null) return raw
        val clean = (sameSite?.groupValues?.get(1)?.ifEmpty { "/" } ?: raw).split('?', '#').first()
        val slash = if (clean.startsWith("/")) clean else "/$clean"
        val path = slash.trimStart('/')
        return if (path.endsWith("/")) path else "$path/"
    }

    private fun normalizeText(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun novelName(d: Document): String {
        val raw = normalizeText(d.selectFirst("h1.entry-title")?.text().orEmpty())
        return raw.replace(Regex(":\\s*Table of Contents$", RegexOption.IGNORE_CASE), "")
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val items = fetchCatalogue().map { Entry(id, it.path, it.name, it.cover, ContentType.NOVEL) }
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val term = query.trim().lowercase()
        val items = fetchCatalogue()
            .filter { it.name.lowercase().contains(term) }
            .map { Entry(id, it.path, it.name, it.cover, ContentType.NOVEL) }
        return PagedResult(items, false)
    }

    private suspend fun fetchCatalogue(): List<NovelPage> {
        val d = doc(absPath(""))
        val paths = mutableListOf<String>()
        for (a in d.select("li.menu-item > a")) {
            val path = toPath(a.attr("href") ?: "")
            if (path.isNotEmpty() && !path.startsWith("http") && !chapterPath.matches(path) && !paths.contains(path)) {
                paths += path
            }
        }
        val pages = mutableListOf<NovelPage>()
        for (path in paths) {
            val page = loadNovelPage(path)
            if (novelChapters(page).isNotEmpty()) {
                pages += NovelPage(path, novelName(page), pageCover(page))
            }
        }
        return pages
    }

    private suspend fun loadNovelPage(path: String): Document {
        val d = doc(absPath(path))
        d.select(".jp-relatedposts, .sharedaddy, script, style").remove()
        return d
    }

    private fun pageCover(d: Document): String? =
        entryContent(d)?.selectFirst("img[src]")?.attr("src")?.let { abs(it) }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val path = toPath(entryId)
        val d = loadNovelPage(path)
        val chapters = novelChapters(d)
        check(chapters.isNotEmpty()) { "First Try Translations: no chapters listed at $path" }

        val author = novelMeta(d, "author")
        return EntryDetails(
            entry = Entry(id, path, novelName(d), pageCover(d), ContentType.NOVEL),
            description = novelSummary(d, novelName(d)),
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = emptyList(),
            status = null,
        ).also { cachedChapters[path] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        val path = toPath(entryId)
        cachedChapters[path]?.let { return it }
        val d = loadNovelPage(path)
        return novelChapters(d).also { cachedChapters[path] = it }
    }

    /** Chapter links in page order, labelled with their "Volume ..." heading. */
    private fun novelChapters(d: Document): List<Episode> {
        val chapters = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        var volume = ""
        for (element in entryContent(d)?.select("p").orEmpty()) {
            val chapterLinks = element.select("a[href]").filter { chapterPath.matches(toPath(it.attr("href"))) }
            if (chapterLinks.isEmpty()) {
                val heading = normalizeText(element.text())
                if (Regex("^(prologue|volume\\b|chapters?\\b)[^.]{0,30}:$", RegexOption.IGNORE_CASE).matches(heading)) {
                    volume = if (Regex("^volume", RegexOption.IGNORE_CASE).containsMatchIn(heading)) heading.dropLast(1) else ""
                }
                continue
            }
            for (link in chapterLinks) {
                val path = toPath(link.attr("href"))
                val match = chapterPath.find(path) ?: continue
                if (!seen.add(path)) continue
                val label = normalizeText(link.text())
                chapters += Episode(
                    id = path,
                    name = if (volume.isNotEmpty() && label.isNotEmpty()) "$volume - $label" else label.ifEmpty { path },
                    number = (chapters.size + 1).toFloat(),
                    dateMillis = parseDate(
                        "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}",
                        "yyyy-MM-dd",
                    ),
                )
            }
        }
        return chapters
    }

    /** Value of an "Author:" line on the novel page. */
    private fun novelMeta(d: Document, key: String): String {
        val regex = Regex("^$key\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        for (el in entryContent(d)?.select("p").orEmpty()) {
            regex.find(normalizeText(el.text()))?.let { return it.groupValues[1] }
        }
        return ""
    }

    /** Synopsis paragraphs: the text between the metadata and the chapter list. */
    private fun novelSummary(d: Document, title: String): String {
        val lines = mutableListOf<String>()
        for (element in entryContent(d)?.select("p").orEmpty()) {
            val text = paragraphLines(element)
                .map { normalizeText(it) }
                .filter { it.isNotEmpty() && !metaLine.containsMatchIn(it) }
                .joinToString("\n")
                .replace(Regex("^Description\\s*:\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (Regex("^table of contents$", RegexOption.IGNORE_CASE).matches(text)) break
            if (text.isNotEmpty() && text.lowercase() != title.lowercase() && !Regex("^[-\u2014\u2013_\\s]+$").matches(text)) {
                lines += text
            }
        }
        return lines.joinToString("\n")
    }

    /** Paragraph text that keeps <br> as line breaks (Jsoup's text() collapses them). */
    private fun paragraphLines(p: Element): List<String> {
        val sb = StringBuilder()
        for (node in p.childNodes()) {
            when (node) {
                is org.jsoup.nodes.TextNode -> sb.append(node.text())
                is Element -> if (node.tagName() == "br") sb.append('\n') else sb.append(node.text())
            }
        }
        return sb.toString().split('\n')
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val path = toPath(episodeId)
        val d = Jsoup.parse(fetchHtml(absPath(path)), absPath(path))
        val content = entryContent(d)
            ?: throw IllegalStateException("First Try Translations: no chapter text at $path")
        content.select("script, style, .sharedaddy, .jp-relatedposts").remove()
        for (el in content.select("p").toList()) {
            val text = el.text()
            if (Regex("\\b(previous|next|toc)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                navWords.replace(text, "").replace(Regex("[|\\s]"), "").isEmpty()
            ) el.remove()
        }
        return paragraphsOf(content)
    }
}

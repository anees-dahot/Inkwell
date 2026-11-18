package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class MadderTranslatesSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "maddertranslates"
    override val name = "Madder Translates"
    override val baseUrl = "https://www.maddertranslates.com"
    override val lang = "en"

    private val host = "maddertranslates.com"
    private val seriesPath = "wn"

    private val authorLine = Regex("^(?:作者|作家\\(Author\\)|Author)\\s*[：:]\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val navLinks = Regex("^(?:<<\\s*BACK|NEXT\\s*>>|TOC|\\s)+$", RegexOption.IGNORE_CASE)
    private val skipSummaryLine = Regex(
        "^(?:原作|Original Work|Light Novel|Support the Author|Amazon$|Booklive$|Bookwalker$)",
        RegexOption.IGNORE_CASE,
    )

    private data class NovelEntry(val path: String, val novelName: String, val status: String?)

    private fun cleanText(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** Text of an element, with <br> treated as a line break. */
    private fun linesWithBr(el: Element): List<String> {
        val clone = el.clone()
        clone.select("br").forEach { it.replaceWith(TextNode("\n")) }
        val sb = StringBuilder()
        clone.traverse { node, _ -> if (node is TextNode) sb.append(node.wholeText) }
        return sb.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Normalizes a link into a path without host or trailing slash. */
    private fun pathOf(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val url = baseUrl.toHttpUrlOrNull()?.resolve(href.trim()) ?: return null
        if (url.host.removePrefix("www.") != host) return null
        return url.encodedPath.trimEnd('/').ifBlank { null }
    }

    /** The "Web Novel" page links every series under an Ongoing/Hiatus heading. */
    private suspend fun novelList(): List<NovelEntry> {
        val d = doc(absPath(seriesPath))
        val novels = mutableListOf<NovelEntry>()
        val seen = HashSet<String>()
        var status: String? = "Ongoing"
        for (block in d.selectFirst(".td-page-content")?.children().orEmpty()) {
            val heading = block.select("strong").first()?.text() ?: ""
            when {
                Regex("hiatus", RegexOption.IGNORE_CASE).containsMatchIn(heading) -> status = "On Hiatus"
                Regex("ongoing", RegexOption.IGNORE_CASE).containsMatchIn(heading) -> status = "Ongoing"
                Regex("completed?", RegexOption.IGNORE_CASE).containsMatchIn(heading) -> status = "Completed"
            }
            for (a in block.select("a[href]")) {
                val path = pathOf(a.attr("href")) ?: continue
                // Series pages sit at /wnNN; chapters are one level deeper.
                if (!Regex("^/wn\\d+$", RegexOption.IGNORE_CASE).matches(path) || !seen.add(path)) continue
                // The link text is "・English title<br>「romaji」<br>「Japanese」".
                val novelName = linesWithBr(a).firstOrNull().orEmpty()
                    .replace(Regex("^[・\\s]+"), "").trim()
                if (novelName.isEmpty()) continue
                novels += NovelEntry(path, novelName, status)
            }
        }
        return novels
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        // The whole catalogue is a single short page.
        if (page > 1) return PagedResult(emptyList(), false)
        val items = novelList().map {
            Entry(id, it.path.trimStart('/'), it.novelName, null, ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty() || page > 1) return PagedResult(emptyList(), false)
        val items = novelList()
            .filter { novel -> terms.all { novel.novelName.lowercase().contains(it) } }
            .map { Entry(id, it.path.trimStart('/'), it.novelName, null, ContentType.NOVEL) }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val entries = runCatching { novelList() }.getOrDefault(emptyList())
        val entry = entries.firstOrNull { it.path == "/" + entryId.trimStart('/') }
        val content = d.selectFirst(".td-page-content") ?: org.jsoup.Jsoup.parse("<div class='td-page-content'></div>")
        content.select("script, style, noscript, .code-block").remove()

        val chapters = mutableListOf<Episode>()
        val seen = HashSet<String>()
        val chapterPrefix = Regex("^${Regex.escape("/" + entryId.trimStart('/'))}/c[^/]+$", RegexOption.IGNORE_CASE)
        for (a in content.select("a[href]")) {
            val path = pathOf(a.attr("href")) ?: continue
            val chapterName = cleanText(a.text())
            if (chapterName.isEmpty() || !seen.add(path) || !chapterPrefix.matches(path)) continue
            chapters += Episode(path.trimStart('/'), chapterName, null, null)
        }

        val novelName = entry?.novelName
            ?: cleanText(d.selectFirst("h1.entry-title")?.text() ?: "").ifBlank { "Untitled" }

        var author: String? = null
        // Everything above the first rule is the synopsis and credits.
        val summary = mutableListOf<String>()
        val kids = content.children()
        if (kids.isNotEmpty()) {
            val intro = mutableListOf<Element>(kids.first()!!)
            for (el in kids.drop(1)) {
                if (el.tagName() == "hr") break
                intro += el
            }
            for (block in intro) {
                if (block.tagName() != "p") continue
                val joined = linesWithBr(block).joinToString("\n")
                if (joined.isEmpty()) continue
                val foundAuthor = authorLine.find(joined)
                if (foundAuthor != null) {
                    author = foundAuthor.groupValues[1].trim()
                    continue
                }
                if (skipSummaryLine.containsMatchIn(joined)) continue
                summary += joined
            }
        }

        return EntryDetails(
            entry = Entry(id, entryId.trimStart('/'), novelName, null, ContentType.NOVEL),
            description = summary.joinToString("\n\n").ifBlank { null },
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = emptyList(),
            status = entry?.status,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId] ?: emptyList()
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.selectFirst(".td-page-content") ?: return emptyList()
        content.select(
            "script, style, iframe, noscript, form, ins, .code-block, #disqus_thread, .td-post-sharing",
        ).remove()
        content.select("p").forEach { paragraph ->
            val text = cleanText(paragraph.text())
            // Drop empty paragraphs and the closing "<<BACK NEXT>> TOC" link row.
            if ((text.isEmpty() && paragraph.select("img").isEmpty()) || navLinks.matches(text)) paragraph.remove()
        }
        // Lazy-loaded images keep their real address in data-lazy-src.
        content.select("img[data-lazy-src]").forEach { img ->
            img.attr("src", img.attr("data-lazy-src"))
        }
        if (content.select("img").isEmpty() && content.text().trim().length < 200) return emptyList()
        return paragraphsOf(content)
    }
}

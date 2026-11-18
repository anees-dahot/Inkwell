package app.inkwell.data.source.novel

import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperNovelSource
import app.inkwell.data.source.paragraphsOf
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

/**
 * CClaw Translations (cclawtranslations.home.blog) is a WordPress.com blog.
 * Two catalogue pages ("Ongoing Projects", "Completed Projects") list every
 * novel with a cover and a link to its own table-of-contents page. That page
 * groups dated chapter posts (/2023/07/11/<slug>/) under "Volume N" headings.
 * Neither the catalogue nor the table of contents has a synopsis.
 */
class CClawTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "cclawtranslations"
    override val name = "CClaw Translations"
    override val baseUrl = "https://cclawtranslations.home.blog"
    override val lang = "en"

    // Chapters are dated posts: /2023/07/11/<slug>/
    private val chapterPathRe = Regex("^/(\\d{4})/(\\d{2})/(\\d{2})/[^/]+/?$")

    private val volumeRe = Regex("^(?:volume|vol\\.?|book|part|arc)\\b", RegexOption.IGNORE_CASE)

    private val sections = listOf("ongoing-projects/", "completed-projects/")

    private fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** Site-relative path (leading + trailing slash) for same-host hrefs. */
    private fun sitePath(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val u = abs(href)?.toHttpUrlOrNull() ?: return null
        if (u.host.removePrefix("www.") != "cclawtranslations.home.blog") return null
        val path = u.encodedPath.replace(Regex("/{2,}"), "/")
        return if (path.endsWith("/")) path else "$path/"
    }

    private fun coverOf(img: Element?): String? {
        img ?: return null
        val src = img.attr("data-orig-file").takeIf { it.isNotBlank() } ?: img.attr("src")
        return src.takeIf { it.isNotBlank() }?.split('?')?.first()?.let { abs(it) }
    }

    /** First <img> between this element and the next h2 (cheerio nextUntil). */
    private fun imgUntilNextH2(h2: Element): Element? {
        var sibling = h2.nextElementSibling()
        while (sibling != null && sibling.tagName() != "h2") {
            if (sibling.tagName() == "img") return sibling
            sibling.selectFirst("img")?.let { return it }
            sibling = sibling.nextElementSibling()
        }
        return null
    }

    private suspend fun listSection(path: String): List<Entry> {
        val d = doc(absPath(path))
        val novels = mutableListOf<Entry>()
        val seen = HashSet<String>()
        // Each novel is an <h2> holding the ToC link, followed by its cover.
        d.select(".entry-content h2").forEach { h2 ->
            val link = h2.selectFirst("a") ?: return@forEach
            val novelPath = sitePath(link.attr("href")) ?: return@forEach
            val name = clean(link.text())
            if (name.isEmpty() || !seen.add(novelPath)) return@forEach
            novels += Entry(
                id,
                novelPath.trimStart('/'),
                name,
                coverOf(imgUntilNextH2(h2)),
                ContentType.NOVEL,
            )
        }
        return novels
    }

    private suspend fun catalogue(): List<Entry> = sections.flatMap { listSection(it) }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(catalogue(), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val needle = clean(query).lowercase()
        val items = catalogue().filter { clean(it.title).lowercase().contains(needle) }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private fun closestP(el: Element): Element? {
        var cur: Element? = el
        while (cur != null) {
            if (cur.tagName() == "p") return cur
            cur = cur.parent()
        }
        return null
    }

    /**
     * Walk headings and links in document order so each chapter knows its
     * volume. Some entries are split over several anchors to the same post
     * ("Epi" + "logue"), so name them after the whole line.
     */
    private fun chapterList(content: Element?): List<Episode> {
        var volume = ""
        val seen = HashSet<String>()
        val chapters = mutableListOf<Episode>()
        content?.select("h2, h3, a")?.forEach { el ->
            if (el.tagName() != "a") {
                val heading = clean(el.text())
                if (volumeRe.containsMatchIn(heading)) volume = heading
                return@forEach
            }
            val href = sitePath(el.attr("href")) ?: return@forEach
            val date = chapterPathRe.matchEntire(href) ?: return@forEach
            val line = closestP(el)
            val hrefs = line?.select("a")
                ?.mapNotNull { sitePath(it.attr("href")) }?.toSet() ?: setOf(href)
            val label = clean(if (hrefs.size == 1) line?.text().orEmpty() else el.text())
                .ifEmpty { clean(el.text()) }
            if (label.isEmpty() || !seen.add(href)) return@forEach
            chapters += Episode(
                id = href.trimStart('/'),
                name = if (volume.isNotEmpty()) "$volume - $label" else label,
                number = (chapters.size + 1).toFloat(),
                dateMillis = parseDate(
                    "${date.groupValues[1]}-${date.groupValues[2]}-${date.groupValues[3]}",
                    "yyyy-M-d",
                ),
            )
        }
        return chapters
    }

    override suspend fun details(entryId: String): EntryDetails {
        val path = sitePath("/" + entryId.trim('/'))
            ?: throw IllegalArgumentException("CClaw Translations: bad novel path $entryId")
        val d = doc(absPath(path))
        val content = d.selectFirst(".entry-content")
        val title = clean(d.selectFirst("h1.entry-title")?.text().orEmpty())
            .replace(Regex("\\s*\\(?\\bToC\\b\\)?\\s*$", RegexOption.IGNORE_CASE), "")
            .takeIf { it.isNotEmpty() } ?: "Untitled"
        val cover = content?.selectFirst("img")?.let { coverOf(it) }

        // Leave the status unset when the catalogue cannot be read, rather than
        // reporting a finished novel as ongoing.
        val status = runCatching {
            if (listSection("completed-projects/").any { it.id == path.trimStart('/') }) {
                "Completed"
            } else {
                "Ongoing"
            }
        }.getOrNull()

        return EntryDetails(
            entry = Entry(id, path.trimStart('/'), title, cover, ContentType.NOVEL),
            description = null,
            authors = emptyList(),
            genres = emptyList(),
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val path = sitePath("/" + entryId.trim('/'))
            ?: throw IllegalArgumentException("CClaw Translations: bad novel path $entryId")
        return chapterList(doc(absPath(path)).selectFirst(".entry-content"))
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val path = sitePath("/" + episodeId.trim('/'))
            ?: throw IllegalArgumentException("CClaw Translations: bad chapter $episodeId")
        val d = doc(absPath(path))
        val content = d.selectFirst(".entry-content") ?: return emptyList()
        content.select(
            "script, style, .sharedaddy, #jp-post-flair, .jp-relatedposts, #wordads-inline-marker",
        ).remove()
        return paragraphsOf(content)
    }
}

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
import org.jsoup.nodes.Document

/**
 * BloodcoreTL (bloodcoretl.com). A one-novel fan translation site built with
 * Next.js: the home page is the novel page and every chapter lives at
 * `/chapter/<slug>`. There is no browse listing and no site search, so the
 * catalogue is the home page and search filters it by name.
 */
class BloodCoreTlSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "bloodcoretl"
    override val name = "BloodcoreTL"
    override val baseUrl = "https://bloodcoretl.com"
    override val lang = "en"

    private val chapterPathRe = Regex("^/chapter/[^/?#]+$")

    private suspend fun loadPage(path: String): Document = doc(absPath(path.trimStart('/')))

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** Normalize a URL or href to a site-relative path (leading slash kept). */
    private fun toPath(href: String): String {
        val raw = href.trim().replace(Regex("^https?://(www\\.)?bloodcoretl\\.com", RegexOption.IGNORE_CASE), "")
        val path = raw.split(Regex("[?#]")).first()
        return if (path.startsWith("/")) path else "/" + path
    }

    private fun novelName(d: Document): String = normalize(d.selectFirst("h1")?.text().orEmpty())

    /** The cover is served through `/_next/image?url=<encoded original>`. */
    private fun novelCover(d: Document): String? {
        val src = d.selectFirst("img[alt=\"Cover Image\"]")?.attr("src").orEmpty()
        val original = Regex("[?&]url=([^&]+)").find(src)?.groupValues?.get(1)
        if (original != null) return abs(decodeURIComponent(original))
        return src.takeIf { it.isNotEmpty() }?.let { abs(it) }
    }

    private fun decodeURIComponent(s: String): String =
        java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    /** Value of the "Author:" line next to the cover. */
    private fun novelAuthor(d: Document): String? {
        for (p in d.select("p")) {
            val firstSpan = p.children().firstOrNull { it.tagName() == "span" }
            val label = normalize(firstSpan?.text().orEmpty())
            if (Regex("^author:?$", RegexOption.IGNORE_CASE).matches(label)) {
                val clone = p.clone()
                clone.children().filter { it.tagName() == "span" }.forEach { it.remove() }
                return normalize(clone.text()).takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** Synopsis paragraphs under the "Synopsis" heading. */
    private fun novelSummary(d: Document): String? {
        val heading = d.select("h3").firstOrNull {
            Regex("^synopsis$", RegexOption.IGNORE_CASE).matches(normalize(it.text()))
        }
        val lines = heading?.parent()?.select("p")
            ?.map { normalize(it.text()) }?.filter { it.isNotEmpty() }.orEmpty()
        return lines.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    /**
     * Chapter links in page order, labelled with the "Book N" heading they sit
     * under. The "Start Reading" button repeats the first link, so only anchors
     * wrapping the chapter title are used.
     */
    private fun novelChapters(d: Document): List<Episode> {
        val chapters = mutableListOf<Episode>()
        val seen = HashSet<String>()
        var book = ""
        d.select("p.bg-gray-600, a[href^=\"/chapter/\"]").forEach { el ->
            if (el.tagName() == "p") {
                book = normalize(el.text())
                return@forEach
            }
            val path = toPath(el.attr("href"))
            if (!chapterPathRe.matches(path)) return@forEach
            val label = normalize(el.selectFirst("p")?.text().orEmpty())
            if (label.isEmpty() || !seen.add(path)) return@forEach
            chapters += Episode(
                id = path.trimStart('/'),
                name = if (book.isNotEmpty()) "$book - $label" else label,
                number = (chapters.size + 1).toFloat(),
                dateMillis = null,
            )
        }
        return chapters
    }

    private suspend fun novelItem(): Entry {
        val d = loadPage("/")
        return Entry(id, "", novelName(d), novelCover(d), ContentType.NOVEL)
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(listOf(novelItem()), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val novel = novelItem()
        val term = query.trim().lowercase()
        return if (novel.title.lowercase().contains(term)) {
            PagedResult(listOf(novel), false)
        } else {
            PagedResult(emptyList(), false)
        }
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val d = loadPage(entryId)
        return EntryDetails(
            entry = Entry(id, entryId.trim('/'), novelName(d).ifEmpty { "Untitled" }, novelCover(d), ContentType.NOVEL),
            description = novelSummary(d),
            authors = listOfNotNull(novelAuthor(d)),
            genres = emptyList(),
            status = null,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> = novelChapters(loadPage(entryId))

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.selectFirst("div.hyphens-auto") ?: return emptyList()
        if (content.text().trim().isEmpty()) return emptyList()
        return paragraphsOf(content)
    }
}

package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Its A Me Translations (itsame-translations.com) — port of LNReader's
 * itsametranslations plugin. The novels are the project pages linked from the
 * top menu; chapters are posts at /<category>/<slug> linked from each project
 * page.
 */
class ItsAmeTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "itsametranslations"
    override val name = "Its A Me Translations"
    override val baseUrl = "https://itsame-translations.com"
    override val lang = "en"

    private val pageSize = 20

    /** Top menu entries that group novels by state. */
    private val statusByGroup = mapOf(
        "ongoing-projects" to "Ongoing",
        "completed-series" to "Completed",
        "dropped" to "Cancelled",
    )
    private val nonChapterRoots = setOf("category", "tag", "author", "wp-content")
    private val summaryLabel = Regex("^(summary|description|synopsis)\\s*:?$", RegexOption.IGNORE_CASE)
    private val summaryEnd = Regex("^(chapters?|toc|table of contents?)\\s*:?$", RegexOption.IGNORE_CASE)

    private data class ChapterData(val name: String, val path: String)
    private data class NovelData(
        val name: String,
        val path: String,
        val cover: String?,
        val status: String?,
        val chapters: List<ChapterData>,
        val author: String?,
        val summary: String?,
    )

    private var novelListCache: List<NovelData>? = null

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> =
        toItems(novelList(), page)

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return PagedResult(emptyList(), false)
        // The site's own search returns chapter posts rather than novels, so
        // match the project titles instead.
        val novels = novelList().filter { novel -> terms.all { it in novel.name.lowercase() } }
        return toItems(novels, page)
    }

    private fun toItems(novels: List<NovelData>, page: Int): PagedResult<Entry> {
        val from = (page - 1) * pageSize
        val items = novels.drop(from).take(pageSize).map { n ->
            Entry(id, n.path, n.name, n.cover, ContentType.NOVEL)
        }
        return PagedResult(items, from + pageSize < novels.size)
    }

    /** The novels are the project pages linked from the site's top menu. */
    private suspend fun novelList(): List<NovelData> {
        novelListCache?.let { return it }
        val (novels, complete) = fetchNovelList()
        if (complete) novelListCache = novels
        return novels
    }

    private suspend fun fetchNovelList(): Pair<List<NovelData>, Boolean> {
        val d = doc(baseUrl)
        val found = mutableListOf<Triple<String, String, String>>() // path, name, status
        val seen = HashSet<String>()
        d.select("#primary-menu > li").forEach { group ->
            val groupPath = pathOf(group.selectFirst("> a")?.attr("href").orEmpty())
            val status = statusByGroup[groupPath] ?: return@forEach
            group.select("ul.sub-menu a").forEach { a ->
                val path = pathOf(a.attr("href")) ?: return@forEach
                val nm = normalize(a.text())
                // Novel pages sit at a single-segment address; announcements don't.
                if (nm.isEmpty() || '/' in path || !seen.add(path)) return@forEach
                found.add(Triple(path, nm, status))
            }
        }

        val loaded = mutableListOf<NovelData?>()
        var complete = true
        for (item in found) {
            val novel = runCatching { loadNovel(item.first, item.second, item.third) }.getOrNull()
            if (novel == null) complete = false
            loaded += novel
        }
        // Every project failing to load is a fetch problem, not an empty source.
        if (found.isNotEmpty() && loaded.all { it == null }) {
            throw IllegalStateException("$name: could not load any novel.")
        }
        // Dropped projects whose chapters only exist on the old blog have
        // nothing to read here.
        return Pair(loaded.filterNotNull().filter { it.chapters.isNotEmpty() }, complete)
    }

    /** Reads the cover, summary, author and chapter list of one project page. */
    private suspend fun loadNovel(path: String, name: String, status: String?): NovelData {
        val d = doc(absPath(path) + "/")
        val content = d.selectFirst("article .entry-content")

        val cover = content?.let { c ->
            d.selectFirst("article img.wp-post-image")?.let { imageOf(it) }
                ?: c.selectFirst("img")?.let { imageOf(it) }
        }

        // Chapters are posts at /<category>/<slug>. Anything else (other
        // project pages, the old blog, raws) is not a chapter.
        val chapters = mutableListOf<ChapterData>()
        val seenChapters = HashSet<String>()
        content?.select("a[href]")?.forEach { anchor ->
            val chapterPath = pathOf(anchor.attr("href")) ?: return@forEach
            if (!seenChapters.add(chapterPath)) return@forEach
            val parts = chapterPath.split('/')
            if (parts.size != 2 || parts[0] in nonChapterRoots) return@forEach
            var chapterName = normalize(anchor.text())
            if (chapterName.isEmpty()) return@forEach
            // "Part I" only makes sense with the chapter it belongs to.
            if (Regex("^part\\b", RegexOption.IGNORE_CASE).containsMatchIn(chapterName)) {
                val parent = anchor.parents().firstOrNull { it.tagName() == "ul" }?.parent()
                if (parent != null && parent.tagName() == "li") {
                    val clone = parent.clone()
                    clone.children().remove()
                    val parentName = normalize(clone.text())
                    if (parentName.isNotEmpty()) chapterName = "$parentName - $chapterName"
                }
            }
            chapters.add(ChapterData(chapterName, chapterPath))
        }

        // Volume illustrations, afterwords and similar entries repeat across
        // volumes; tell them apart by the volume number in their address.
        val nameCount = chapters.groupingBy { it.name }.eachCount()
        val named = chapters.map { ch ->
            if ((nameCount[ch.name] ?: 0) < 2) return@map ch
            val vol = Regex("[-/]v(?:ol(?:ume)?)?-?(\\d+)(?:-|$)", RegexOption.IGNORE_CASE)
                .find(ch.path)?.groupValues?.get(1) ?: return@map ch
            ChapterData("${ch.name} (Vol $vol)", ch.path)
        }

        var author: String? = null
        val summary = mutableListOf<String>()
        var inSummary = false
        content?.children()?.forEach { block ->
            val text = normalize(block.text())
            Regex("^Author\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
                author = it.groupValues[1].trim()
            }
            if (block.tagName() == "ul" || block.tagName() == "ol") {
                inSummary = false
                return@forEach
            }
            if (summaryLabel.matches(text)) {
                inSummary = true
                return@forEach
            }
            if (summaryEnd.matches(text)) inSummary = false
            if (inSummary && block.tagName() == "p" && text.isNotEmpty() && block.select("a").isEmpty()) {
                summary.add(text)
            }
        }

        return NovelData(
            name = name.ifBlank { normalize(d.selectFirst("article h1.entry-title")?.text().orEmpty()).ifBlank { "Untitled" } },
            path = path,
            cover = cover,
            status = status,
            chapters = named,
            author = author?.takeIf { it.isNotBlank() },
            summary = summary.joinToString("\n\n").ifBlank { null },
        )
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val listed = novelList().find { it.path == entryId }
        val novel = runCatching { loadNovel(listed?.path ?: entryId, listed?.name ?: "", listed?.status) }
            .getOrElse { listed?.let { NovelData(it.name, it.path, it.cover, it.status, emptyList(), null, null) } }
            ?: throw IllegalStateException("$name: novel not found.")
        return EntryDetails(
            entry = Entry(id, entryId, novel.name, novel.cover, ContentType.NOVEL),
            description = novel.summary,
            authors = listOfNotNull(novel.author),
            genres = emptyList(),
            status = novel.status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val listed = novelList().find { it.path == entryId }
        val novel = loadNovel(listed?.path ?: entryId, listed?.name ?: "", listed?.status)
        return novel.chapters.map { Episode(it.path, it.name, null, null) }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId) + "/")
        val content = d.selectFirst("article .entry-content") ?: return emptyList()
        content.select("script, style, iframe, noscript, form, .sharedaddy, .jetpack-likes-widget-wrapper").remove()
        content.select("p, figure").forEach { block ->
            val text = normalize(block.text())
            val isNavigation = Regex("\\bToC\\b").containsMatchIn(text) && text.length < 60
            val isDonation = block.select("a[href*=ko-fi.com]").isNotEmpty()
            val isEmpty = text.isEmpty() && block.select("img").isEmpty()
            if (isNavigation || (isDonation && text.length < 160) || isEmpty) block.remove()
        }
        // Lazy-loaded images keep their real address in data-src.
        content.select("img[data-src]").forEach { it.attr("src", it.attr("data-src")) }
        return paragraphsOf(content)
    }

    // ---- helpers -----------------------------------------------------------

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** Site-relative path (no leading slash) for pages on this site only. */
    private fun pathOf(href: String): String? = runCatching {
        val url = baseUrl.toHttpUrl().resolve(href.trim()) ?: return null
        if (url.host.removePrefix("www.") != "itsame-translations.com") return null
        url.encodedPath.trimEnd('/').removePrefix("/").takeUnless { it.isEmpty() }
    }.getOrNull()

    private fun imageOf(img: org.jsoup.nodes.Element): String? {
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        return if (Regex("^https?:").containsMatchIn(src)) src else null
    }
}

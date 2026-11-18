package app.inkwell.data.source.novel

import okhttp3.HttpUrl.Companion.toHttpUrl

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Port of LNReader's onlinereadfreenovel plugin. The text of a book is split
 across numbered pages; each page is one "chapter". Empty 200 responses are
 skipped during the chapter walk.
 */
class OnlineReadFreeNovelSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "onlinereadfreenovel"
    override val name = "Online Read Free Novel"
    override val baseUrl = "https://onlinereadfreenovel.com"
    override val lang = "en"

    // Cloudflare challenges browser UAs from datacenter networks but lets a
    // client that names itself through.
    override val userAgent = "Mozilla/5.0 (compatible; LNReader)"

    private val maxPages = 400
    private val pageBatch = 6
    private val pageRetries = 3
    private val maxEmptyRun = 12
    private val heading =
        Regex("^(?:chapter\\s+[\\w-]+|prologue|epilogue)\\b[^.!?]{0,60}$", RegexOption.IGNORE_CASE)
    private val maxHeadings = 4

    private fun toPath(href: String): String =
        runCatching { baseUrl.toHttpUrl().resolve(href) }.getOrNull()?.encodedPath
            ?: href.takeIf { it.startsWith("/") } ?: "/$href"

    private fun toAbsolute(src: String): String? = abs(src)

    /** Site-relative path without the leading slash, for Entry/Episode ids. */
    private fun idOf(path: String): String = path.trim('/')

    private suspend fun fetchDom(path: String): Document {
        val body = fetch(baseUrl + path)
        return Jsoup.parse(body, baseUrl + path)
    }

    /** Retries a chapter-list page after rate limits, server errors or network errors. */
    private suspend fun fetchPageDom(path: String): Document? {
        repeat(pageRetries) { attempt ->
            try {
                return withContext(Dispatchers.IO) {
                    client.newCall(request(baseUrl + path)).execute().use { r ->
                        if (r.code == 404) return@use null
                        check(r.isSuccessful) { "$name HTTP ${r.code}" }
                        Jsoup.parse(r.body?.string().orEmpty(), baseUrl + path)
                    }
                }
            } catch (e: Exception) {
                if (attempt == pageRetries - 1) throw e
                delay(1000L shl attempt)
            }
        }
        return null
    }

    private fun parseBookList(d: Document): List<Entry> =
        d.select("article.box").mapNotNull { box ->
            val link = box.selectFirst("h2.title a") ?: return@mapNotNull null
            val href = link.attr("href") ?: return@mapNotNull null
            val src = box.selectFirst("div.text img")?.attr("src")
            Entry(
                sourceId = id,
                id = idOf(toPath(href)),
                title = link.text().trim(),
                coverUrl = src?.let { toAbsolute(it) },
                type = ContentType.NOVEL,
            )
        }

    // Default filters: genre "all".
    override suspend fun popular(page: Int): PagedResult<Entry> {
        val path = if (page > 1) "/allbooks/p/$page/" else "/allbooks/"
        val items = parseBookList(fetchDom(path))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val term = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val path = if (page > 1) "/search_by_author_title_series/q/$term/p/$page"
        else "/search_by_author_title_series/q/$term"
        val d = fetchDom(path)
        val items = d.select("ul.books li a").mapNotNull { item ->
            val href = item.attr("href") ?: return@mapNotNull null
            val style = item.selectFirst(".i1")?.attr("style").orEmpty()
            val cover = Regex("url\\([\"']?([^\"')]+)[\"']?\\)").find(style)?.groupValues?.get(1)
            val path = bookPath(toPath(href), cover) ?: return@mapNotNull null
            Entry(
                sourceId = id,
                id = idOf(path),
                title = item.selectFirst(".h2")?.attr("title")?.takeIf { it.isNotBlank() }
                    ?: item.text().trim(),
                coverUrl = cover?.let { toAbsolute(it) },
                type = ContentType.NOVEL,
            )
        }
        return PagedResult(items, items.isNotEmpty())
    }

    /**
     * The book's `_read.html` path for a search link. Search links are cut to
     * a fixed length, so when the cut falls inside the slug it is recovered
     * from the cover file name (`[<id>_]<slug>_preview.jpg`).
     */
    private fun bookPath(href: String, cover: String?): String? {
        val path = toPath(href)
        if (Regex("/\\d+-[^/]+_read\\.html$").containsMatchIn(path)) return path
        val match = Regex("^(/[^/]+/\\d+-)([^/]*)$").find(path) ?: return null
        val prefix = match.groupValues[1]
        val tail = match.groupValues[2]

        val dot = tail.indexOf('.')
        if (dot > 0) return prefix + tail.take(dot) + "_read.html"

        val file = cover?.let { Regex("/([^/]+)_preview\\.\\w+$").find(it)?.groupValues?.get(1) }
            ?: return null
        val slug = listOf(file, file.replace(Regex("^\\d+_"), "")).firstOrNull { it.startsWith(tail) }
        return slug?.let { prefix + it + "_read.html" }
    }

    /** Page `n` is `p,<n>,` inserted before the id. */
    private fun pagePath(novelPath: String, page: Int): String {
        if (page <= 1) return novelPath
        return novelPath.replace(Regex("/(\\d+-[^/]+_read\\.html)$"), "/p,$page,\$1")
    }

    /**
     * Walks the text pages of a book. The walk ends at the first page with
     * text and no next link, or after maxEmptyRun empty pages in a row.
     */
    private suspend fun parsePages(novelPath: String, firstPage: Document): List<Episode> {
        val chapters = mutableListOf<Episode>()
        // A path not in the `_read.html` form cannot be paged; list page 1 only.
        val lastPage = if (pagePath(novelPath, 2) == novelPath) 1 else maxPages
        var emptyRun = 0
        var start = 1
        while (start <= lastPage) {
            val pages = (start until (start + pageBatch).coerceAtMost(lastPage + 1)).toList()
            start += pageBatch
            val doms = coroutineScope {
                pages.map { page ->
                    async {
                        if (page == 1) firstPage else fetchPageDom(pagePath(novelPath, page))
                    }
                }.awaitAll()
            }

            pages.forEachIndexed { i, page ->
                val dom = doms[i]
                val paragraphs = dom?.let { paragraphsOfDom(it) }.orEmpty()
                if (paragraphs.isEmpty()) {
                    if (++emptyRun >= maxEmptyRun) return chapters
                    return@forEachIndexed
                }
                emptyRun = 0
                val headings = paragraphs
                    .map { it.replace(Regex("<[^>]*>"), "").trim() }
                    .filter { heading.containsMatchIn(it) }
                // Many headings on one page is a table of contents, not chapters.
                val headingText = headings.firstOrNull()?.takeIf { headings.size <= maxHeadings }
                chapters.add(
                    Episode(
                        id = idOf(pagePath(novelPath, page)),
                        name = "Page $page" + (headingText?.let { ": $it" } ?: ""),
                        number = (chapters.size + 1).toFloat(),
                        dateMillis = null,
                    ),
                )
                val hasNext = dom!!.select(".page_next a").isNotEmpty()
                if (!hasNext) return chapters
            }
        }
        return chapters
    }

    override suspend fun details(entryId: String): EntryDetails {
        val novelPath = "/" + entryId.trim('/')
        val d = fetchDom(novelPath)
        val bookName = d.selectFirst("h1")?.text()?.trim().orEmpty()
        check(bookName.isNotEmpty()) { "No book found at $novelPath" }
        val author = d.selectFirst(".box_in > b > a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val cover = d.selectFirst("#textToRead img")?.attr("src")?.let { toAbsolute(it) }
        val chapters = parsePages(novelPath, d).also { cachedChapters[entryId] = it }
        return EntryDetails(
            entry = Entry(id, entryId, bookName, cover, ContentType.NOVEL),
            description = null,
            authors = listOfNotNull(author),
            genres = emptyList(),
            status = null,
        )
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    /**
     * The text of a page as paragraph HTML: a run of two or more `<br>` marks a
     * paragraph boundary; images, scripts, comments and empty paragraphs drop.
     */
    private fun paragraphsOfDom(d: Document): List<String> {
        val text = d.selectFirst("#textToRead") ?: return emptyList()
        text.select("img, a.highslide, script, style").remove()
        val html = text.html().replace(Regex("</?p[^>]*>", RegexOption.IGNORE_CASE), "")
        val brRun = Regex("(?:<br\\s*/?>\\s*){2,}", RegexOption.IGNORE_CASE)
        return html.split(brRun)
            .map { part ->
                part.replace(Regex("<!--[\\s\\S]*?-->"), "")
                    .replace(Regex("^(?:<br\\s*/?>|&nbsp;|\u00a0|\\s)+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("(?:<br\\s*/?>|&nbsp;|\u00a0|\\s)+$", RegexOption.IGNORE_CASE), "")
            }
            .filter { it.isNotEmpty() && it != "--&gt;" }
            .map { Jsoup.parse(it).text().trim() }
            .filter { it.isNotEmpty() }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = fetchDom("/" + episodeId.trim('/'))
        return paragraphsOfDom(d)
    }
}

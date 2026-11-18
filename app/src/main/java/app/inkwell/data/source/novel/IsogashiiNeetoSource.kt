package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Working NEET Translation (isogashiineeto.com) — port of LNReader's
 * isogashiineeto plugin. Small WordPress.com blog: four hand-written series
 * pages in the "Series" menu, each holding a hand-maintained table of
 * contents. Chapter paths may be site-relative or absolute URLs to the
 * translator's other blogs, so ids keep the TS path form verbatim.
 */
class IsogashiiNeetoSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "isogashiineeto"
    override val name = "Working NEET Translation"
    override val baseUrl = "https://isogashiineeto.com"
    override val lang = "en"

    private val siteHosts = listOf(
        "isogashiineeto.com", "www.isogashiineeto.com", "isogashiineeto.wordpress.com",
    )
    private val deadHost = "workingneet.com"
    private val deadHostReplacement = "workingneet.wordpress.com"

    private val categoryBySeries = mapOf(
        "/neet-hello-work/" to "neet-hello-work",
        "/although-im-a-wizard-with-a-dark-attribute-i-became-a-hero-somehow-i-think-ill-try-my-best-so-that-my-wives-can-live-a-good-life-anyways/" to "dark-magician-hero",
        "/hatena%e2%98%86illusion/" to "hatena%e2%98%86illusion",
        "/kuzukoku/" to "kuzukoku",
    )

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    private data class ChapterData(val name: String, val path: String, val date: String?)

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val items = runCatching { fetchCatalog() }.getOrDefault(emptyList())
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val term = query.trim().lowercase()
        if (term.isEmpty()) return PagedResult(emptyList(), false)

        val catalog = runCatching { fetchCatalog() }.getOrDefault(emptyList())
        val byPath = catalog.associateBy { it.id }
        val results = mutableListOf<Entry>()
        val seen = HashSet<String>()
        fun add(novel: Entry?) {
            if (novel != null && seen.add(novel.id)) results.add(novel)
        }

        // The series' own titles are not part of WordPress search, so match
        // them directly first.
        catalog.forEach { novel -> if (novel.title.lowercase().contains(term)) add(novel) }

        // Chapter posts that match the term point back to their series
        // through the post category.
        val d = runCatching { doc(baseUrl + "/?s=" + enc(query.trim())) }.getOrNull()
        d?.select("#main article .cat-links a")?.forEach { a ->
            val slug = categorySlug(a.attr("href"))
            categoryBySeries.forEach { (seriesPath, cat) ->
                if (cat == slug) add(byPath[seriesPath])
            }
        }
        return PagedResult(results, false)
    }

    /** Series from the "Series" sub-menu, each with the cover of its page. */
    private suspend fun fetchCatalog(): List<Entry> {
        val d = doc(baseUrl + "/")
        val entries = mutableListOf<Pair<String, String>>() // path to name
        val seen = HashSet<String>()
        d.select("#site-navigation .sub-menu a").forEach { a ->
            val href = a.attr("href")
            val nm = normalize(a.text())
            if (href.isBlank() || nm.isEmpty()) return@forEach
            val path = toPath(href)
            if (path.isEmpty() || path.startsWith("http") || !seen.add(path)) return@forEach
            entries.add(path to nm)
        }
        return entries.mapNotNull { (path, nm) ->
            val page = runCatching { doc(absPath(path)) }.getOrNull()
                ?: return@mapNotNull null
            val content = entryContent(page) ?: return@mapNotNull null
            Entry(id, path, nm, firstImage(content), ContentType.NOVEL)
        }
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val content = entryContent(d)
        val title = normalize(d.selectFirst("#main h1.entry-title")?.text().orEmpty())
            .ifBlank { "Untitled" }
        val chapters = parseToc(content)

        var synopsis: String? = null
        var author: String? = null
        content?.children()?.filter { it.tagName() == "p" }?.forEach { p ->
            val text = normalize(p.text())
            Regex("^Synopsis\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
                synopsis = it.groupValues[1]
                return@forEach
            }
            Regex("^Author\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)?.let {
                author = it.groupValues[1]
                return@forEach
            }
            // Illustrators? line is recognised but the model has no artist field.
        }

        val all = mutableListOf<ChapterData>()
        all += chapters
        val categorySlug = categoryBySeries[entryId]
        if (categorySlug != null && chapters.isNotEmpty()) {
            all += fetchNewerPosts(categorySlug, chapters)
        }
        val episodes = all.mapIndexed { index, ch ->
            Episode(ch.path, ch.name, (index + 1).toFloat(), parseDate(ch.date, "yyyy-MM-dd"))
        }
        return EntryDetails(
            entry = Entry(id, entryId, title, content?.let { firstImage(it) }, ContentType.NOVEL),
            description = synopsis?.takeIf { it.isNotBlank() },
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = emptyList(),
            status = null,
        ).also { cachedEpisodes[entryId] = episodes }
    }

    private val cachedEpisodes = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedEpisodes[entryId]?.let { return it }
        details(entryId)
        return cachedEpisodes[entryId].orEmpty()
    }

    /**
     * Posts of the series category that the ToC does not list and that are
     * not older than its latest entry. The archive is newest first, so paging
     * stops at the first page that holds no such post.
     */
    private suspend fun fetchNewerPosts(
        categorySlug: String,
        known: List<ChapterData>,
    ): List<ChapterData> {
        var since = ""
        val knownPaths = HashSet<String>()
        known.forEach { ch ->
            knownPaths.add(ch.path)
            if (ch.date != null && ch.date > since) since = ch.date
        }
        if (since.isEmpty()) return emptyList()

        val newer = mutableListOf<ChapterData>()
        for (page in 1..20) {
            val url = absPath("category/$categorySlug/") + (if (page > 1) "page/$page/" else "")
            val html = runCatching { fetch(url) }.getOrNull() ?: break
            val d = Jsoup.parse(html, url)
            var foundNewer = false
            d.select("#main article").forEach { art ->
                val link = art.selectFirst("h2.entry-title a") ?: return@forEach
                val href = link.attr("href")
                val datetime = art.selectFirst("time.entry-date")?.attr("datetime")
                    ?: return@forEach
                val date = datetime.take(10)
                val path = toPath(href)
                if (path.isEmpty()) return@forEach
                if (date >= since && knownPaths.add(path)) {
                    foundNewer = true
                    newer.add(ChapterData(normalize(link.text()), path, date))
                }
            }
            if (!foundNewer || d.selectFirst("#main .nav-previous a") == null) break
        }
        // The archive lists posts newest first, so reversing it yields
        // publishing order without comparing bare dates.
        return newer.reversed()
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val url = absPath(episodeId)
        val firstPage = doc(url)
        val paras = mutableListOf<String>()
        pageParagraphs(firstPage)?.let { paras += it }

        val pageUrls = firstPage.select(".page-links a")
            .mapNotNull { it.absUrl("href").takeIf { h -> h.isNotBlank() } }
            .distinct()
        for (pageUrl in pageUrls) {
            pageParagraphs(doc(pageUrl))?.let { paras += it }
        }
        return paras
    }

    /** Clean entry content of one chapter page. */
    private fun pageParagraphs(d: Document): List<String>? {
        val content = entryContent(d) ?: return null
        content.select(
            "script, style, .sharedaddy, #jp-post-flair, .jp-relatedposts, " +
                ".page-links, #wordads-inline-marker, [id^=atatags]",
        ).remove()
        // "Previous | ToC | Next" bars, as a paragraph or a one-row table.
        val nav = content.select("p, table").filter { el ->
            val text = normalize(el.text())
            text.length < 80 &&
                Regex("\\b(previous|prev)\\b|\\bnext\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                Regex("\\b(toc|contents)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) &&
                el.select("a").isNotEmpty()
        }
        nav.forEach { it.remove() }
        return paragraphsOf(content)
    }

    // ---- helpers -----------------------------------------------------------

    private fun entryContent(d: Document): Element? =
        d.selectFirst("#main .entry-content") ?: d.selectFirst(".entry-content")

    private fun firstImage(content: Element): String? {
        val src = content.select("img").firstOrNull()?.attr("src")?.takeIf { it.isNotBlank() }
            ?: return null
        return if (src.startsWith("//")) "https:$src" else abs(src)
    }

    /**
     * Site-relative path for this site's own posts and absolute `https://`
     * URL for the translator's other blogs.
     */
    private fun toPath(href: String): String {
        var raw = href.trim()
        if (raw.startsWith("//")) raw = "https:$raw"
        if (raw.startsWith("http", true)) {
            val match = Regex("^https?://([^/?#]+)([/?#].*)?$", RegexOption.IGNORE_CASE).find(raw)
                ?: return ""
            var host = match.groupValues[1].lowercase()
            val rest = (match.groupValues[2].ifEmpty { "/" }).split("#").first()
            if (host in siteHosts) return rest
            if (host == deadHost || host == "www.$deadHost") host = deadHostReplacement
            return "https://$host$rest"
        }
        val clean = raw.split("#").first()
        return if (clean.startsWith("/")) clean else "/$clean"
    }

    private fun categorySlug(href: String): String =
        Regex("/category/([^/]+)/?").find(href)?.groupValues?.get(1)?.lowercase().orEmpty()

    /** "Vol N" from the closest "Volume N ..." element above the list. */
    private fun volumeLabel(list: Element): String {
        var label = ""
        var prev = list.previousElementSibling()
        while (prev != null) {
            if (label.isEmpty()) {
                Regex("^Volume\\s*(\\d+)", RegexOption.IGNORE_CASE).find(normalize(prev.text()))
                    ?.let { label = "Vol ${it.groupValues[1]}" }
            }
            prev = prev.previousElementSibling()
        }
        return label
    }

    /** Raw text directly inside a list item, without nested lists and links. */
    private fun ownText(item: Element): String {
        val clone = item.clone()
        clone.select("ul, a").remove()
        return clone.text()
    }

    /**
     * Chapters from the volume lists. Every link inside a top-level list is a
     * chapter; the volume comes from the nearest "Volume N" heading above the
     * list and nested list labels ("Chapter 3") become part of the name.
     */
    private fun parseToc(content: Element?): List<ChapterData> {
        content ?: return emptyList()
        val chapters = mutableListOf<ChapterData>()
        val seen = HashSet<String>()
        content.children().filter { it.tagName() == "ul" }.forEach { list ->
            val volume = volumeLabel(list)
            list.select("a").forEach { anchor ->
                val href = anchor.attr("href")
                if (href.isBlank()) return@forEach
                val path = toPath(href)
                if (path.isEmpty() || !seen.add(path)) return@forEach

                var linkText = normalize(anchor.text())
                val labels = ArrayDeque<String>()
                anchor.parents().filter { it.tagName() == "li" }.forEachIndexed { k, li ->
                    val raw = ownText(li)
                    var own = normalize(raw)
                    if (own.endsWith(":") || own.endsWith("\uff1a")) own = own.dropLast(1)
                    if (own.isEmpty()) return@forEachIndexed
                    // The site sometimes splits one word across the list item
                    // and its link (`<li>C<a>hapter 15</a>`): glue it back.
                    if (k == 0 && raw.isNotEmpty() && !raw.last().isWhitespace() &&
                        linkText.isNotEmpty() && linkText.first() in 'a'..'z'
                    ) {
                        linkText = own + linkText
                        return@forEachIndexed
                    }
                    labels.addFirst(own)
                }
                if (linkText.isNotEmpty() && labels.lastOrNull() != linkText) labels.addLast(linkText)
                if (volume.isNotEmpty()) labels.addFirst(volume)
                chapters.add(ChapterData(labels.joinToString(" - ").ifEmpty { path }, path, dateFromPath(path)))
            }
        }
        return chapters
    }

    private fun dateFromPath(path: String): String? {
        val m = Regex("/(\\d{4})/(\\d{2})/(\\d{2})/").find(path) ?: return null
        return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
    }

    private fun normalize(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

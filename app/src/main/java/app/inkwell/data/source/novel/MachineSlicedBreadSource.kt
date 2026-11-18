package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class MachineSlicedBreadSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "machineslicedbread"
    override val name = "Machine Sliced Bread"
    override val baseUrl = "https://machineslicedbread.xyz"
    override val lang = "en"

    private val siteHost = Regex("^(?:www\\.)?machineslicedbread\\.xyz$", RegexOption.IGNORE_CASE)
    // Pages that are linked from a novel page but are not chapters.
    private val notChapter = Regex("glossary|whats-with-the-passwords", RegexOption.IGNORE_CASE)
    private val directLink = Regex("^\\(?\\s*direct link\\s*\\)?$", RegexOption.IGNORE_CASE)
    private val socialLink = Regex("patreon|discord|ko-fi|syosetu|amazon|twitter|x\\.com", RegexOption.IGNORE_CASE)
    private val placeholderCover = Regex("notavailable", RegexOption.IGNORE_CASE)
    // Credit lines, headings and notes that are not part of a synopsis.
    private val synopsisSkip = Regex(
        "^(?:raw|translat(?:ion|or|ed)|edit(?:or|ed)|proofread|author|autor|chapters?|table of contents|toc|note)\\b",
        RegexOption.IGNORE_CASE,
    )
    // "Previous chapter | TOC | Next chapter" style lines inside chapters.
    private val navSeparator = "[\\s|‹›«»<>\\[\\]()\\-–—·]"
    private val navWord = "(?:(?:previous|prev|next|back)(?:\\s+chapter)?|toc|table of contents|index|top)"
    private val chapterNavigation = Regex(
        "^$navSeparator*(?:$navWord$navSeparator*)+$",
        RegexOption.IGNORE_CASE,
    )
    private val summaryLimit = 2500
    private val maxRestPages = 15

    private data class ListedNovel(
        val path: String,
        val novelName: String,
        val section: String?,
        val status: String?,
    )

    private fun sectionFromHeading(heading: String): String? {
        val text = heading.lowercase()
        return when {
            Regex("hiatus|dropped").containsMatchIn(text) -> "hiatus"
            Regex("completed").containsMatchIn(text) -> "completed"
            Regex("ongoing").containsMatchIn(text) -> "ongoing"
            Regex("caught up").containsMatchIn(text) -> "caught-up"
            Regex("original").containsMatchIn(text) -> "originals"
            else -> null
        }
    }

    private fun statusOfSection(section: String?): String? = when (section) {
        "caught-up", "ongoing" -> "Ongoing"
        "completed" -> "Completed"
        "hiatus" -> "On Hiatus"
        else -> null
    }

    private fun cleanText(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun canonical(path: String): String = path.trimEnd('/').lowercase()

    /** Converts an href into a site-relative path; null for other sites, the home page or anchors. */
    private fun sitePath(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val url = baseUrl.toHttpUrlOrNull()?.resolve(href.trim()) ?: return null
        if (!siteHost.matches(url.host)) return null
        val path = url.encodedPath.replace(Regex("/{2,}"), "/")
        val pageId = url.queryParameter("page_id")
        if (path == "/" && pageId != null) return "/?page_id=$pageId"
        if (path == "/" || !url.query.isNullOrBlank()) return null
        return path
    }

    /**
     * The home page is the whole catalogue: one list per section heading, with
     * only the series that are hosted on this site.
     */
    private suspend fun catalogue(): List<ListedNovel> {
        val d = doc(baseUrl)
        val novels = mutableListOf<ListedNovel>()
        val seen = HashSet<String>()
        var section: String? = null
        for (el in d.selectFirst(".entry-content")?.select("h2, h3, ul").orEmpty()) {
            if (el.tagName() == "h2" || el.tagName() == "h3") {
                section = sectionFromHeading(el.text()) ?: section
                continue
            }
            for (item in el.select("> li")) {
                val link = item.selectFirst("a[href]") ?: continue
                val path = sitePath(link.attr("href")) ?: continue
                val novelName = cleanText(link.text())
                if (novelName.isEmpty() || !seen.add(canonical(path))) continue
                novels += ListedNovel(path, novelName, section, statusOfSection(section))
            }
        }
        return novels
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        // The catalogue is a single page without pagination.
        if (page > 1) return PagedResult(emptyList(), false)
        val items = catalogue().map {
            Entry(id, it.path.trimStart('/'), it.novelName, null, ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty() || page > 1) return PagedResult(emptyList(), false)
        val items = catalogue()
            .filter { novel -> tokens.all { novel.novelName.lowercase().contains(it) } }
            .map { Entry(id, it.path.trimStart('/'), it.novelName, null, ContentType.NOVEL) }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val content = d.selectFirst(".entry-content") ?: org.jsoup.Jsoup.parse("<div class='entry-content'></div>")
        content.select("script, style, .sharedaddy, #jp-post-flair").remove()

        val novelName = cleanText(d.selectFirst("h1.entry-title")?.text() ?: "").ifBlank { "Untitled" }

        val selfPath = canonical(sitePath(absPath(entryId)) ?: absPath(entryId))
        val chapters = mutableListOf<Episode>()
        val seen = HashSet(listOf(selfPath))
        for (link in content.select("a[href]")) {
            val path = sitePath(link.attr("href"))
            if (path == null || notChapter.containsMatchIn(path)) continue
            val key = canonical(path)
            if (!seen.add(key)) continue
            var chapterName = cleanText(link.text())
            // Some lists pair a title link (to an ad redirect) with a "(Direct
            // Link)" to the chapter itself: the title is the previous link.
            if (chapterName.isEmpty() || directLink.matches(chapterName)) {
                chapterName = cleanText(
                    link.parent()?.select("a")?.takeWhile { it !== link }?.lastOrNull()?.text() ?: "",
                )
            }
            if (chapterName.isEmpty() || notChapter.containsMatchIn(chapterName)) continue
            val number = Regex("^(?:chapter\\s+)?(\\d+(?:\\.\\d+)?)\\b", RegexOption.IGNORE_CASE)
                .find(chapterName)?.groupValues?.get(1)?.toFloatOrNull()
            chapters += Episode(path.trimStart('/'), chapterName, number, null)
        }

        if (chapters.isNotEmpty()) {
            val listedPaths = chapters.map { canonical("/" + it.id.trimStart('/')) }.toSet()
            for (chapter in newerChapters(chapters.last())) {
                if (canonical("/" + chapter.id.trimStart('/')) !in listedPaths) chapters += chapter
            }
        }

        // A few series are a single long page of text with updates appended to it.
        val singlePage = chapters.isEmpty() &&
            cleanText(content.text()).length > 8000 &&
            content.select("a[href]").count { !socialLink.containsMatchIn(it.attr("href")) } <= 2
        if (singlePage) {
            chapters += Episode(entryId.trimStart('/'), novelName, null, null)
        }

        // The synopsis is the plain text that comes before the first chapter link.
        val chapterPaths = chapters.map { "/" + it.id.trimStart('/') }.toSet()
        val lines = mutableListOf<String>()
        for (el in content.select("> p, > div")) {
            if (el.select("a[href]").any { chapterPaths.contains(sitePath(it.attr("href"))) }) break
            if (el.select("a[href]").isNotEmpty()) continue
            val line = cleanText(el.text())
            if (line.isEmpty() || synopsisSkip.containsMatchIn(line)) continue
            lines += line
        }
        var summary = ""
        for (line in if (singlePage) emptyList() else lines) {
            if (summary.isNotEmpty() && summary.length + line.length > summaryLimit) break
            summary += (if (summary.isEmpty()) "" else "\n\n") + line
        }

        val author = content.select("p, div, li").asSequence()
            .flatMap { it.text().split('\n').asSequence() }
            .map { it.trim() }
            .firstOrNull { Regex("^\\s*Aut(?:h)?or\\s*:\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE).matches(it) }
            ?.let { Regex("^\\s*Aut(?:h)?or\\s*:\\s*(.+?)\\s*$", RegexOption.IGNORE_CASE).find(it)!!.groupValues[1].trim() }

        val coverSrc = content.select("img[src]").map { it.attr("src") }
            .firstOrNull { !placeholderCover.containsMatchIn(it) && !it.startsWith("data:") }

        val listed = runCatching {
            catalogue().firstOrNull { canonical(it.path) == canonical("/" + entryId.trimStart('/')) }
        }.getOrNull()

        return EntryDetails(
            entry = Entry(id, entryId.trimStart('/'), novelName, abs(coverSrc), ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = emptyList(),
            status = listed?.status,
        ).also { cachedChapters[entryId] = chapters }
    }

    /**
     * Some contents pages are not kept up to date. Newer chapters follow the
     * same slug pattern as the last listed one, so they are looked up through
     * the site's WordPress REST API and appended in order.
     */
    private suspend fun newerChapters(last: Episode): List<Episode> {
        val lastSlug = last.id.trimStart('/').trim('/').split('/').lastOrNull().orEmpty()
        val match = Regex("^(.*-)(\\d+)/?$").find(lastSlug) ?: return emptyList()
        val (prefixRaw, lastNumberRaw) = match.destructured
        val found = LinkedHashMap<Int, Episode>()
        runCatching {
            for (page in 1..maxRestPages) {
                val url = absPath("wp-json/wp/v2/pages") +
                    "?search=" + java.net.URLEncoder.encode(prefixRaw.replace('-', ' '), "UTF-8") +
                    "&per_page=100&page=$page&orderby=date&order=desc&_fields=link,date,title"
                val items = runCatching { json(url).jsonArray }.getOrNull() ?: break
                var reachedListed = false
                for (item in items) {
                    val o = item.jsonObject
                    val path = sitePath(o["link"]?.jsonPrimitive?.content)
                    val slug = path?.trim('/')?.split('/')?.lastOrNull().orEmpty()
                    if (path == null || !slug.startsWith(prefixRaw)) continue
                    val number = slug.removePrefix(prefixRaw).toIntOrNull() ?: continue
                    if (number <= lastNumberRaw.toInt()) {
                        reachedListed = true
                        continue
                    }
                    val title = cleanText(Jsoup.parse(o["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content ?: "").text())
                    var name = Regex("^.*?(?=chapter\\s+\\d)", RegexOption.IGNORE_CASE).replace(title, "")
                    if (name.startsWith("chapter")) name = "Chapter" + name.substring("chapter".length)
                    found[number] = Episode(
                        id = path.trimStart('/'),
                        name = name.ifBlank { "Chapter $number" },
                        number = number.toFloat(),
                        dateMillis = parseDate(o["date"]?.jsonPrimitive?.content, "yyyy-MM-dd'T'HH:mm:ss"),
                    )
                }
                if (reachedListed || items.size < 100) break
            }
        }
        return found.keys.sorted().mapNotNull { found[it] }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId] ?: emptyList()
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val text = readChapter(episodeId, followRedirect = true)
        return text ?: emptyList()
    }

    private suspend fun readChapter(chapterPath: String, followRedirect: Boolean): List<String>? {
        val d = doc(absPath(chapterPath))
        if (d.selectFirst("form.post-password-form") != null) return emptyList()

        val content = d.selectFirst(".entry-content") ?: return emptyList()
        content.select(
            "script, style, noscript, iframe, form, nav, .tap-nav, .sharedaddy, " +
                "#jp-post-flair, .wpcnt, .adsbygoogle, .post-navigation, .nav-links",
        ).remove()
        // Drop "Previous | TOC | Next" lines.
        content.select("p, div").forEach { el ->
            val text = cleanText(el.text())
            if (text.isNotEmpty() && text.length < 60 && chapterNavigation.matches(text)) el.remove()
        }

        if (followRedirect && cleanText(content.text()).length < 100) {
            // Some chapter entries are redirect pages that only link to the
            // real chapter: follow that link once.
            val selfKey = canonical(sitePath(absPath(chapterPath)) ?: absPath(chapterPath))
            val target = content.select("a[href]")
                .map { sitePath(it.attr("href")) }
                .firstOrNull { path -> path != null && canonical(path) != selfKey }
            if (target != null) return readChapter(target, followRedirect = false)
        }
        if (cleanText(content.text()).length < 100) return emptyList()
        return paragraphsOf(content)
    }
}

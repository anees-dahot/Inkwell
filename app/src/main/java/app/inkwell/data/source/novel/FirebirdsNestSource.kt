package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Port of LNReader's `firebirdsnest` plugin. Firebird's Nest is a small
 * WordPress.com-hosted fan translation site where each novel is a WP page
 * with a hand-maintained ToC; the complete chapter index lives in per-novel
 * tag archives of chapter announcement posts.
 */
class FirebirdsNestSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "firebirdsnest"
    override val name = "Firebird's Nest"
    override val baseUrl = "https://firebirdsnest.org"
    override val lang = "en"

    private data class TagPost(val path: String, val slug: String, val title: String, val date: String)
    private data class TagArchive(val tagSlug: String, val posts: List<TagPost>)
    private data class TocEntry(val path: String, val name: String)

    private val sameSiteHosts = listOf("firebirdsnest.org", "www.firebirdsnest.org", "firebirdsnest.wordpress.com")
    private val nonNovelPaths = setOf("projects/", "about/")

    // ---- helpers -----------------------------------------------------------

    /** Site-relative path (no leading slash, trailing slash) or an external URL. */
    private fun toPath(href: String): String {
        var raw = href.trim()
        if (raw.startsWith("//")) raw = "https:$raw"
        if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val m = Regex("^https?://([^/?#]+)([/?#].*)?$", RegexOption.IGNORE_CASE).find(raw) ?: return ""
            val host = m.groupValues[1].lowercase()
            if (host !in sameSiteHosts) return raw.substringBefore('#')
            raw = m.groupValues[2].ifEmpty { "/" }
        }
        val clean = raw.substringBefore('#').substringBefore('?')
        if (clean.isEmpty()) return ""
        val slash = if (clean.startsWith("/")) clean else "/$clean"
        val path = slash.trimStart('/')
        return if (path.endsWith("/")) path else "$path/"
    }

    private fun normalizeText(text: String): String =
        text.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun lastSegment(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.lastOrNull().orEmpty()

    private fun lastNumber(value: String): Int =
        Regex("\\d+").findAll(value).lastOrNull()?.value?.toIntOrNull() ?: 0

    private fun entryContent(d: Document): Element? =
        d.selectFirst("#main .entry-content") ?: d.selectFirst(".entry-content")

    private suspend fun fetchHtmlOr404(url: String): String? = withContext(Dispatchers.IO) {
        client.newCall(request(url)).execute().use { r ->
            if (r.code == 404) return@use null
            check(r.isSuccessful) { "$name HTTP ${r.code} while fetching $url" }
            r.body?.string().orEmpty()
        }
    }

    // ---- listing -----------------------------------------------------------

    private suspend fun fetchNovelCatalog(): List<Entry> {
        val d = doc(absPath(""))
        val novels = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        for (a in d.select("#site-navigation .sub-menu a")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val path = toPath(href)
            if (path.isEmpty() || path in nonNovelPaths || !seen.add(path)) continue
            val novelName = normalizeText(a.text())
            if (novelName.isEmpty()) continue
            novels += Entry(id, path, novelName, null, ContentType.NOVEL)
        }
        check(novels.isNotEmpty()) { "Firebird's Nest: no novels found in the site menu" }
        return novels
    }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(fetchNovelCatalog(), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val catalog = fetchNovelCatalog()
        val url = absPath("").toHttpUrl().newBuilder()
            .addQueryParameter("s", query)
            .apply { if (page > 1) addQueryParameter("paged", page.toString()) }
            .build()
        val d = doc(url)

        val slugByName = LinkedHashMap<String, String>()
        for (novel in catalog) slugByName[lastSegment(novel.id)] = novel.title
        val novelSlugs = slugByName.keys.toList()

        val results = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        for (a in d.select("h2.entry-title > a")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val slug = novelSlugForResult(toPath(href), novelSlugs) ?: continue
            if (!seen.add(slug)) continue
            results += Entry(id, "$slug/", slugByName[slug] ?: slug, null, ContentType.NOVEL)
        }
        return PagedResult(results, results.isNotEmpty())
    }

    private fun novelSlugForResult(path: String, novelSlugs: List<String>): String? {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return null
        if (segments.size == 4 && Regex("^\\d{4}$").matches(segments[0])) {
            val slug = segments[3]
            return novelSlugs.firstOrNull { slug.startsWith("$it-") }
        }
        if (segments[0] in novelSlugs) return segments[0]
        if (segments.size > 1 && segments[1] in novelSlugs) return segments[1]
        return null
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val path = toPath(entryId)
        val d = doc(absPath(path))
        val content = entryContent(d)
            ?: throw IllegalStateException("Firebird's Nest: no entry content at $path")
        val novelName = normalizeText(d.selectFirst("#main h1.entry-title")?.text().orEmpty())
        check(novelName.isNotEmpty()) { "Firebird's Nest: missing novel title at $path" }
        val novelSlug = lastSegment(path)

        val meta = applyMetadata(content)
        val tocEntries = parseToc(content)

        val archive = fetchTagArchive(novelSlug)
        val annDates = HashMap<String, String>()
        archive?.posts?.forEach { annDates[it.slug] = it.date }

        val chapters = mutableListOf<Episode>()
        val used = mutableSetOf<String>()
        for (entry in tocEntries) {
            if (!used.add(entry.path)) continue
            val date = announcementDateFor(entry.path, novelSlug, archive, annDates)
            chapters += Episode(
                id = entry.path,
                name = entry.name,
                number = null,
                dateMillis = date?.let { parseDate(it, "yyyy-MM-dd") },
            )
        }

        if (archive != null) {
            val extras = resolveAnnouncementChapters(archive, novelSlug, path)
                .sortedWith(compareBy({ it.post.date }, { lastNumber(it.post.slug) }))
            for (candidate in extras) {
                if (!used.add(candidate.path)) continue
                chapters += Episode(
                    id = candidate.path,
                    name = candidate.post.title,
                    number = null,
                    dateMillis = parseDate(candidate.post.date, "yyyy-MM-dd"),
                )
            }
        }

        check(chapters.isNotEmpty()) { "Firebird's Nest: no chapters found for $path" }
        val numbered = chapters.mapIndexed { index, ep -> ep.copy(number = (index + 1).toFloat()) }

        return EntryDetails(
            entry = Entry(id, path, novelName, null, ContentType.NOVEL),
            description = meta.summary,
            authors = listOfNotNull(meta.author),
            genres = emptyList(),
            status = meta.status,
        ).also { cachedChapters[path] = numbered }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val path = toPath(entryId)
        cachedChapters[path]?.let { return it }
        details(path)
        return cachedChapters[path].orEmpty()
    }

    private data class Meta(val author: String?, val status: String?, val summary: String?)

    private fun applyMetadata(content: Element): Meta {
        var author: String? = null
        var status: String? = null
        val raw = content.html()
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
        val flattened = Jsoup.parse("<div>$raw</div>").selectFirst("div")?.text().orEmpty()
        for (line in flattened.split('\n').map { normalizeText(it) }.filter { it.isNotEmpty() }) {
            Regex("^Author:\\s*(.+)$", RegexOption.IGNORE_CASE).find(line)?.let { author = normalizeText(it.groupValues[1]) }
            Regex("^Status:\\s*(.+)$", RegexOption.IGNORE_CASE).find(line)?.let { status = parseStatus(it.groupValues[1]) }
        }

        val paragraphs = mutableListOf<String>()
        var reachedToc = false
        for (el in content.select("p, strong, h1, h2, h3, h4, h5, h6")) {
            if (reachedToc) continue
            val text = normalizeText(el.text())
            if (isTocMarker(el, text)) { reachedToc = true; continue }
            if (el.tagName() != "p") continue
            if (el.select("a").isNotEmpty() || Regex("^Author:", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
                Regex("^Status:", RegexOption.IGNORE_CASE).containsMatchIn(text) || text.isEmpty()
            ) continue
            paragraphs += text
        }
        return Meta(author, status, if (paragraphs.isEmpty()) null else paragraphs.joinToString("\n\n"))
    }

    private fun parseStatus(value: String): String? {
        val normalized = value.lowercase()
        return when {
            normalized.startsWith("ongoing") -> "Ongoing"
            normalized.startsWith("completed") -> "Completed"
            "hiatus" in normalized -> "On Hiatus"
            else -> null
        }
    }

    private fun isTocMarker(element: Element, text: String): Boolean =
        if (element.tagName() == "p") {
            Regex("^table of contents", RegexOption.IGNORE_CASE).containsMatchIn(text)
        } else {
            Regex("(table of )?contents$", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

    private fun parseToc(content: Element): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        val seen = mutableSetOf<String>()
        var reachedToc = false
        for (el in content.select("a, p, strong, h1, h2, h3, h4, h5, h6")) {
            if (!reachedToc) {
                if (el.tagName() == "a") continue
                if (isTocMarker(el, normalizeText(el.text()))) reachedToc = true
                continue
            }
            if (el.tagName() != "a") continue
            val href = el.attr("href")
            if (href.isEmpty()) continue
            val path = toPath(href)
            val name = normalizeText(el.text())
            if (path.isEmpty() || name.isEmpty() || !seen.add(path) || path.contains("/feed")) continue
            entries += TocEntry(path, name)
        }
        return entries
    }

    private suspend fun fetchTagArchive(novelSlug: String): TagArchive? {
        val candidates = mutableListOf(novelSlug)
        val firstWord = novelSlug.split('-').first()
        if (firstWord.isNotEmpty() && firstWord != novelSlug) candidates += firstWord
        for (tagSlug in candidates) {
            val firstHtml = fetchHtmlOr404(absPath("tag/$tagSlug/")) ?: continue
            val first = parseTagPage(firstHtml)
            val posts = first.posts.toMutableList()
            for (pageNo in 2..first.maxPage) {
                posts += parseTagPage(fetchHtmlOr404(absPath("tag/$tagSlug/page/$pageNo/")) ?: continue).posts
            }
            return TagArchive(tagSlug, posts)
        }
        return null
    }

    private fun parseTagPage(html: String): TagPage {
        val d = Jsoup.parse(html)
        val posts = mutableListOf<TagPost>()
        for (a in d.select("#main h2.entry-title > a")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val path = toPath(href)
            val match = Regex("^(\\d{4})/(\\d{2})/(\\d{2})/([^/]+)/$").find(path) ?: continue
            posts += TagPost(path, match.groupValues[4], normalizeText(a.text()),
                "${match.groupValues[1]}-${match.groupValues[2]}-${match.groupValues[3]}")
        }
        var maxPage = 1
        for (a in d.select("#main .nav-links a")) {
            val match = Regex("/page/(\\d+)/").find(a.attr("href"))
            match?.groupValues?.get(1)?.toIntOrNull()?.let { if (it > maxPage) maxPage = it }
        }
        return TagPage(posts, maxPage)
    }

    private data class TagPage(val posts: List<TagPost>, val maxPage: Int)

    private data class ChapterCandidate(val post: TagPost, val path: String)

    private suspend fun resolveAnnouncementChapters(
        archive: TagArchive,
        novelSlug: String,
        novelPath: String,
    ): List<ChapterCandidate> {
        val prefix = archive.tagSlug + "-"
        val resolved = mutableListOf<ChapterCandidate>()
        val unresolved = mutableListOf<TagPost>()
        for (post in archive.posts) {
            if (!post.slug.startsWith(prefix)) continue
            val rest = post.slug.substring(prefix.length)
            val direct = deriveChapterPath(rest, novelSlug, post.path)
            if (direct != null) resolved += ChapterCandidate(post, direct) else unresolved += post
        }
        for (post in unresolved) {
            resolveAnnouncementPath(post, novelPath)?.let { resolved += ChapterCandidate(post, it) }
        }
        return resolved
    }

    private fun deriveChapterPath(rest: String, novelSlug: String, postPath: String): String? {
        if (Regex("^ch-\\d+(-\\d+)?$").matches(rest)) return postPath
        Regex("^v(\\d+)ch(\\d+)$").find(rest)?.let {
            return "$novelSlug/v${it.groupValues[1]}-ch${it.groupValues[2]}/"
        }
        if (Regex("^(v\\d+c\\d+|ch\\d+)(-\\d+)?$").matches(rest)) return "$novelSlug/$rest/"
        return null
    }

    private suspend fun resolveAnnouncementPath(post: TagPost, novelPath: String): String? {
        val d = doc(absPath(post.path))
        val content = entryContent(d) ?: return null
        val prefix = if (novelPath.endsWith("/")) novelPath else "$novelPath/"
        for (a in content.select("a")) {
            val href = a.attr("href")
            if (href.isEmpty()) continue
            val path = toPath(href)
            if (path.startsWith(prefix) && path != novelPath && !path.contains("/feed")) return path
        }
        val text = normalizeText(content.text())
        return if (text.length >= 200) post.path else null
    }

    private fun announcementDateFor(
        chapterPath: String,
        novelSlug: String,
        archive: TagArchive?,
        annDates: Map<String, String>,
    ): String? {
        if (chapterPath.startsWith("http") || archive == null) return null
        val base = lastSegment(chapterPath)
        val compact = base.replace(Regex("^v(\\d+)-ch"), "v$1ch")
        val keys = listOf(
            base, compact,
            "$novelSlug-$base", "$novelSlug-$compact",
            "${archive.tagSlug}-$base", "${archive.tagSlug}-$compact",
        )
        return keys.firstOrNull { annDates.containsKey(it) }?.let { annDates[it] }
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val path = toPath(episodeId)
        val url = if (path.startsWith("http")) path else absPath(path)
        val d = doc(url)
        val content = entryContent(d)
            ?: throw IllegalStateException("Firebird's Nest: no chapter content at $path")
        content.select("#jp-post-flair, .sharedaddy, .jp-relatedposts").remove()
        for (p in content.select("p").toList()) {
            val text = normalizeText(p.text())
            if (text.length < 60 && text.contains('|') &&
                Regex("(TOC|Contents)", RegexOption.IGNORE_CASE).containsMatchIn(text)
            ) p.remove()
        }
        return paragraphsOf(content)
    }
}

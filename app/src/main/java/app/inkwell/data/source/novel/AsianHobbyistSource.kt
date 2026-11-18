package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Port of LNReader's Asian Hobbyist plugin. The home page lists series by
 * latest chapter; ranking and search go through the site's AJAX endpoint,
 * which needs the token every page carries in `<meta name="enc">`.
 */
class AsianHobbyistSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "asianhobbyist"
    override val name = "Asian Hobbyist"
    override val baseUrl = "https://www.asianhobbyist.com"
    override val lang = "en"

    private val siteHost = Regex("^(?:www\\.)?asianhobbyist\\.com$", RegexOption.IGNORE_CASE)

    /** The theme's own image for series that have no cover. */
    private val placeholderCover = Regex("/assets/images/404\\.jpg$", RegexOption.IGNORE_CASE)

    /** Parts of a chapter that are advertising, donation requests or widgets. */
    private val chapterJunk =
        "script, style, noscript, iframe, form, .code-block, .adsbygoogle, #vuukle-quiz, [id^=\"vuukle\"]"

    // ---- requests ----------------------------------------------------------

    private suspend fun fetchDoc(url: String): Document? = withContext(Dispatchers.IO) {
        client.newCall(request(url)).execute().use { r ->
            if (r.code == 404) return@withContext null
            check(r.isSuccessful) { "$name HTTP ${r.code}" }
            Jsoup.parse(r.body?.string().orEmpty(), url)
        }
    }

    private suspend fun ajax(action: String, src: String): Document {
        val home = doc(baseUrl)
        val enc = home.selectFirst("meta[name=\"enc\"]")?.attr("content")
            ?: throw IllegalStateException("$name: search token not found.")
        return formPost(
            absPath("wp-admin/admin-ajax.php"),
            mapOf("action" to action, "enc" to enc, "src" to src),
        )
    }

    // ---- helpers -----------------------------------------------------------

    private fun cleanText(text: String) = text.replace(Regex("\\s+"), " ").trim()

    /**
     * Converts an href into a site-relative path (`series/slug/`). Returns
     * null for links to other sites, anchors and the home page.
     */
    private fun sitePath(href: String?): String? {
        if (href.isNullOrEmpty()) return null
        val url = abs(href)?.toHttpUrlOrNull() ?: return null
        if (!siteHost.matches(url.host) || url.encodedPath == "/") return null
        return url.encodedPath.trimStart('/')
    }

    /**
     * Some covers still point at the old http://asianhobbyist.com host, and
     * series without a cover show the theme's own placeholder image.
     */
    private fun coverUrl(src: String?): String? {
        if (src.isNullOrEmpty() || src.startsWith("data:") || placeholderCover.containsMatchIn(src)) {
            return null
        }
        val url = abs(src)?.toHttpUrlOrNull() ?: return null
        return if (siteHost.matches(url.host)) {
            url.newBuilder().scheme("https").host("www.asianhobbyist.com").build().toString()
        } else {
            url.toString()
        }
    }

    /** Images are lazy loaded: the real address is in data-src. */
    private fun imageOf(img: Element?): String? =
        coverUrl(img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> = listingPage(page)

    override suspend fun latest(page: Int): PagedResult<Entry> = listingPage(page)

    /** The home page lists series by their latest chapter, nine per page. */
    private suspend fun listingPage(pageNo: Int): PagedResult<Entry> {
        val url = if (pageNo > 1) absPath("page/$pageNo") + "/" else "$baseUrl/"
        val d = fetchDoc(url) ?: return PagedResult(emptyList(), false)
        val novels = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        for (item in d.select("ul.series > li.item")) {
            val link = item.selectFirst(".title a") ?: continue
            val path = sitePath(link.attr("href")) ?: continue
            if (!path.startsWith("series/") || !seen.add(path)) continue
            val name = cleanText(link.text())
            if (name.isEmpty()) continue
            novels.add(Entry(id, path, name, imageOf(item.selectFirst("img")), ContentType.NOVEL))
        }
        return PagedResult(novels, novels.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        // The site's search box shows a single list of matching series.
        val term = query.trim()
        if (page > 1 || term.length < 3) return PagedResult(emptyList(), false)
        val d = ajax("gsr", term)
        val novels = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        for (item in d.select("li")) {
            val link = item.selectFirst(".title a") ?: continue
            val path = sitePath(link.attr("href")) ?: continue
            // The visible text is re-cased around the match; title holds the real name.
            val name = cleanText(link.attr("title")?.takeIf { it.isNotBlank() } ?: link.text())
            if (name.isEmpty() || !seen.add(path)) continue
            novels.add(Entry(id, path, name, imageOf(item.selectFirst("img")), ContentType.NOVEL))
        }
        return PagedResult(novels, false)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val d = fetchDoc(absPath(entryId)) ?: throw IllegalStateException("$name: novel not found")
        val name = cleanText(d.selectFirst(".info h1.entry-title")?.text().orEmpty())
            .ifBlank { cleanText(d.selectFirst("h1.entry-title")?.text().orEmpty()) }
            .ifBlank { "Untitled" }
        val summary = d.selectFirst(".info .description")?.text()
            ?.replace(Regex("[ \t]+"), " ")?.trim()

        val chapters = mutableListOf<Episode>()
        for (row in d.select(".releases .tableBody .row")) {
            val link = row.selectFirst("a[href]") ?: continue
            val path = sitePath(link.attr("href")) ?: continue
            val chapterName = cleanText(link.text())
            if (chapterName.isEmpty()) continue
            val number = Regex("(\\d+(?:\\.\\d+)?)").find(chapterName)?.groupValues?.get(1)?.toFloatOrNull()
            chapters.add(Episode(path, chapterName, number, null))
        }

        return EntryDetails(
            entry = Entry(id, entryId, name, imageOf(d.selectFirst(".info .thumb img")), ContentType.NOVEL),
            description = summary?.takeIf { it.isNotEmpty() },
            authors = emptyList(),
            genres = emptyList(),
            status = null,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter text ------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = fetchDoc(absPath(episodeId)) ?: return emptyList()
        val content = d.selectFirst(".entry-content") ?: return emptyList()
        content.select(chapterJunk).remove()
        return paragraphsOf(content)
    }
}

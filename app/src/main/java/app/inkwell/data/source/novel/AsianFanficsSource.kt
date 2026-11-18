package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Port of LNReader's Asianfanfics plugin. Listings, story details and chapter
 * text are HTMX fragments the pages load after render; this fetches those
 * fragments directly. Members-only chapters surface a notice (plus teaser).
 */
class AsianFanficsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "asianfanfics"
    override val name = "Asianfanfics"
    override val baseUrl = "https://www.asianfanfics.com"
    override val lang = "en"

    // ---- requests ----------------------------------------------------------

    private suspend fun fetchHtml(url: String, referer: String? = null): Document =
        withContext(Dispatchers.IO) {
            val rb = request(url).newBuilder()
            referer?.let { rb.header("Referer", it) }
            client.newCall(rb.build()).execute().use { r ->
                check(r.isSuccessful) { "Could not reach $url (${r.code})" }
                Jsoup.parse(r.body?.string().orEmpty(), url)
            }
        }

    // ---- listing -----------------------------------------------------------

    private fun parseNovels(d: Document): List<Entry> =
        d.select("article").mapNotNull { art ->
            val link = art.selectFirst("h3 a[href*=\"/story/view/\"]")
                ?.takeIf { it.hasAttr("href") } ?: return@mapNotNull null
            val href = link.attr("href")
            Entry(
                id,
                href.trimStart('/'),
                link.text().trim().ifBlank { "Untitled" },
                abs(art.selectFirst("img")?.attr("src")),
                ContentType.NOVEL,
            )
        }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val items = parseNovels(fetchHtml(absPath("htmx/browse/en/trending") + "?page=$page"))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        val items = parseNovels(fetchHtml(absPath("htmx/browse/en/latest") + "?page=$page"))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val qs = "q=" + URLEncoder.encode(query, "UTF-8") + "&page=$page"
        val items = parseNovels(
            fetchHtml(absPath("htmx/browse/search") + "?$qs", absPath("browse/search") + "?$qs"),
        )
        return PagedResult(items, items.isNotEmpty())
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val url = absPath(entryId)
        val d = fetchHtml(url)
        val header = d.selectFirst("main header")
            ?: throw IllegalStateException("$name: unexpected story page layout")

        header.select("h1 span").remove()
        val title = header.selectFirst("h1")?.text()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: "Untitled"
        val cover = header.selectFirst("img")?.attr("src")
        val author = header.selectFirst("a[href^=\"/profile/\"]")?.text()?.trim()
        val genres = header.select("a[href^=\"/browse/tag/\"]").eachText().map { it.trim() }
        val status = if (header.select("span").any { it.text().trim().lowercase() == "completed" }) {
            "Completed"
        } else {
            "Ongoing"
        }

        var summary: String? = null
        val descriptionPath = d.selectFirst("#bodyText [hx-get^=\"/htmx/story/\"]")?.attr("hx-get")
        if (!descriptionPath.isNullOrEmpty()) {
            val description = fetchHtml(absPath(descriptionPath.trimStart('/')), url)
            summary = description.selectFirst("#story-description")?.text()?.trim()
        }

        // The TOC is rendered twice (sidebar + mobile sheet); entry 0 is the
        // foreword, i.e. the story page itself.
        val chapters = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (el in d.select("a[data-toc-chapter]")) {
            if (el.attr("data-toc-chapter") == "0") continue
            val href = el.attr("href").takeIf { it.isNotEmpty() } ?: continue
            if (!seen.add(href)) continue
            val number = el.select("span").first()?.text()?.trim()?.toIntOrNull()
                ?: (chapters.size + 1)
            chapters.add(
                Episode(
                    id = href.trimStart('/'),
                    name = el.selectFirst("span.truncate")?.text()?.trim().orEmpty(),
                    number = number.toFloat(),
                    dateMillis = null,
                ),
            )
        }

        return EntryDetails(
            entry = Entry(id, entryId, title, abs(cover), ContentType.NOVEL),
            description = summary?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = genres,
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter text ------------------------------------------------------

    /**
     * Returns the fragment's chapter body without scripts, stylesheets, inline
     * styles, event handlers or javascript: URLs. The body is author-written,
     * so inline styles could otherwise lay content over the reader.
     */
    private fun cleanContent(fragment: Document): Element? {
        val content = fragment.selectFirst("#user-submitted-body") ?: return null
        content.select("script, style, link, meta, iframe, object, embed, form").remove()
        content.select("[style]").removeAttr("style")
        for (el in content.select("*")) {
            for (attr in el.attributes().asList().toList()) {
                val key = attr.key
                if (key.lowercase().startsWith("on") || attr.value.trim().lowercase().startsWith("javascript:")) {
                    el.removeAttr(key)
                }
            }
        }
        return content
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val url = absPath(episodeId)
        val d = fetchHtml(url)
        val body = d.selectFirst("#bodyText") ?: return emptyList()

        val contentPath = body.selectFirst("[hx-get^=\"/htmx/chapter/\"]")?.attr("hx-get")
        if (!contentPath.isNullOrEmpty()) {
            val chapter = fetchHtml(absPath(contentPath.trimStart('/')), url)
            return paragraphsOf(cleanContent(chapter))
        }

        // Members-only, subscribers-only and friends-only chapters show a
        // notice and, sometimes, a public teaser instead of the chapter text.
        val notice = body.selectFirst("div.border-dashed:not(#content-load-error)")?.text()?.trim()
            ?.takeUnless { it.isEmpty() } ?: "This chapter is not publicly available."
        val paragraphs = mutableListOf(
            "$notice This chapter requires an Asianfanfics account and cannot be read in this plugin.",
        )

        val teaserPath = body.selectFirst("[hx-get^=\"/htmx/teaser/\"]")?.attr("hx-get")
        if (!teaserPath.isNullOrEmpty()) {
            val teaser = paragraphsOf(cleanContent(fetchHtml(absPath(teaserPath.trimStart('/')), url)))
            if (teaser.isNotEmpty()) {
                paragraphs.add("Teaser:")
                paragraphs.addAll(teaser)
            }
        }
        return paragraphs
    }
}

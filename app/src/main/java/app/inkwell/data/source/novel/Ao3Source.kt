package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Port of LNReader's Archive Of Our Own plugin. AO3 search/browse runs through
 * `works/search`; novel pages get `view_adult=true` and chapter lists come
 * from the `…/navigate` page. Every request carries `view_adult=true` cookie.
 */
class Ao3Source(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "archiveofourown"
    override val name = "Archive Of Our Own"
    override val baseUrl = "https://archiveofourown.org"
    override val lang = "en"

    override val headers = mapOf("Cookie" to "view_adult=true")

    // ---- requests ----------------------------------------------------------

    private suspend fun searchHtml(url: String): Document {
        val body = fetch(url)
        if (body.contains("Retry later")) {
            throw IllegalStateException("AO3 rate limit: request timed out by server (Retry later)")
        }
        return Jsoup.parse(body, url)
    }

    // ---- listing -----------------------------------------------------------

    private fun parseNovels(d: Document): List<Entry> =
        d.select("li.work").mapNotNull { el ->
            val a = el.selectFirst("h4.heading > a") ?: return@mapNotNull null
            val href = a.attr("href").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Entry(id, href.trimStart('/'), a.text().trim().ifBlank { "Untitled" }, null, ContentType.NOVEL)
        }

    private suspend fun worksSearch(page: Int, sortColumn: String): PagedResult<Entry> {
        val url = absPath("works/search").toHttpUrl().newBuilder()
            .addQueryParameter("commit", "Search")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("work_search[language_id]", "en")
            .addQueryParameter("work_search[sort_column]", sortColumn)
            .addQueryParameter("work_search[sort_direction]", "desc")
            .build()
        val items = parseNovels(searchHtml(url.toString()))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = worksSearch(page, "hits")

    override suspend fun latest(page: Int) = worksSearch(page, "revised_at")

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val url = absPath("works/search").toHttpUrl().newBuilder()
            .addQueryParameter("commit", "Search")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("work_search[language_id]", "en")
            .addQueryParameter("work_search[query]", query)
            .build()
        val items = parseNovels(searchHtml(url.toString()))
        return PagedResult(items, items.isNotEmpty())
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    private fun tagList(d: Document, css: String) =
        d.select(css).eachText().map { it.trim() }.filter { it.isNotEmpty() }

    override suspend fun details(entryId: String): EntryDetails {
        val novelUrl = absPath(entryId).toHttpUrl().newBuilder()
            .addQueryParameter("view_adult", "true").build()
        val navUrl = absPath(entryId.trimEnd('/') + "/navigate").toHttpUrl().newBuilder()
            .addQueryParameter("view_adult", "true").build()

        val d = searchHtml(novelUrl.toString())
        val nav = searchHtml(navUrl.toString())

        val title = d.selectFirst("h2.title")?.text()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: "Untitled"
        val status = if (d.selectFirst("dt.status")?.text()?.contains("Updated") == true) "Ongoing" else "Completed"
        val authors = tagList(d, "a[rel=\"author\"]")
        val genres = tagList(d, "dd.freeform.tags li a.tag")

        val summary = d.selectFirst("blockquote.userstuff")?.text()?.trim().orEmpty()
        val fandom = tagList(d, "dd.fandom.tags li a.tag").joinToString(",")
        val rating = tagList(d, "dd.rating.tags li a.tag").joinToString(",")
        val warning = tagList(d, "dd.warning.tags li a.tag").joinToString(",")
        val series = tagList(d, "dd.series li a.tag").joinToString(",")
        val relation = tagList(d, "dd.relationship.tags li a.tag").joinToString(",")
        val character = tagList(d, "dd.character.tags li a.tag").joinToString(",")
        val stats = tagList(d, "dd.stats li a.tag").joinToString(",")
        val description = "Fandom:\n$fandom\n\nRating:\n$rating\n\nWarning:\n$warning\n\nSummary:\n$summary\n\n" +
            "Series:\n$series\n\nRelationships:\n$relation\n\nCharacters:\n$character\n\nStats:\n$stats"

        val chapters = mutableListOf<Episode>()
        for ((index, el) in nav.select("ol.index li").withIndex()) {
            val a = el.selectFirst("a") ?: continue
            val href = a.attr("href").trim().takeIf { it.isNotEmpty() } ?: continue
            val releaseTimeText = el.selectFirst("span.datetime")?.text()
                ?.replace(Regex("[()]"), "")?.trim()
            chapters.add(
                Episode(
                    id = href.trimStart('/'),
                    name = a.text().trim().takeIf { it.isNotEmpty() } ?: "Chapter ${index + 1}",
                    number = null,
                    dateMillis = parseDate(releaseTimeText, "yyyy-MM-dd", "MMM d, yyyy", "EEE, dd MMM yyyy", "dd/MM/yyyy"),
                ),
            )
        }
        // Single-chapter works have no navigate list: the work itself is the chapter.
        if (chapters.isEmpty()) {
            val published = d.selectFirst(".wrapper dd.published")?.text()?.trim()
            chapters.add(
                Episode(entryId, title, null, parseDate(published, "yyyy-MM-dd", "MMM d, yyyy")),
            )
        }

        return EntryDetails(
            entry = Entry(id, entryId, title, null, ContentType.NOVEL),
            description = description,
            authors = authors,
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

    override suspend fun chapterText(episodeId: String): List<String> {
        val url = absPath(episodeId).toHttpUrl().newBuilder()
            .addQueryParameter("view_adult", "true").build()
        val d = searchHtml(url.toString())
        d.select("h3.landmark.heading#work").remove()
        val content = d.selectFirst("div#chapters > div") ?: d.selectFirst("div#chapters")
        return paragraphsOf(content)
    }
}

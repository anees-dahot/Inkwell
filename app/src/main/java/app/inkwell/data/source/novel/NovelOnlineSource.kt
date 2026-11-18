package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/**
 * Port of LNReader's novelsOnline plugin. Listings are plain HTML; the
 * detailed search is a form POST to `/detailed-search`.
 */
class NovelOnlineSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "NO.net"
    override val name = "novelsOnline"
    override val baseUrl = "https://novelsonline.org"
    override val lang = "en"

    // ---- listing -----------------------------------------------------------

    private fun parseNovels(d: Document): List<Entry> =
        d.select(".top-novel-block").mapNotNull { el ->
            val url = el.selectFirst("h2 a")?.attr("href")
                ?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Entry(
                sourceId = id,
                id = url.removePrefix("$baseUrl/").trimStart('/'),
                title = el.selectFirst("h2")?.text()?.trim()
                    .takeUnless { it.isNullOrEmpty() } ?: "Untitled",
                coverUrl = abs(el.selectFirst(".top-novel-cover img")?.attr("src")),
                type = ContentType.NOVEL,
            )
        }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val items = parseNovels(doc(absPath("top-novel/$page")))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int): PagedResult<Entry> =
        popular(page) // the site exposes a single "top novels" ordering

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val items = parseNovels(
            formPost(absPath("detailed-search"), mapOf("keyword" to query, "search" to "1")),
        )
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        var summary: String? = null
        val genres = mutableListOf<String>()
        val authors = mutableListOf<String>()
        var status: String? = null

        for (el in d.select(".novel-detail-item")) {
            val detailName = el.selectFirst("h6")?.text()?.trim() ?: continue
            val detail = el.selectFirst(".novel-detail-body") ?: continue
            when (detailName) {
                "Description" -> summary = detail.text().trim()
                "Genre" -> genres.addAll(detail.select("li").eachText().map { it.trim() })
                "Author(s)" -> authors.addAll(detail.select("li").eachText().map { it.trim() })
                "Status" -> status = detail.text().trim().takeIf { it.isNotEmpty() }
            }
        }

        val title = d.selectFirst("h1")?.text()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: "Untitled"
        val cover = d.selectFirst(".novel-cover")?.selectFirst("a > img")?.attr("src")

        val chapters = d.select("ul.chapter-chs > li > a").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Episode(
                id = href.removePrefix("$baseUrl/").trimStart('/'),
                name = a.text().trim(),
                number = null,
                dateMillis = null,
            )
        }

        return EntryDetails(
            entry = Entry(id, entryId, title, abs(cover), ContentType.NOVEL),
            description = summary,
            authors = authors.filter { it.isNotBlank() },
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
        val d = doc(absPath(episodeId))
        return paragraphsOf(d.selectFirst("#contentall"))
    }
}

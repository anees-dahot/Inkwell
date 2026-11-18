package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

/**
 * Novel Arrow — the site was rebranded to NovelPing (novelarrow.com now
 * redirects to novelping.com) and rebuilt as a NovelFull-style site:
 * /sort/<kind>?page=N lists, /book/<slug>, /ajax/chapter-archive?novelId=<slug>
 * for the full chapter list, and `#chr-content` on chapter pages.
 */
class NovelArrowSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelarrow"
    override val name = "Novel Arrow"
    override val baseUrl = "https://novelping.com"
    override val lang = "en"

    // ---- listing -----------------------------------------------------------

    private suspend fun listPage(path: String, page: Int): PagedResult<Entry> {
        val d = doc(absPath("$path?page=$page"))
        val items = d.select(".list-novel .row").mapNotNull { rowEntry(it) }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = listPage("sort/popular", page)

    override suspend fun latest(page: Int) = listPage("sort/updates", page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val url = absPath("search").toHttpUrl().newBuilder()
            .addQueryParameter("keyword", query)
            .addQueryParameter("page", page.toString())
            .build()
        val d = doc(url)
        val items = d.select(".list-novel .row").mapNotNull { rowEntry(it) }
        return PagedResult(items, items.isNotEmpty())
    }

    private fun rowEntry(row: Element): Entry? {
        val a = row.selectFirst("h3.novel-title a") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val path = href.removePrefix(baseUrl).trim('/')
        if (!path.startsWith("book/")) return null
        val title = a.text().trim().ifEmpty { return null }
        return Entry(id, path, title, abs(row.selectFirst("img")?.attr("src")), ContentType.NOVEL)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    private fun meta(d: org.jsoup.nodes.Document, prop: String): String? =
        d.selectFirst("meta[property=$prop]")?.attr("content")?.takeIf { it.isNotBlank() }

    override suspend fun details(entryId: String): EntryDetails {
        val path = entryId.trim('/')
        val slug = path.removePrefix("book/")
        val d = doc(absPath(path))
        val summary = d.selectFirst("#novel-description-content")?.wholeText()
            ?.lines()?.joinToString("\n") { it.trim() }?.trim()?.ifEmpty { null }
        val status = if (meta(d, "og:novel:status").equals("OnGoing", true)) "Ongoing" else "Completed"
        val chapters = fetchChapters(slug)
        return EntryDetails(
            entry = Entry(
                sourceId = id,
                id = entryId,
                title = meta(d, "og:novel:novel_name") ?: d.selectFirst("h1")?.text()?.trim() ?: "Untitled",
                coverUrl = abs(meta(d, "og:image")),
                type = ContentType.NOVEL,
            ),
            description = summary,
            authors = listOfNotNull(meta(d, "og:novel:author")),
            genres = meta(d, "og:novel:genre")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    private val numRe = Regex("""[Cc]hapter\s+(\d+(?:\.\d+)?)""")

    private suspend fun fetchChapters(slug: String): List<Episode> {
        val d = doc(absPath("ajax/chapter-archive?novelId=$slug"))
        return d.select("li[data-chapter-item] a").mapNotNull { a ->
            val href = a.attr("href").removePrefix(baseUrl).trim('/').takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val name = a.attr("title").ifBlank { a.text() }.trim()
            Episode(id = href, name = name, number = numRe.find(name)?.groupValues?.get(1)?.toFloatOrNull(), dateMillis = null)
        }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        return fetchChapters(entryId.trim('/').removePrefix("book/")).also { cachedChapters[entryId] = it }
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val c = d.selectFirst("#chr-content") ?: return emptyList()
        return paragraphsOf(c)
    }
}

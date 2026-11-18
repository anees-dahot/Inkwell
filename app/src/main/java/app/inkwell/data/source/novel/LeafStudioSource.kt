package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient

/**
 * LeafStudio (leafstudio.site) — port of LNReader's leafstudio plugin, a
 * plain HTML catalogue at /novels with optional ?search= filtering.
 */
class LeafStudioSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "LeafStudio"
    override val name = "LeafStudio"
    override val baseUrl = "https://leafstudio.site"
    override val lang = "en"

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int) = listPage(page, null)

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> =
        listPage(page, query)

    private suspend fun listPage(page: Int, query: String?): PagedResult<Entry> {
        var link = absPath("novels")
        if (page > 1) link += "/page/$page"
        if (query != null) {
            link += "?search=" + enc(query) + "&type=&language=&status=&sort="
        }
        val d = doc(link)
        val items = d.select("a.novel-item").mapNotNull { card ->
            val name = card.selectFirst("p.novel-item-title")?.text()?.trim().orEmpty()
            val path = card.attr("href").removePrefix(baseUrl).trim('/')
            if (path.isEmpty()) return@mapNotNull null
            Entry(id, path, name, abs(card.selectFirst("img.novel-item-Cover")?.attr("src")), ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val status = d.selectFirst("a#novel_status")?.text()?.trim().orEmpty()
        return EntryDetails(
            entry = Entry(
                id,
                entryId,
                d.selectFirst("h1.title")?.text()?.trim().orEmpty().ifBlank { "Untitled" },
                abs(d.selectFirst("img#novel_cover")?.attr("src")),
                ContentType.NOVEL,
            ),
            description = d.select("div.desc_div > p").map { it.text().trim() }
                .filter { it.isNotEmpty() }.joinToString("\n\n").ifBlank { null },
            authors = emptyList(),
            genres = d.select("div#tags_div > a.novel_genre").eachText()
                .map { it.trim() }.filter { it.isNotEmpty() },
            status = if (status == "Active") "Ongoing" else status.ifEmpty { null },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val d = doc(absPath(entryId))
        val chapters = d.select("a.free_chap.chap").mapNotNull { a ->
            val path = a.attr("href").trim().removePrefix(baseUrl).trim('/')
            if (path.isEmpty()) return@mapNotNull null
            Episode(id = path, name = a.text().trim(), number = null, dateMillis = null)
        }
        // The site lists the newest chapter first.
        return chapters.reversed()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        return d.select("article > p.chapter_content")
            .map { it.text().replace('\u00a0', ' ').trim() }
            .filter { it.isNotEmpty() }
    }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient

/** Port of LNReader's novelhall plugin (HTML scraping). */
class NovelHallSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelhall"
    override val name = "Novel Hall"
    override val baseUrl = "https://novelhall.com"
    override val lang = "en"

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val d = doc("$baseUrl/all2022-$page.html")
        val items = d.select("li.btm").mapNotNull { li ->
            val href = li.selectFirst("a")?.attr("href")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Entry(id, href, li.text().trim(), null, ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val d = doc("$baseUrl/index.php?s=so&module=book&keyword=" + java.net.URLEncoder.encode(query, "UTF-8"))
        val items = d.select("table tr").mapNotNull { tr ->
            val href = tr.selectFirst("td:nth-child(2) a")?.attr("href")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val name = tr.selectFirst("td:nth-child(2)")?.text()?.trim().orEmpty()
            Entry(id, href, name, null, ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        d.select(".total p").remove()
        val author = d.select(".total span").firstOrNull { it.text().contains("Author") }?.text()
            ?.replace("Author：", "")?.trim()?.takeIf { it.isNotEmpty() }
        val status = d.select(".total span").firstOrNull { it.text().contains("Status") }?.text()
            ?.replace("Status：", "")?.replace("Active", "Ongoing")?.trim()?.takeIf { it.isNotEmpty() }
        return EntryDetails(
            entry = Entry(
                id, entryId,
                d.selectFirst(".book-info > h1")?.text()?.takeIf { it.isNotBlank() } ?: "Untitled",
                abs(d.selectFirst("meta[property=og:image]")?.attr("content")),
                ContentType.NOVEL,
            ),
            description = d.selectFirst(".intro")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(author),
            genres = d.select(".total a").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val d = doc(absPath(entryId))
        return d.select("#morelist ul > li").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Episode(href, a.text().trim(), null, null)
        }
    }

    override suspend fun chapterText(episodeId: String): List<String> =
        paragraphsOf(doc(absPath(episodeId)).selectFirst("#htmlContent"))
}

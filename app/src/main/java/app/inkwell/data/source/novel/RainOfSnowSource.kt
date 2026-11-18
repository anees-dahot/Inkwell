package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.time.LocalDate

/** Port of LNReader's rainofsnow plugin. */
class RainOfSnowSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "rainofsnow"
    override val name = "Rainofsnow"
    override val baseUrl = "https://rainofsnow.com"
    override val lang = "en"

    private fun relPath(href: String?): String? {
        if (href.isNullOrEmpty()) return null
        return href.removePrefix(baseUrl).removePrefix("/").trimEnd('/').takeIf { it.isNotEmpty() }
    }

    private fun novels(d: Document): List<Entry> = d.select(".minbox").mapNotNull { el ->
        val path = relPath(el.selectFirst("h3 > a")?.attr("href")) ?: return@mapNotNull null
        Entry(id, path, el.selectFirst("h3")?.text().orEmpty(), abs(el.selectFirst("img")?.attr("data-src")), ContentType.NOVEL)
    }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val items = novels(doc("$baseUrl/novels/page/$page"))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val url = baseUrl.toHttpUrl().newBuilder().addQueryParameter("s", query.trim().replace(Regex("\\s+"), "+")).build()
        return PagedResult(novels(doc(url)), false)
    }

    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private fun chapters(d: Document): List<Episode> = d.select("#chapter .march1 li").mapNotNull { li ->
        val href = li.selectFirst("a")?.attr("href")
        val path = relPath(href) ?: return@mapNotNull null
        val m = Regex("([a-z]+) (..?), (.+)").find(li.select("small").text().trim().lowercase())
        val mi = m?.let { months.indexOf(it.groupValues[1].take(3)) } ?: -1
        val date = if (m != null && mi != -1) runCatching {
            LocalDate.of(m.groupValues[3].trim().toInt(), mi + 1, m.groupValues[2].trim().toInt())
                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull() else null
        Episode(path, li.selectFirst(".chapter")?.text()?.trim().orEmpty(), null, date)
    }

    private fun labelNext(d: Document, label: String): String =
        d.select("span").firstOrNull { it.ownText().contains(label) || it.text().contains(label) }
            ?.nextElementSibling()?.text()?.trim().orEmpty()

    private val chapterCache = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc("$baseUrl/$entryId")
        var total = 1
        for (li in d.select(".page-numbers li")) {
            val n = Regex("(\\d+)").find(li.select("a").text().trim())?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (n > total) total = n
        }
        val eps = ArrayList(chapters(d))
        for (p in 2..total) eps += chapters(doc("$baseUrl/$entryId/page/$p/"))
        chapterCache[entryId] = eps
        return EntryDetails(
            entry = Entry(id, entryId, d.select(".text h2").text().trim(), abs(d.selectFirst(".imagboca1 img")?.attr("data-src")), ContentType.NOVEL),
            description = d.selectFirst("#synop")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(labelNext(d, "Author").takeIf { it.isNotEmpty() }),
            genres = labelNext(d, "Genre(s)").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            status = null,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        chapterCache[entryId]?.let { return it }
        details(entryId)
        return chapterCache[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc("$baseUrl/${episodeId.trimStart('/')}")
        return paragraphsOf(d.selectFirst(".content"))
    }
}

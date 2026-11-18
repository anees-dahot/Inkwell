package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class ChrysanthemumGardenSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "chrysanthemumgarden"
    override val name = "Chrysanthemum Garden"
    override val baseUrl = "https://chrysanthemumgarden.com"
    override val lang = "en"

    // ---- listing -----------------------------------------------------------

    private suspend fun booksPage(page: Int): PagedResult<Entry> {
        val url = absPath(if (page == 1) "books" else "books/page/$page") + "/"
        val d = doc(url)
        val items = d.select("article").mapNotNull { art ->
            val genres = art.select("div.series-genres > a").eachText().joinToString()
            if (genres.contains("Manhua")) return@mapNotNull null
            val href = art.selectFirst("h2.novel-title > a")?.attr("href") ?: return@mapNotNull null
            val cover = art.selectFirst("div.novel-cover > img")?.attr("data-breeze")
            Entry(
                sourceId = id,
                id = href.removePrefix(baseUrl).trim('/'),
                title = art.selectFirst("h2.novel-title > a")?.text()?.trim() ?: "Untitled",
                coverUrl = abs(cover),
                type = ContentType.NOVEL,
            )
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = booksPage(page)
    override suspend fun latest(page: Int) = booksPage(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val root = json(absPath("wp-json/cg/novels"))
        val all = root.jsonArray.mapNotNull { el ->
            val o = el.jsonObject
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val link = o["link"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Triple(name, link.removePrefix(baseUrl).trim('/'), null as String?)
        }
        val q = query.lowercase()
        val items = all.filter { (n, _, _) -> n.lowercase().contains(q) }
            .map { (n, p, c) -> Entry(id, p, n, c, ContentType.NOVEL) }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId) + "/")
        d.selectFirst("h1.novel-title > span.novel-raw-title")?.remove()
        val summary = d.select("div.entry-content > p").joinToString("\n\n") { it.text() }.trim()
        val author = Regex("Author:\\s*([^<]*)<br>")
            .find(d.selectFirst("div.novel-info")?.html().orEmpty())?.groupValues?.get(1)?.trim()
        val genres = buildList {
            addAll(d.select("div.series-genres > a").eachText())
            addAll(d.select("a.series-tag").eachText().map { it.substringBefore('(').trim() })
        }.distinct()
        val cover = d.selectFirst("div.novel-cover > img")?.attr("data-breeze")
        val title = d.selectFirst("h1.novel-title")?.text()?.trim() ?: "Untitled"
        val chapters = chapterList(d)
        return EntryDetails(
            entry = Entry(id, entryId, title, abs(cover), ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = genres,
            status = null,
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    private fun chapterList(d: org.jsoup.nodes.Document): List<Episode> =
        d.select("div.chapter-item > a").mapNotNull { a ->
            val href = a.attr("href") ?: return@mapNotNull null
            Episode(
                id = href.removePrefix(baseUrl).trim('/'),
                name = a.text().trim(),
                number = a.text().trim().substringAfterLast(' ').toFloatOrNull(),
                dateMillis = null,
            )
        }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        val d = doc(absPath(entryId) + "/")
        return chapterList(d).also { cachedChapters[entryId] = it }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId) + "/")
        return paragraphsOf(d.selectFirst("div#novel-content"))
    }
}

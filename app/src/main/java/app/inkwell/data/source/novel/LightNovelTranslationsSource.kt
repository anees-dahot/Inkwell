package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import java.net.URLEncoder
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's lightnoveltranslation plugin. */
class LightNovelTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "lightnoveltranslations"
    override val name = "Light Novel Translations"
    override val baseUrl = "https://lightnovelstranslations.com"
    override val lang = "en"

    // Site serves 6 novels per page; fetch 5 site pages per app page.
    private val pagesPerAppPage = 5

    private fun relPath(href: String) = href.removePrefix(baseUrl).trimStart('/')

    private fun cardsOf(html: String): List<Entry> =
        Jsoup.parse(html, baseUrl).select(".read_list-story-item").mapNotNull { card ->
            val thumb = card.selectFirst(".item_thumb a") ?: return@mapNotNull null
            val href = thumb.attr("href").ifEmpty { return@mapNotNull null }
            val title = thumb.attr("aria-label").ifEmpty {
                card.selectFirst(".wrap_item_content h3")?.text()?.trim().orEmpty()
            }.ifEmpty { thumb.attr("title") }
            Entry(id, relPath(href), title, card.selectFirst(".item_thumb img")?.attr("src")?.ifEmpty { null }, ContentType.NOVEL)
        }

    private suspend fun fetchCards(appPage: Int, url: (Int) -> String): PagedResult<Entry> {
        val first = (appPage - 1) * pagesPerAppPage + 1
        val out = ArrayList<Entry>()
        for (p in first until first + pagesPerAppPage) {
            val items = runCatching { cardsOf(fetch(url(p))) }.getOrElse { if (out.isEmpty() && p == first) throw it else emptyList() }
            if (items.isEmpty()) break
            out.addAll(items)
        }
        return PagedResult(out.distinctBy { it.id }, out.isNotEmpty())
    }

    private fun browse(page: Int, sortby: String) =
        if (page > 1) "$baseUrl/browse/page/$page/?sortby=$sortby" else "$baseUrl/browse/?sortby=$sortby"

    override suspend fun popular(page: Int) = fetchCards(page) { browse(it, "most-liked") }
    override suspend fun latest(page: Int) = fetchCards(page) { browse(it, "most-recent") }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val q = "field-search=${URLEncoder.encode(query, "UTF-8")}"
        return fetchCards(page) { if (it > 1) "$baseUrl/browse/page/$it/?$q" else "$baseUrl/browse/?$q" }
    }

    private suspend fun novelDoc(entryId: String) =
        Jsoup.parse(fetch(absPath(entryId)).replace(Regex(">\\s+<"), "><"), baseUrl)

    override suspend fun details(entryId: String): EntryDetails {
        val d = novelDoc(entryId)
        val status = when (d.select("div.novel_status").text().trim()) {
            "Ongoing" -> "Ongoing"
            "Hiatus" -> "On Hiatus"
            "Completed" -> "Completed"
            else -> null
        }
        val author = d.select("div.novel_detail_info li").firstOrNull { it.text().contains("Author") }
            ?.text()?.replace(Regex("^\\s*Author:?\\s*"), "")?.trim()
        return EntryDetails(
            entry = Entry(
                id, entryId, d.selectFirst("div.novel_title h3")?.text()?.trim().orEmpty(),
                d.selectFirst("div.novel-image img")?.attr("src")?.ifEmpty { null }, ContentType.NOVEL,
            ),
            description = d.selectFirst("div.novel_text p")?.text()?.trim()?.ifEmpty { null },
            authors = listOfNotNull(author?.ifEmpty { null }),
            genres = emptyList(),
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> =
        novelDoc(entryId).select("li.chapter-item.unlock").mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifEmpty { return@mapNotNull null }
            Episode(relPath(href), a.text().trim(), null, null)
        }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = Jsoup.parse(fetch(absPath(episodeId)).replace(Regex(">\\s+<"), "><"), baseUrl)
        val el = d.selectFirst("div.text_story") ?: return emptyList()
        el.select("div.ads_content").remove()
        return paragraphsOf(el)
    }
}

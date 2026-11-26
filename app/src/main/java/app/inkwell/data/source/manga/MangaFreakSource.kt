package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.model.chapterNumber
import app.inkwell.data.source.ScraperMangaSource
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/** Port of Keiyoushi's MangaFreak extension (ww3.mangafreak.me). */
class MangaFreakSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    override val baseUrl = "https://ww3.mangafreak.me"
    override val id = "mangafreak"
    override val name = "MangaFreak"
    override val lang = "en"

    override val headers = mapOf("Referer" to "https://ww3.mangafreak.me/")

    private fun cover(slug: String) = ref("https://images.mangafreak.me/manga_images/${slug.lowercase()}.jpg")
    private fun slugOf(href: String) = href.substringAfterLast("/Manga/")

    private fun published(el: Element) =
        chapterNumber(el.selectFirst("div:matchesOwn(^\\d+ Published)")?.text()?.substringBefore(' '))

    private fun hasNext(d: Document) = d.selectFirst(".pagination a.next_p") != null

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val d = doc("$baseUrl/Genre/All/$page")
        val items = d.select("div.ranking_item").mapNotNull { el ->
            val a = el.selectFirst("a[href^=/Manga/]") ?: return@mapNotNull null
            Entry(id, a.attr("href"), a.text().trim(), ref(el.selectFirst("img")?.attr("src")), type, published(el))
        }
        return PagedResult(items, hasNext(d))
    }

    /** /Genre/Slice_Of_Life/1 (site matches genre names case-insensitively). */
    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> {
        val g = tag.trim().replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').ifEmpty { return PagedResult(emptyList(), false) }
        val d = try { doc("$baseUrl/Genre/$g/$page") } catch (e: IllegalStateException) {
            if (e.message?.contains("404") == true) return PagedResult(emptyList(), false) else throw e
        }
        val items = d.select("div.ranking_item").mapNotNull { el ->
            val a = el.selectFirst("a[href^=/Manga/]") ?: return@mapNotNull null
            Entry(id, a.attr("href"), a.text().trim(), ref(el.selectFirst("img")?.attr("src")), type, published(el))
        }
        return PagedResult(items, items.isNotEmpty() && hasNext(d))
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        val d = doc("$baseUrl/Latest_Releases/$page")
        val items = d.select("div.latest_releases_item").mapNotNull { el ->
            val a = el.selectFirst("a[href^=/Manga/]") ?: return@mapNotNull null
            Entry(id, a.attr("href"), a.text().trim(), cover(slugOf(a.attr("href"))), type,
                chapterNumber(el.selectFirst("a[href^=/Read]")?.text()?.substringAfterLast(' ')))
        }
        return PagedResult(items, hasNext(d))
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        if (page > 1) return PagedResult(emptyList(), false) // site returns every result on one page
        val d = doc("$baseUrl/Find/" + URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20"))
        val items = d.select("div.manga_search_item").mapNotNull { el ->
            val a = el.selectFirst("h3 a[href^=/Manga/]") ?: return@mapNotNull null
            Entry(id, a.attr("href"), a.text().trim(), ref(el.selectFirst("img")?.attr("src")), type)
        }
        return PagedResult(items, false)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val info = d.select(".manga_series_data > div")
        fun field(label: String) = info.firstOrNull { it.text().startsWith(label, true) }?.text()?.substringAfter(":")?.trim()
        val statusLine = info.firstOrNull { it.text().startsWith("This is", true) }?.text().orEmpty()
        return EntryDetails(
            entry = Entry(id, entryId, d.selectFirst(".manga_series_data h1")?.text().orEmpty(),
                ref(d.selectFirst(".manga_series_image img")?.attr("src")), type),
            description = d.selectFirst(".manga_series_description p")?.text()
                ?.replace("&quot;", "\"")?.ifEmpty { null },
            authors = listOfNotNull(field("Written By"), field("Illustrated By")).distinct(),
            genres = d.select(".series_sub_genre_list a").map { it.text().trim() },
            status = when {
                statusLine.contains("ON-GOING", true) -> "Ongoing"
                statusLine.contains("COMPLETE", true) -> "Completed"
                else -> null
            },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> =
        doc(absPath(entryId)).select(".manga_series_list tr").mapNotNull { tr ->
            val a = tr.selectFirst("a.chapter-link") ?: return@mapNotNull null
            val name = a.text().trim()
            Episode(
                id = a.attr("href"),
                name = name,
                number = Regex("""Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(name)
                    ?.groupValues?.get(1)?.toFloatOrNull(),
                dateMillis = tr.select("td").getOrNull(1)?.text()?.trim()?.let { t ->
                    runCatching {
                        java.time.LocalDate.parse(t, java.time.format.DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }.getOrNull()
                },
            )
        }.reversed()

    override suspend fun pages(episodeId: String): List<Page> =
        doc(absPath(episodeId)).select("img#gohere")
            .mapNotNull { it.attr("src").ifEmpty { null } }
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
}

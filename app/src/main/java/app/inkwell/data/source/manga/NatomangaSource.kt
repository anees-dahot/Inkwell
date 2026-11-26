package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import java.net.URLDecoder

/**
 * Natomanga / Manganato network. natomanga.com redirects to natumanga.com (Cloudflare-challenged on every inner
 * page); the same site is served unchallenged (except search) from manganato.gg, which is what Mihon's Manganato
 * extension uses. Image CDN (2xstorage.com) requires the site Referer; [ref] registers it.
 * Search is behind a Cloudflare managed challenge: it raises CloudflareException so the app can solve it in WebView.
 */
class NatomangaSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    override val baseUrl = "https://www.manganato.gg"
    override val id = "natomanga"
    override val name = "Natomanga"
    override val lang = "en"

    override val headers = mapOf("Referer" to "$baseUrl/")

    private fun path(url: String) = url.toHttpUrl().encodedPath

    private fun parseList(d: Document): PagedResult<Entry> {
        val items = d.select("div.list-comic-item-wrap").mapNotNull { el ->
            val a = el.selectFirst("h3 a") ?: return@mapNotNull null
            val href = a.absUrl("href").ifEmpty { return@mapNotNull null }
            val im = el.selectFirst("a.cover img, img")
            val cover = (im?.absUrl("data-src")?.ifEmpty { null } ?: im?.absUrl("src")?.ifEmpty { null })
            Entry(id, path(href), a.text().trim(), ref(cover), type,
                app.inkwell.data.model.chapterNumber(el.selectFirst("a.list-story-item-wrap-chapter")?.text()))
        }
        val cur = d.selectFirst(".group-page a.page_select")?.text()?.trim()?.toIntOrNull()
        val last = d.select(".group-page a[href]").mapNotNull {
            Regex("""[?&]page=(\d+)""").find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull()
        val hasNext = if (cur != null && last != null) cur < last else items.isNotEmpty() && last != null
        return PagedResult(items, hasNext)
    }

    override suspend fun popular(page: Int) = parseList(doc("$baseUrl/genre/all?type=topview&page=$page"))
    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> {
        val slug = app.inkwell.data.model.tagSlug(tag).ifEmpty { return PagedResult(emptyList(), false) }
        val d = try { doc("$baseUrl/genre/$slug?type=topview&page=$page") } catch (e: IllegalStateException) {
            if (e.message?.contains("404") == true) return PagedResult(emptyList(), false) else throw e
        }
        return parseList(d)
    }
    override suspend fun latest(page: Int) = parseList(doc("$baseUrl/manga-list/latest-manga?page=$page"))

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        val alias = query.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "_").trim('_')
        return parseList(doc("$baseUrl/search/story/$alias?page=$page"))
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val info = d.selectFirst("ul.manga-info-text") ?: error("Page not found")
        val title = info.selectFirst("h1")?.text()?.trim().orEmpty()
        val desc = d.selectFirst("#contentBox")?.also { it.select("h2, script, style").remove() }?.text()?.trim()
        fun field(label: String) = info.select("li").firstOrNull { it.ownText().trim().startsWith(label, true) }
        val authors = field("Author")?.let { li ->
            li.select("a").map { it.text().trim() }.ifEmpty {
                li.ownText().substringAfter(":").split(",").map { it.trim() }
            }
        }.orEmpty().filter { it.isNotEmpty() && !it.equals("updating", true) }
        val status = field("Status")?.ownText()?.substringAfter(":")?.trim()?.ifEmpty { null }
        return EntryDetails(
            entry = Entry(id, entryId, title, ref(d.selectFirst(".manga-info-pic img")?.absUrl("src")?.ifEmpty { null }), type),
            description = desc?.ifEmpty { null },
            authors = authors,
            genres = info.select("li.genres a").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = entryId.trimEnd('/').substringAfterLast('/')
        val data = json("$baseUrl/api/manga/$slug/chapters?limit=-1").jsonObject["data"]?.jsonObject
        val arr = data?.get("chapters")?.jsonArray ?: return emptyList()
        return arr.mapNotNull { c ->
            val o = c.jsonObject
            val cs = o.str("chapter_slug") ?: return@mapNotNull null
            Episode(
                id = "/manga/$slug/$cs",
                name = o.str("chapter_name") ?: cs,
                number = o["chapter_num"]?.jsonPrimitive?.floatOrNull,
                dateMillis = parseDate(o.str("updated_at")),
            )
        }
    }

    private fun JsonObject.str(k: String): String? =
        (this[k] as? JsonElement)?.jsonPrimitive?.contentOrNull?.ifEmpty { null }

    override suspend fun pages(episodeId: String): List<Page> =
        doc(absPath(episodeId)).select(".container-chapter-reader img")
            .map { it.absUrl("src").ifEmpty { it.absUrl("data-src") } }
            .filter { it.isNotEmpty() }
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
}

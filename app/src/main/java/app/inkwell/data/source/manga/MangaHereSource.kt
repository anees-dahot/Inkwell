package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.model.chapterNumber
import app.inkwell.data.source.ScraperMangaSource
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/** Port of Keiyoushi's MangaHere extension (www.mangahere.cc). Shares its backend with Fanfox. */
open class MangaHereSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    override val baseUrl = "https://www.mangahere.cc"
    override val id = "mangahere"
    override val name = "MangaHere"
    override val lang = "en"

    override val headers get() = mapOf("Referer" to "$baseUrl/", "Cookie" to "isAdult=1")

    private fun https(u: String?): String? = u?.takeIf { it.isNotBlank() }?.let {
        when {
            it.startsWith("//") -> "https:$it"
            it.startsWith("http://") -> "https://" + it.removePrefix("http://")
            else -> it
        }
    }

    private fun listing(d: Document): PagedResult<Entry> {
        val items = d.select("ul.manga-list-1-list li, ul.manga-list-4-list li").mapNotNull { el ->
            val a = el.selectFirst("p.manga-list-1-item-title a, p.manga-list-4-item-title a") ?: return@mapNotNull null
            Entry(id, a.attr("href"), a.attr("title").ifEmpty { a.text() },
                ref(https(el.selectFirst("img[class*=cover]")?.attr("src"))), type,
                chapterNumber(el.selectFirst("p.manga-list-1-item-subtitle a, p.manga-list-4-item-subtitle a")?.text()?.takeIf { "Ch." in it }?.substringAfterLast("Ch.")))
        }
        val next = d.select(".pager-list-left a").lastOrNull()
        val hasNext = next != null && next.text().trim() == ">" && !next.attr("href").startsWith("javascript")
        return PagedResult(items, hasNext)
    }

    override suspend fun popular(page: Int) = listing(doc("$baseUrl/directory/$page.htm?views.za"))

    private val genreSlugs = setOf(
        "action", "adventure", "comedy", "doujinshi", "drama", "ecchi", "fantasy", "gender_bender", "harem",
        "historical", "horror", "josei", "martial_arts", "mature", "mecha", "mystery", "one_shot", "psychological",
        "romance", "school_life", "sci_fi", "seinen", "shoujo", "shoujo_ai", "shounen", "shounen_ai",
        "slice_of_life", "smut", "sports", "supernatural", "tragedy", "webtoons", "yaoi", "yuri",
    )

    /** /directory/<genre_slug>/<page>.htm (unknown slugs silently fall back to the full directory, so gate on a known set). */
    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> {
        val slug = app.inkwell.data.model.tagSlug(tag).replace('-', '_')
        if (slug !in genreSlugs) return PagedResult(emptyList(), false)
        return listing(doc("$baseUrl/directory/$slug/$page.htm?views.za"))
    }

    override suspend fun latest(page: Int) = listing(doc("$baseUrl/directory/$page.htm?latest.az"))

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        val url = "$baseUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("title", query.trim()).build()
        return listing(doc(url))
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val title = d.selectFirst(".detail-info-right-title-font")?.text().orEmpty()
        return EntryDetails(
            entry = Entry(id, entryId, title, ref(https(d.selectFirst("img.detail-info-cover-img")?.attr("src"))), type),
            description = d.selectFirst("p.fullcontent")?.text()?.ifEmpty { null },
            authors = d.select("p.detail-info-right-say a").map { it.text() },
            genres = d.select("p.detail-info-right-tag-list a").map { it.text() },
            status = d.selectFirst("span.detail-info-right-title-tip")?.text()?.ifEmpty { null },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> =
        doc(absPath(entryId)).select("ul.detail-main-list li a").mapNotNull { a ->
            val href = a.attr("href").ifEmpty { return@mapNotNull null }
            val name = a.selectFirst("p.title3")?.text() ?: a.attr("title")
            Episode(
                id = href,
                name = name,
                number = Regex("""Ch\.(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toFloatOrNull(),
                dateMillis = parseChapterDate(a.selectFirst("p.title2")?.text()),
            )
        }

    private fun parseChapterDate(s: String?): Long? {
        val t = s?.trim().orEmpty()
        val now = System.currentTimeMillis()
        return when {
            t.isEmpty() -> null
            t.startsWith("Today", true) -> now
            t.startsWith("Yesterday", true) -> now - 86_400_000L
            else -> runCatching {
                java.time.LocalDate.parse(t, java.time.format.DateTimeFormatter.ofPattern("MMM dd,yyyy", java.util.Locale.ENGLISH))
                    .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: parseDate(t)
        }
    }

    override suspend fun pages(episodeId: String): List<Page> {
        val chapterUrl = absPath(episodeId)
        val html = fetch(chapterUrl)
        val cid = Regex("""var chapterid\s*=\s*(\d+)""").find(html)?.groupValues?.get(1) ?: error("chapter id not found")
        val count = Regex("""var imagecount\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: error("image count not found")
        val dir = chapterUrl.substringBeforeLast('/')
        val urls = linkedSetOf<String>()
        var p = 1
        while (p <= count) {
            // each chapterfun response carries this page and the next one
            val req = request("$dir/chapterfun.ashx?cid=$cid&page=$p&key=")
                .newBuilder().header("Referer", chapterUrl).build()
            val body = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                client.newCall(req).execute().use { r -> check(r.isSuccessful) { "$name HTTP ${r.code}" }; r.body?.string().orEmpty() }
            }
            val js = unpackJs(body)
            val pix = Regex("""var pix="(.*?)"""").find(js)?.groupValues?.get(1) ?: break
            val list = Regex("""var pvalue=\[(.*?)]""").find(js)?.groupValues?.get(1) ?: break
            val got = Regex(""""(.*?)"""").findAll(list).map { it.groupValues[1] }.toList()
            if (got.isEmpty()) break
            got.forEach { v -> urls += https(if (v.startsWith("//") || v.startsWith("http")) v else pix + v)!! }
            p += 2
        }
        return urls.take(count).mapIndexed { i, u -> Page(i, ref(u)!!) }
    }

    /** Dean Edwards p,a,c,k,e,d unpacker. */
    private fun unpackJs(src: String): String {
        val m = Regex("""\}\('(.*)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL).find(src) ?: return src
        val payload = m.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val radix = m.groupValues[2].toInt()
        val count = m.groupValues[3].toInt()
        val words = m.groupValues[4].split('|')
        fun enc(n: Int): String {
            val head = if (n < radix) "" else enc(n / radix)
            val r = n % radix
            return head + if (r > 35) (r + 29).toChar().toString() else r.toString(36)
        }
        val dict = HashMap<String, String>()
        for (i in count - 1 downTo 0) dict[enc(i)] = words.getOrNull(i)?.takeIf { it.isNotEmpty() } ?: enc(i)
        return Regex("""\b\w+\b""").replace(payload) { dict[it.value] ?: it.value }
    }
}

/** Fanfox shares MangaHere's backend and markup. */
class FanfoxSource(client: OkHttpClient) : MangaHereSource(client) {
    override val baseUrl = "https://fanfox.net"
    override val id = "fanfox"
    override val name = "Fanfox"
}

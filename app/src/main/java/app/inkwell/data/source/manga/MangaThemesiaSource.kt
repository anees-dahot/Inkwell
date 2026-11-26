package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Base for sites running the WordPress "MangaThemesia" (a.k.a. MangaStream /
 * Asura-old style) theme — port of Keiyoushi lib-multisrc/mangathemesia.
 */
abstract class MangaThemesiaSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    override val lang = "en"
    override val headers: Map<String, String> get() = mapOf("Referer" to "$baseUrl/")

    /** Series directory ("/manga", some sites "/series", "/comics"). */
    protected open val mangaUrlDirectory = "/manga"
    protected open val popularOrder = "popular"
    protected open val latestOrder = "update"

    protected open val listItemSelector = ".listupd .bs .bsx"
    protected open val nextPageSelector = "a.next.page-numbers, a.r"
    protected open val chapterSelector = "#chapterlist li"
    protected open val dateFormats = listOf("MMMM d, yyyy", "MMM d, yyyy", "d MMM yyyy", "dd/MM/yyyy", "yyyy-MM-dd")

    protected fun path(url: String): String = url.toHttpUrl().encodedPath

    protected fun imgUrl(e: Element?): String? {
        e ?: return null
        for (a in listOf("data-lazy-src", "data-src", "src")) {
            val v = e.attr(a).trim()
            if (v.isNotEmpty() && !v.startsWith("data:")) return abs(v)
        }
        return null
    }

    protected fun parseList(d: Document): PagedResult<Entry> {
        val items = d.select(listItemSelector).mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = a.absUrl("href").ifEmpty { return@mapNotNull null }
            val title = a.attr("title").ifEmpty { el.selectFirst(".tt")?.text().orEmpty() }.trim()
            if (title.isEmpty()) return@mapNotNull null
            Entry(id, path(href), title, ref(imgUrl(el.selectFirst("img"))), type, app.inkwell.data.model.chapterNumber(el.selectFirst(".epxs")?.text()))
        }.distinctBy { it.id }
        return PagedResult(items, d.selectFirst(nextPageSelector) != null)
    }

    protected suspend fun listing(url: String): PagedResult<Entry> = try {
        parseList(doc(url))
    } catch (e: IllegalStateException) {
        if (e.message?.contains("404") == true) PagedResult(emptyList(), false) else throw e
    }

    protected open fun browseUrl(page: Int, order: String) =
        "$baseUrl$mangaUrlDirectory/?page=$page&order=$order"

    override suspend fun popular(page: Int) = listing(browseUrl(page, popularOrder))
    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> =
        listing(browseUrl(page, popularOrder) + "&genre%5B%5D=" + app.inkwell.data.model.tagSlug(tag))
    override suspend fun latest(page: Int) = listing(browseUrl(page, latestOrder))

    protected open fun searchUrl(query: String, page: Int): String =
        "$baseUrl/page/$page/".toHttpUrl().newBuilder().addQueryParameter("s", query.trim()).build().toString()

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        return listing(searchUrl(query, page))
    }

    /** Value of an info row labelled [label] (infotable / tsinfo / .spe layouts). */
    protected fun info(d: Document, label: String): String? {
        d.select(".infotable tr").firstOrNull { it.selectFirst("td")?.text()?.trim()?.startsWith(label, true) == true }
            ?.select("td")?.getOrNull(1)?.text()?.trim()?.let { return it }
        d.select(".tsinfo .imptdt, .imptdt").firstOrNull { it.ownText().trim().startsWith(label, true) }
            ?.selectFirst("i, a")?.text()?.trim()?.let { return it }
        d.select(".spe span, .fmed").firstOrNull { it.text().trim().startsWith(label, true) }
            ?.text()?.substringAfter(":", "")?.trim()?.let { return it }
        return null
    }

    protected open fun description(d: Document): String? =
        d.selectFirst(".entry-content[itemprop=description], .desc, .entry-content")
            ?.let { e -> e.select("p").map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { e.text().trim() } }
            ?.ifEmpty { null }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val title = d.selectFirst("h1.entry-title, .ts-breadcrumb li:last-child span")?.text()?.trim()
            ?: error("Page not found")
        val status = info(d, "Status")?.lowercase()?.let {
            when {
                it.contains("ongoing") -> "Ongoing"
                it.contains("complete") -> "Completed"
                it.contains("hiatus") -> "Hiatus"
                it.contains("drop") || it.contains("cancel") -> "Cancelled"
                else -> null
            }
        }
        val authors = listOfNotNull(info(d, "Author"), info(d, "Artist"))
            .flatMap { it.split(",") }.map { it.trim() }
            .filter { it.isNotEmpty() && !it.equals("n/a", true) && !it.equals("-", true) }.distinct()
        return EntryDetails(
            entry = Entry(id, entryId, title, ref(imgUrl(d.selectFirst(".infomanga > div[itemprop=image] img, .thumb img"))), type),
            description = description(d),
            authors = authors,
            genres = d.select(".mgen a, .seriestugenre a, .genxed a, span.mgen a").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = status,
        )
    }

    private fun toMillis(raw: String?): Long? {
        val s = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        for (f in dateFormats) runCatching {
            return LocalDate.parse(s, DateTimeFormatter.ofPattern(f, Locale.ENGLISH))
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        return parseDate(s)
    }

    override suspend fun episodes(entryId: String): List<Episode> =
        doc(absPath(entryId)).select(chapterSelector).mapNotNull { el ->
            val a = el.selectFirst("a") ?: return@mapNotNull null
            val href = a.absUrl("href").ifEmpty { return@mapNotNull null }
            val num = el.selectFirst(".chapternum")?.text()?.trim().orEmpty().ifEmpty { a.text().trim() }
            Episode(
                id = path(href),
                name = num,
                number = el.attr("data-num").toFloatOrNull()
                    ?: Regex("""\d+(?:\.\d+)?""").find(num)?.value?.toFloatOrNull(),
                dateMillis = toMillis(el.selectFirst(".chapterdate")?.text()),
            )
        }

    override suspend fun pages(episodeId: String): List<Page> {
        val d = doc(absPath(episodeId))
        val script = d.select("script").map { it.data() }.firstOrNull { it.contains("ts_reader.run") }
        var urls: List<String> = emptyList()
        if (script != null) {
            val raw = script.substringAfter("ts_reader.run(").substringBefore(");")
            urls = runCatching {
                json.parseToJsonElement(raw).jsonObject["sources"]!!.jsonArray.first()
                    .jsonObject["images"]!!.jsonArray.map { it.jsonPrimitive.content }
            }.getOrDefault(emptyList())
        }
        if (urls.isEmpty()) {
            urls = d.select("#readerarea img").mapNotNull { imgUrl(it) }
        }
        return urls.mapNotNull { abs(it.trim()) }
            .filter { !it.endsWith("readerarea.svg") }.distinct()
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
    }
}

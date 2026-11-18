package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Port of LNReader's webnovel.com plugin (Qidian). Metadata comes from the
 * m.webnovel.com `__NEXT_DATA__` JSON (fallback: desktop `g_data.book=`), the
 * chapter list from the desktop `/catalog` page (`.volume-item`), and chapter
 * text from `.cha-tit` + `.cha-words`. Locked chapters are always listed with a
 * 🔒 suffix (the plugin's "hide locked" setting is not exposed).
 */
class WebnovelSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "webnovel"
    override val name = "Webnovel"
    override val baseUrl = "https://www.webnovel.com"
    override val lang = "en"

    /** Desktop Chrome UA. Note: m.webnovel.com redirects desktop agents — see [fetchNextData]. */
    override val headers = mapOf("User-Agent" to DESKTOP_UA)


    private val cachedChapters = HashMap<String, List<Episode>>()

    // ---- listing -----------------------------------------------------------

    private suspend fun categoryPage(page: Int, orderBy: Int): PagedResult<Entry> {
        val d = doc("$baseUrl/stories/novel?orderBy=$orderBy&pageIndex=$page")
        val items = parseNovelList(d, category = true)
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = categoryPage(page, orderBy = 1)
    override suspend fun latest(page: Int) = categoryPage(page, orderBy = 5)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            // Site convention: '+' stands in for spaces. OkHttp re-encodes it to
            // %2B, exactly like the plugin's encodeURIComponent would.
            .addQueryParameter("keywords", query.replace(Regex("\\s+"), "+"))
            .addQueryParameter("pageIndex", page.toString())
            .build()
        val items = parseNovelList(doc(url), category = false)
        return PagedResult(items, items.isNotEmpty())
    }

    /** TS parseNovels: category list (data-original thumbs) or search list (src). */
    private fun parseNovelList(d: Document, category: Boolean): List<Entry> {
        val selector = if (category) ".j_category_wrapper" else ".j_list_container"
        val attribute = if (category) "data-original" else "src"
        return d.select("$selector li").mapNotNull { li ->
            val thumb = li.selectFirst(".g_thumb") ?: return@mapNotNull null
            val path = thumb.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val img = thumb.selectFirst(".g_thumb > img")
            val cover = img?.attr(attribute)?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() }
            Entry(
                sourceId = id,
                id = path.removePrefix("/"),
                title = thumb.attr("title").takeUnless { it.isBlank() } ?: "No Title Found",
                coverUrl = httpsAbs(cover),
                type = ContentType.NOVEL,
            )
        }
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val path = entryId.trimStart('/')
        // Fetched first: if Cloudflare blocks it we throw before the extras
        val catalog = fetchDesktop(absPath(path) + "/catalog")
        val bookId = bookIdOf(path)
        // The mobile book page is ~200 KB vs up to 3.4 MB desktop and has more
        // stats; only the mobile catalog has absolute chapter dates
        val (mobileBook, mobileCatalog) = coroutineScope {
            val book = async { fetchNextData("$MOBILE_SITE/$path") }
            val cat = async { bookId?.let { fetchNextData("$MOBILE_SITE/book/$it/catalog") } }
            book.await() to cat.await()
        }
        val initData = mobileBook.obj("initialProps").obj("pageProps").obj("initData")
        // Throws when blocked: placeholders would overwrite real metadata
        val desktopBody = if (initData.obj("bookInfo") != null) "" else fetchDesktop(absPath(path))
        parseChapterList(catalog, mobileCatalog).also { cachedChapters[path] = it }
        return buildNovel(path, desktopBody, initData)
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val path = entryId.trimStart('/')
        cachedChapters[path]?.let { return it }
        val catalog = fetchDesktop(absPath(path) + "/catalog")
        val mobileCatalog = bookIdOf(path)
            ?.let { fetchNextData("$MOBILE_SITE/book/$it/catalog") }
        return parseChapterList(catalog, mobileCatalog).also { cachedChapters[path] = it }
    }

    /** Desktop page; retries a Cloudflare "Just a moment" 403 once, then throws. */
    private suspend fun fetchDesktop(url: String): String {
        var status = 0
        for (attempt in 0..1) {
            if (attempt > 0) delay(1500)
            val (code, body) = withContext(Dispatchers.IO) {
                client.newCall(request(url)).execute().use { r ->
                    if (r.isSuccessful) r.code to r.body?.string().orEmpty() else r.code to null
                }
            }
            if (body != null) return body
            status = code
        }
        throw IllegalStateException(
            "Webnovel: HTTP $status for $url (Cloudflare? open the novel in WebView, then retry)"
        )
    }

    /**
     * Best effort: null on Cloudflare blocks, network or parse errors.
     * No User-Agent override: m.webnovel.com redirects desktop agents, so the
     * app's default UA applies instead of [DESKTOP_UA].
     */
    private suspend fun fetchNextData(url: String): JsonObject? {
        var html = ""
        for (attempt in 0..1) {
            if (html.isNotEmpty()) break
            if (attempt > 0) delay(1500)
            try {
                val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
                val (code, ok, body) = withContext(Dispatchers.IO) {
                    client.newCall(req).execute().use { r ->
                        Triple(r.code, r.isSuccessful, r.body?.string().orEmpty())
                    }
                }
                if (code == 404) return null
                if (ok) html = body
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // network error: retry once
            }
        }
        if (html.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val script = Jsoup.parse(html)
                    .selectFirst("script#__NEXT_DATA__")?.data()
                if (script.isNullOrEmpty()) return@runCatching null
                (json.parseToJsonElement(script) as? JsonObject).obj("props")
            }.getOrNull()
        }
    }

    private suspend fun parseChapterList(body: String, mobile: JsonObject?): List<Episode> =
        withContext(Dispatchers.IO) {
            val d = Jsoup.parse(body)
            val mobileChapters = mobile.obj("initialState").obj("entities").obj("chapter")

            val chapters = mutableListOf<Episode>()
            for (volume in d.select(".volume-item")) {
                // "Volume 0 : Auxiliary Volume" or "Volume 1"
                val heading = volume.selectFirst("h4")?.text()
                    ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                val volumeName = volumeHeading.find(heading)?.let { m ->
                    val subtitle = m.groupValues.getOrNull(2)?.trim().orEmpty()
                    if (subtitle.isEmpty()) "Volume ${m.groupValues[1]}"
                    else "Volume ${m.groupValues[1]}: $subtitle"
                } ?: volumeAnywhere.find(volume.text())?.groupValues?.get(1)
                    ?.let { "Volume $it" }
                    ?: "Unknown Volume"

                for (li in volume.select("li")) {
                    val a = li.selectFirst("a") ?: continue
                    val chapterPath = a.attr("href").takeIf { it.isNotBlank() } ?: continue
                    val chapterName = "$volumeName: " +
                        (a.attr("title").trim().takeUnless { it.isEmpty() } ?: "No Title Found")
                    val locked = li.select("svg").isNotEmpty()
                    // Absolute epoch only: relative dates would change on every refresh
                    val dateMillis = li.attr("data-cid")
                        .takeUnless { it.isEmpty() }
                        ?.let { cid -> mobileChapters.obj(cid).long("publishTime") }
                        ?.takeIf { it > 0 }
                    chapters += Episode(
                        id = chapterPath.removePrefix("/"),
                        name = if (locked) "$chapterName 🔒" else chapterName,
                        number = null,
                        dateMillis = dateMillis,
                    )
                }
            }
            chapters
        }

    /** From mobile data, else desktop `g_data`, else desktop HTML. */
    private suspend fun buildNovel(
        novelPath: String,
        desktopBody: String,
        initData: JsonObject?,
    ): EntryDetails = withContext(Dispatchers.IO) {
        val book = initData.obj("bookInfo") ?: parseGData(desktopBody) ?: JsonObject(emptyMap())
        val d = Jsoup.parse(desktopBody)

        val genreRaw = buildList {
            book.str("categoryName")?.let { add(it) }
            book.arr("tagInfos")?.forEach { tag -> (tag as? JsonObject).str("tagName")?.let { add(it) } }
        }
        val genres = genreRaw.mapNotNull { g -> titleCase(g).takeIf { it.isNotBlank() } }.distinct()
        val genreList = genres.ifEmpty {
            listOfNotNull(
                d.selectFirst(".det-hd-detail > .det-hd-tag")?.attr("title")
                    ?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        val htmlStatus = d.select(".det-hd-detail svg")
            .firstOrNull { it.attr("title") == "Status" }
            ?.nextElementSibling()?.text()?.trim()
        val status = when (book.long("actionStatus")) {
            30L -> "Ongoing"
            50L -> "Completed"
            else -> null
        } ?: htmlStatus?.takeIf { it.isNotEmpty() } ?: "Unknown"

        val name = book.str("bookName")?.takeUnless { it.isBlank() }
            ?: d.selectFirst(".g_thumb > img")?.attr("alt")?.takeUnless { it.isBlank() }
            ?: throw IllegalStateException("Webnovel: no novel data found for /$novelPath")
        val bookId = bookIdOf(novelPath)
        val coverUpdateTime = book.long("coverUpdateTime")
        val cover = if (bookId != null && coverUpdateTime != null && coverUpdateTime > 0) {
            "https://book-pic.webnovel.com/bookcover/$bookId?imageMogr2/thumbnail/600x&imageId=$coverUpdateTime"
        } else {
            d.selectFirst(".g_thumb > img")?.attr("src")?.let { httpsAbs(it) }
        }

        val summary = book.str("description")?.replace(Regex("\\r\\n?"), "\n")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: synopsisOf(d)
            ?: "No Summary Found"

        val author = book.str("authorName")?.takeUnless { it.isBlank() }
            ?: namesOf(book.arr("authorItems")).joinToString(", ").takeIf { it.isNotEmpty() }
            ?: d.select(".det-info .c_s").firstOrNull { it.text().trim() == "Author:" }
                ?.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotEmpty() }

        EntryDetails(
            entry = Entry(id, novelPath, name, cover, ContentType.NOVEL),
            description = summary,
            authors = listOfNotNull(author),
            genres = genreList,
            status = status,
        )
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        d.select(".para-comment").remove()
        val paras = mutableListOf<String>()
        d.selectFirst(".cha-tit")?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { paras += it }
        d.selectFirst(".cha-words")?.let { paras += paragraphsOf(it) }
        return paras
    }

    // ---- helpers -----------------------------------------------------------

    /** Desktop `g_data.book= {...}`: JSON except for escapes like `\ `. */
    private fun parseGData(body: String): JsonObject? {
        val marker = body.indexOf("g_data.book=")
        if (marker < 0) return null
        val start = body.indexOf('{', marker)
        val end = body.indexOf(",g_data.", start)
        if (start < 0 || end < 0) return null
        return runCatching {
            val cleaned = body.substring(start, end).replace(strayEscape) { m ->
                if (m.groupValues[1].isNotEmpty()) m.value else ""
            }
            (json.parseToJsonElement(cleaned) as? JsonObject).obj("bookInfo")
        }.getOrNull()
    }

    private fun bookIdOf(novelPath: String): String? =
        bookIdRegex.find(novelPath)?.groupValues?.get(1)

    /** Webnovel covers are protocol-relative ("//img.webnovel.com/..."). */
    private fun httpsAbs(href: String?): String? {
        val h = href?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        return when {
            h.startsWith("http") -> h
            h.startsWith("//") -> "https:$h"
            else -> abs(h) ?: "https:$h"
        }
    }

    /** `.j_synopsis > p` with `<br>` turned into line breaks. */
    private fun synopsisOf(d: Document): String? {
        val p = d.selectFirst(".j_synopsis > p") ?: return null
        p.select("br").forEach { it.after("\n") }
        return p.wholeText().trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val MOBILE_SITE = "https://m.webnovel.com"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val bookIdRegex = Regex("""(\d+)/?$""")
        private val volumeHeading = Regex("""^Volume\s*(\d+)\s*(?::\s*(.+))?$""")
        private val volumeAnywhere = Regex("""Volume\s(\d+)""")
        private val strayEscape = Regex("""\\(["\\/bfnrtu])|\\""")
    }
}

// ---- JSON navigation (file-private) ----------------------------------------

private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

private fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray

private fun JsonObject?.str(key: String): String? =
    (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject?.long(key: String): Long? =
    (this?.get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

private fun namesOf(items: JsonArray?): List<String> =
    items?.mapNotNull { el -> (el as? JsonObject)?.str("name")?.trim()?.takeIf { it.isNotEmpty() } }
        ?: emptyList()

private val wordStart = Regex("""(^|\s)\S""")

/** "EASTERN" / "sci-fi" -> "Eastern" / "Sci-fi" */
private fun titleCase(s: String): String =
    s.trim().lowercase().replace(wordStart) { it.value.uppercase() }

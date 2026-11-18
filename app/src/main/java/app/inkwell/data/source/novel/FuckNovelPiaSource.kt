package app.inkwell.data.source.novel

import kotlinx.serialization.json.contentOrNull

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Port of LNReader's `FuckNovelpia` plugin (fucknovelpia.com). The site sits
 * behind Cloudflare with rate limiting; 429s are waited out once, 403/503
 * surface a "use WebView" error.
 */
class FuckNovelPiaSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "FuckNovelpia"
    override val name = "FuckNovelpia"
    override val baseUrl = "https://fucknovelpia.com"
    override val lang = "en"

    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun fetchHtml(url: String, isChapter: Boolean = false): String = withContext(Dispatchers.IO) {
        var response = client.newCall(request(url)).execute()
        if (response.code == 429) {
            val retryAfter = response.header("Retry-After")?.trim()?.toIntOrNull()
            val waitMs = (retryAfter?.coerceAtMost(10) ?: 2) * 1000L
            response.close()
            delay(waitMs)
            response = client.newCall(request(url)).execute()
        }
        response.use { r ->
            if (!r.isSuccessful) {
                if (r.code == 403 || r.code == 503) throw CloudflareException(r.request.url.toString())
                throw IllegalStateException("FuckNovelpia returned HTTP ${r.code}")
            }
            r.body?.string().orEmpty()
        }
    }

    /** Returns false once the site has silently clamped us past the real last page. */
    private fun hasRequestedPage(d: org.jsoup.nodes.Document, requestedPage: Int): Boolean {
        if (requestedPage <= 1) return true
        val activePage = d.select("div.pagination a.active").first()?.text()?.trim()?.toIntOrNull()
        return activePage != null && activePage == requestedPage
    }

    private fun parseNovelsList(d: org.jsoup.nodes.Document): List<Entry> {
        val novels = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        for (el in d.select(".card-book a")) {
            val href = el.attr("href")
            if (href.isEmpty()) continue
            val path = if (href.startsWith("/")) href.substring(1) else href
            if (!seen.add(path)) continue
            val img = el.selectFirst("img")
            val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
                ?: el.selectFirst(".title")?.text()?.trim().orEmpty()
            novels += Entry(id, path, title, img?.attr("src")?.let { abs(it) }, ContentType.NOVEL)
        }
        return novels
    }

    // ---- listing -----------------------------------------------------------

    private suspend fun parseLatestUpdates(): List<Entry> {
        val d = Jsoup.parse(fetchHtml(absPath("updates.php")))
        val novels = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        for (a in d.select("div.updates-list a[href]")) {
            val href = a.attr("href")
            if (!seen.add(href)) continue
            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img") ?: continue
            val cover = img.attr("src")
            val title = img.attr("alt")
            if (title.isBlank() || href.isBlank()) continue
            novels += Entry(id, href.removePrefix("/"), title, abs(cover), ContentType.NOVEL)
        }
        return novels
    }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val params = linkedMapOf(
            "q" to "", "sort" to "newest",
            "author" to "", "uploader" to "", "translator_group" to "",
            "country" to "", "year_from" to "", "year_to" to "",
            "status" to "", "language" to "", "read_only" to "and",
            "genre_mode" to "and", "tag_mode" to "and",
        )
        if (page > 1) params["page"] = page.toString()
        val link = absPath("search.php?" + params.entries.joinToString("&") {
            "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        })
        val d = Jsoup.parse(fetchHtml(link))
        if (!hasRequestedPage(d, page)) return PagedResult(emptyList(), false)
        val items = parseNovelsList(d)
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val items = parseLatestUpdates()
        return PagedResult(items, false)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val params = linkedMapOf(
            "q" to query, "author" to "", "uploader" to "", "translator_group" to "",
            "country" to "", "year_from" to "", "year_to" to "",
            "status" to "", "language" to "", "read_only" to "any",
            "sort" to "newest", "tag_mode" to "AND", "genre_mode" to "AND",
        )
        if (page > 1) params["page"] = page.toString()
        val link = absPath("search.php?" + params.entries.joinToString("&") {
            "${java.net.URLEncoder.encode(it.key, "UTF-8")}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
        })
        val d = Jsoup.parse(fetchHtml(link))
        if (!hasRequestedPage(d, page)) return PagedResult(emptyList(), false)
        val items = parseNovelsList(d)
        return PagedResult(items, items.isNotEmpty())
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val body = fetchHtml(absPath(entryId))
        val d = Jsoup.parse(body, absPath(entryId))

        val ldText = d.selectFirst("script[type=application/ld+json]")?.data()?.trim().orEmpty()
        val parsed = runCatching { parser.parseToJsonElement(ldText).jsonObject }.getOrNull()

        val name = parsed?.str("name")?.takeIf { it.isNotBlank() }
            ?: d.selectFirst("h1")?.text()?.trim().orEmpty()
        val cover = parsed?.str("image")?.takeIf { it.isNotBlank() }
        val summary = parsed?.str("description")?.takeIf { it.isNotBlank() }
            ?: d.selectFirst(".hero-summary")?.text()?.trim().orEmpty()

        var author = parsed?.get("author")?.let { el ->
            when (el) {
                is JsonArray -> el.mapNotNull { (it as? JsonObject)?.str("name") }
                    .filter { it.isNotBlank() }.joinToString(", ")
                is JsonObject -> el.str("name")?.takeIf { it.isNotBlank() }
                else -> null
            }
        }.orEmpty()
        if (author.isEmpty()) {
            author = d.select(".info-list li").first()?.text()
                ?.replace(Regex("^Author:\\s*", RegexOption.IGNORE_CASE), "")?.trim().orEmpty()
        }

        var genres = parsed?.get("genre")?.let { el ->
            when (el) {
                is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }
                    .filter { it.isNotBlank() }.joinToString(",")
                is JsonPrimitive -> el.contentOrNull.orEmpty()
                else -> ""
            }
        }.orEmpty()
        if (genres.isEmpty()) {
            genres = d.select(".genre-pill").eachText().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
        }

        val status = when (d.selectFirst(".status-badge")?.text()?.trim()?.lowercase()) {
            "ongoing" -> "Ongoing"
            "completed" -> "Completed"
            "hiatus" -> "On Hiatus"
            "dropped" -> "Cancelled"
            else -> null
        }

        val chapters = mutableListOf<Episode>()
        for (el in d.select("#chapter-list li")) {
            val href = el.selectFirst("a")?.attr("href")?.trim().orEmpty()
            val path = if (href.startsWith("/")) href.substring(1) else href
            val name = el.selectFirst(".chapter-item-main")?.text()?.trim().orEmpty()
            if (path.isEmpty() || name.isEmpty()) continue
            val flagged = el.selectFirst(".chapter-item-flag") != null
            chapters += Episode(
                id = path,
                name = if (flagged) "$name [IMG]" else name,
                number = el.attr("data-ch").toFloatOrNull(),
                dateMillis = null,
            )
        }

        return EntryDetails(
            entry = Entry(id, entryId, name.ifEmpty { "Untitled" }, cover?.let { abs(it) }, ContentType.NOVEL),
            description = summary.ifEmpty { null },
            authors = author.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            genres = genres.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val body = fetchHtml(absPath(episodeId), isChapter = true)
        val d = Jsoup.parse(body, absPath(episodeId))
        val chapter = d.selectFirst(".reader")
            ?: throw IllegalStateException("Chapter content not found in page")
        chapter.select(".reader-nav").remove()
        chapter.select("script").remove()
        chapter.select("style").remove()
        return paragraphsOf(chapter)
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull
}

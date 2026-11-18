package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

class LuminareTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "luminaretranslations"
    override val name = "Luminare Translations"
    override val baseUrl = "https://luminaretranslations.com"
    override val lang = "en"

    override val headers = mapOf(
        "Accept" to "application/json",
        "Referer" to "https://luminaretranslations.com/",
    )
    override val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun api(path: String) = json("$baseUrl/wp-json/" + path.trimStart('/'))

    private fun stripHtml(html: String): String = Jsoup.parse(html).text().trim()

    private fun decodeEntities(text: String): String = Parser.unescapeEntities(text, false)

    private fun itemOf(o: JsonObject): Entry? {
        val slug = o["slug"]?.jsonPrimitive?.contentOrNull ?: return null
        val title = decodeEntities(o["title"]?.jsonPrimitive?.contentOrNull ?: slug).trim()
        val cover = o["cover_image"]?.jsonPrimitive?.contentOrNull
            ?: o["cover_url"]?.jsonPrimitive?.contentOrNull
        return Entry(sourceId = id, id = "series/$slug", title = title, coverUrl = abs(cover), type = ContentType.NOVEL)
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> = seriesPage(page, "popular")

    override suspend fun latest(page: Int): PagedResult<Entry> = seriesPage(page, "latest")

    private suspend fun seriesPage(page: Int, sort: String): PagedResult<Entry> {
        val root = api("yarnovel/v1/series?page=$page&per_page=20&sort=$sort").jsonObject
        val items = root["data"]?.jsonArray?.mapNotNull { (it as? JsonObject)?.let(::itemOf) } ?: emptyList()
        val totalPages = root["meta"]?.jsonObject?.get("total_pages")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        return PagedResult(items, totalPages?.let { page < it } ?: items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val hits = api("yarnovel/v1/search?q=$q&page=$page").jsonArray
        val items = hits.mapNotNull { (it as? JsonObject)?.let(::itemOf) }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.trimStart('/').removePrefix("series/").trim('/')
        val wp = api("wp/v2/series?slug=" + java.net.URLEncoder.encode(slug, "UTF-8")).jsonArray
        val series = wp.firstOrNull()?.jsonObject ?: throw IllegalStateException("Luminare: novel not found")
        val title = stripHtml(series["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull ?: slug)
        val summary = stripHtml(series["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull ?: "")
            .ifBlank { stripHtml(series["excerpt"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull ?: "") }

        var cover: String? = null
        val mediaId = series["featured_media"]?.jsonPrimitive?.longOrNull ?: 0L
        if (mediaId != 0L) {
            runCatching {
                cover = api("wp/v2/media/$mediaId").jsonObject["source_url"]?.jsonPrimitive?.contentOrNull
            }
        }

        val listItem = findSeriesListItem(slug, title)
        listItem?.get("cover_image")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { cover = it }

        val genres = listItem?.get("genres")?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        val author = series["meta"]?.jsonObject?.get("author")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: listItem?.get("author")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val status = mapStatus(
            listItem?.get("status")?.jsonPrimitive?.contentOrNull,
            series["class_list"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content },
        )

        val chapters = chaptersFrom(listItem)
        return EntryDetails(
            entry = Entry(id, entryId, title, abs(cover), ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = listOfNotNull(author),
            genres = genres,
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId] ?: emptyList()
    }

    private suspend fun findSeriesListItem(slug: String, titleHint: String?): JsonObject? {
        val queries = listOfNotNull(
            titleHint?.takeIf { it.trim().length > 2 },
            slug.split('-').take(4).joinToString(" ").takeIf { it.trim().length > 2 },
            slug.split('-').firstOrNull { it.length > 5 },
        )
        for (q in queries) {
            val page = api(
                "yarnovel/v1/series?search=" + java.net.URLEncoder.encode(q, "UTF-8") + "&per_page=50&page=1",
            ).jsonObject
            val match = page["data"]?.jsonArray
                ?.firstOrNull { (it as? JsonObject)?.get("slug")?.jsonPrimitive?.contentOrNull == slug } as? JsonObject
            if (match != null) return match
        }
        // Fallback: page through the catalog (site is small).
        for (pageNo in 1..10) {
            val page = api("yarnovel/v1/series?page=$pageNo&per_page=50&sort=latest").jsonObject
            val data = page["data"]?.jsonArray ?: break
            val match = data.firstOrNull { (it as? JsonObject)?.get("slug")?.jsonPrimitive?.contentOrNull == slug } as? JsonObject
            if (match != null) return match
            val totalPages = page["meta"]?.jsonObject?.get("total_pages")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
            if (pageNo >= totalPages) break
        }
        return null
    }

    private suspend fun chaptersFrom(listItem: JsonObject?): List<Episode> {
        val chapterId = listItem?.get("latest_chapter")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull
            ?: return emptyList()
        val grouped = runCatching {
            api("yarnovel/v1/chapters/$chapterId/grouped-chapters").jsonObject
        }.getOrNull() ?: return emptyList()

        val episodes = mutableListOf<Episode>()
        var n = 0
        for (group in grouped["groups"]?.jsonArray ?: emptyList()) {
            val g = group.jsonObject
            val groupSlug = g["slug"]?.jsonPrimitive?.contentOrNull
            val groupName =
                if (groupSlug != null && groupSlug != "__ungrouped__") g["name"]?.jsonPrimitive?.contentOrNull ?: "" else ""
            for (ch in g["chapters"]?.jsonArray ?: emptyList()) {
                val c = ch.jsonObject
                n += 1
                val number = c["number"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                val rawTitle = c["title"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?.let { decodeEntities(it) }
                    ?: "Chapter ${number ?: n.toFloat()}"
                val chapterName = if (groupName.isNotBlank()) "$groupName: $rawTitle" else rawTitle
                val date = c["published_at"]?.jsonPrimitive?.contentOrNull ?: c["date"]?.jsonPrimitive?.contentOrNull
                episodes += Episode(
                    id = "chapter/${c["id"]?.jsonPrimitive?.contentOrNull ?: n}",
                    name = chapterName,
                    number = number,
                    dateMillis = parseDate(date),
                )
            }
        }
        return episodes
    }

    private fun mapStatus(raw: String?, classList: List<String>?): String? {
        val fromClass = classList
            ?.firstOrNull { it.startsWith("series_status-") }
            ?.removePrefix("series_status-")
        val value = (raw ?: fromClass ?: "").lowercase()
        return when {
            value.contains("complete") || value == "end" || value == "finished" -> "Completed"
            value.contains("ongoing") || value.contains("on-going") -> "Ongoing"
            value.contains("hiatus") || value.contains("hold") -> "On Hiatus"
            value.contains("cancel") || value.contains("drop") -> "Cancelled"
            else -> null
        }
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val chapterId = episodeId.trimStart('/').removePrefix("chapter/").trim('/')
        val chapter = runCatching {
            api("yarnovel/v1/chapters/$chapterId").jsonObject["data"]?.jsonObject
        }.getOrNull() ?: return emptyList()

        var html = chapter["content"]?.jsonPrimitive?.contentOrNull ?: ""
        val pages = chapter["pages"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content }
            ?: emptyList()
        if (pages.isNotEmpty() && !Regex("<img[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(html)) {
            html += pages.joinToString("") { "<img src=\"$it\" />" }
        }
        if (html.isBlank()) return emptyList()

        val d = Jsoup.parseBodyFragment(html)
        d.select("script, style, noscript").remove()
        d.select("img").forEach { img ->
            val src = img.attr("src").ifBlank { img.attr("data-src") }
            if (src.startsWith("/")) abs(src)?.let { img.attr("src", it) }
        }
        return paragraphsOf(d.body())
    }
}

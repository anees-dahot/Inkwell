package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.util.Base64

/**
 * Port of LNReader's `fictionzone` plugin. The site fronts its API with an
 * "__api_party" proxy that takes the real API path in a JSON POST body.
 */
class FictionZoneSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "fictionzone"
    override val name = "Fiction Zone"
    override val baseUrl = "https://fictionzone.net"
    override val lang = "en"

    private data class Details(
        val novelId: String?,
        val title: String,
        val cover: String?,
        val genres: List<String>,
        val status: String?,
        val author: String?,
        val summary: String?,
    )

    // ---- proxy API ---------------------------------------------------------

    private fun jstr(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private suspend fun getData(apiPath: String): JsonObject {
        val body = "{\"path\":${jstr("/" + apiPath.trimStart('/'))}," +
            "\"headers\":[[\"content-type\",\"application/json\"]," +
            "[\"x-request-time\",\"${java.time.Instant.now()}\"]],\"method\":\"GET\"}"
        val root = jsonPost(absPath("api/__api_party/fictionzone"), body)
        return root.jsonObject["data"]?.jsonObject
            ?: throw IllegalStateException("Failed to fetch data from $apiPath")
    }

    private fun decodeCover(image: String?): String? {
        if (image.isNullOrBlank()) return null
        if (image.startsWith("http://") || image.startsWith("https://")) return image
        val decoded = runCatching { String(Base64.getDecoder().decode(image)) }.getOrNull()
        return decoded?.takeIf { it.startsWith("http") }
    }

    // ---- path helpers ------------------------------------------------------

    private fun formatNovelPath(slug: String): String {
        val clean = slug.trim().replace(Regex("^(omniportal|novel)/", RegexOption.IGNORE_CASE), "")
        val omni = Regex("^([a-zA-Z0-9]+)[-/\\s]+([0-9]+)$").find(clean)
        if (omni != null) return "omniportal/${omni.groupValues[1].lowercase()}/${omni.groupValues[2]}"
        return "novel/$clean"
    }

    private fun pathToSlug(novelPath: String): String {
        var clean = runCatching { java.net.URLDecoder.decode(novelPath.trim(), "UTF-8") }.getOrDefault(novelPath.trim())
        clean = clean.replace(Regex("^(https?://)?([^/]+\\.)?fictionzone\\.net/?", RegexOption.IGNORE_CASE), "")
        clean = clean.trimStart('/').substringBefore('?').substringBefore('#').trimEnd('/')
        clean = clean.replace(Regex("^(omniportal|novel)/", RegexOption.IGNORE_CASE), "")
        val omni = Regex("^([a-zA-Z0-9]+)[/\\-\\s]+([0-9]+)$").find(clean)
        if (omni != null) return "${omni.groupValues[1].lowercase()}-${omni.groupValues[2]}"
        if (Regex("^[0-9]{8,}$").matches(clean)) return "fq-$clean"
        return clean
    }

    // ---- listing -----------------------------------------------------------

    private suspend fun browse(page: Int, latest: Boolean, searchTerm: String? = null): PagedResult<Entry> {
        val params = mutableListOf("page" to page.toString(), "page_size" to "20")
        if (searchTerm != null) {
            params += listOf("search" to searchTerm, "search_in_synopsis" to "true", "sort_by" to "bookmark_count")
        } else {
            params += "sort_by" to if (latest) "created_at" else "bookmark_count"
        }
        params += listOf("sort_order" to "desc", "include_genres" to "true")
        val query = params.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val items = getPage("platform/browse?$query")
        return PagedResult(items, items.isNotEmpty())
    }

    private suspend fun getPage(apiPath: String): List<Entry> {
        val novels = getData(apiPath)["novels"]?.jsonArray ?: return emptyList()
        return novels.mapNotNull { el ->
            val o = el.jsonObject
            val title = o.str("title") ?: return@mapNotNull null
            val slug = o.str("slug") ?: return@mapNotNull null
            Entry(id, formatNovelPath(slug), title, decodeCover(o.str("image")), ContentType.NOVEL)
        }
    }

    override suspend fun popular(page: Int) = browse(page, latest = false)
    override suspend fun latest(page: Int) = browse(page, latest = true)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val term = query.trim()
        if (page == 1 && term.isNotEmpty()) {
            val isUrlOrId = term.contains("fictionzone.net") || term.contains("omniportal") ||
                term.contains("novel/") || Regex("^[a-zA-Z0-9]+[-_/][0-9]+$").containsMatchIn(term) ||
                Regex("^[0-9]{8,}$").containsMatchIn(term)
            if (isUrlOrId) {
                val path = formatNovelPath(pathToSlug(term))
                val direct = runCatching { loadDetails(path).first }.getOrNull()
                if (direct != null) {
                    return PagedResult(listOf(Entry(id, path, direct.title, direct.cover, ContentType.NOVEL)), false)
                }
            }
        }
        return browse(page, latest = false, searchTerm = term)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val (d, chapters) = loadDetails(entryId)
        return EntryDetails(
            entry = Entry(id, entryId, d.title, d.cover, ContentType.NOVEL),
            description = d.summary,
            authors = listOfNotNull(d.author),
            genres = d.genres,
            status = d.status,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        return loadDetails(entryId).second.also { cachedChapters[entryId] = it }
    }

    private suspend fun loadDetails(novelPath: String): Pair<Details, List<Episode>> {
        val novelSlug = pathToSlug(novelPath)
        val omni = Regex("^([a-zA-Z0-9]+)[-_]([0-9]+)$").find(novelSlug)
        val srcId = omni?.groupValues?.get(1)?.lowercase().orEmpty()
        val srcKey = omni?.groupValues?.get(2).orEmpty()

        var details: Details? = null
        runCatching {
            val data = getData("platform/novel-details?slug=$novelSlug")
            val title = data.str("title") ?: return@runCatching
            val genres = buildList {
                data.arr("genres")?.forEach { g -> (g as? JsonObject)?.str("name")?.let { add(it) } }
                data.arr("tags")?.forEach { g -> (g as? JsonObject)?.str("name")?.let { add(it) } }
            }.filter { it.isNotBlank() }
            val author = data.arr("contributors")
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.str("role") == "author" }
                ?.str("display_name")
            details = Details(
                novelId = data.str("id"),
                title = title,
                cover = decodeCover(data.str("image")),
                genres = genres,
                status = when (data.str("status")?.toIntOrNull()) { 1 -> "Ongoing"; 2 -> "Completed"; else -> null },
                author = author?.takeIf { it.isNotBlank() },
                summary = data.str("synopsis")?.takeIf { it.isNotBlank() },
            )
        }
        if (details == null && srcId == "fq") details = fanqieMeta(srcKey)
        val d = details ?: throw IllegalStateException("Novel not found: $novelPath")

        var chapters = if (srcId == "fq") fanqieChapters(srcKey, novelPath) else emptyList()
        if (chapters.isEmpty() && d.novelId != null) chapters = platformChapters(d.novelId, novelPath)
        return d to chapters
    }

    private suspend fun platformChapters(novelId: String, novelPath: String): List<Episode> = runCatching {
        val chapters = getData("platform/chapter-lists?novel_id=$novelId").arr("chapters")
            ?: return@runCatching emptyList()
        chapters.mapIndexedNotNull { idx, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val cid = o.str("chapter_id") ?: return@mapIndexedNotNull null
            val num = o.num("chapter_number") ?: (idx + 1).toFloat()
            val name = o.str("title")?.takeIf { it.isNotBlank() } ?: "Chapter ${num.toInt()}"
            Episode(
                id = "$novelPath/$cid|/platform/chapter-content?novel_id=$novelId&chapter_id=$cid",
                name = name,
                number = num,
                dateMillis = parseDate(o.str("published_date")),
            )
        }
    }.getOrDefault(emptyList())

    // ---- fanqienovel.com fallback (Chinese "fq" novels) ---------------------

    private suspend fun fanqieMeta(bookId: String): Details? = runCatching {
        val raw = fetch("https://fanqienovel.com/page/$bookId")
        var title = Regex("<title>([^<]+)</title>").find(raw)?.groupValues?.get(1)?.trim() ?: "Novel $bookId"
        title = title.replace(Regex("完整版.*$"), "").trim().replace(Regex("_.*$"), "").trim()
        val summary = Regex("\"description\":\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
            ?.replace("\\n", "\n")
            ?.replace(Regex("番茄小说提供.*?精彩小说尽在番茄小说网。"), "")
            ?.trim()?.takeIf { it.isNotEmpty() }
        val cover = Regex("\"images\":\\[\"([^\"]+)\"\\]").find(raw)?.groupValues?.get(1)
            ?.replace("\\u002F", "/")?.takeIf { it.isNotEmpty() }
        val author = Regex("\"authorName\":\"([^\"]+)\"").find(raw)?.groupValues?.get(1)
            ?.trim()?.takeIf { it.isNotEmpty() }
        Details(bookId, title.ifBlank { "Novel $bookId" }, cover, emptyList(), "Ongoing", author, summary)
    }.getOrNull()

    private suspend fun fanqieChapters(bookId: String, novelPath: String): List<Episode> = runCatching {
        val root = json("https://fanqienovel.com/api/reader/directory/detail?bookId=$bookId")
        val volumes = root.jsonObject["data"]?.jsonObject?.arr("chapterListWithVolume")
            ?: return@runCatching emptyList()
        val chapters = mutableListOf<Episode>()
        var order = 1
        for (vol in volumes) {
            val volArr = vol as? JsonArray ?: continue
            for (itemEl in volArr) {
                val item = itemEl as? JsonObject ?: continue
                val itemId = item.str("itemId") ?: continue
                val chNumber = item.str("realChapterOrder")?.toIntOrNull()?.takeIf { it != 0 } ?: order++
                val rawTitle = item.str("title")?.trim().orEmpty()
                val cleanTitle = rawTitle.replace(Regex("^第\\s*[0-9一二三四五六七八九十百千万]+\\s*章\\s*"), "")
                val release = item.str("firstPassTime")?.toLongOrNull()?.times(1000)
                chapters += Episode(
                    id = "$novelPath/$itemId|/omniportal/chapters/content?source_id=fq&source_key=$bookId" +
                        "&chapter_id=$itemId&translate=en&engine=google-trans",
                    name = if (cleanTitle.isNotEmpty()) "Chapter $chNumber: $cleanTitle" else "Chapter $chNumber",
                    number = chNumber.toFloat(),
                    dateMillis = release,
                )
            }
        }
        chapters
    }.getOrDefault(emptyList())

    // ---- chapter ------------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val apiPath = if (episodeId.contains('|')) episodeId.substringAfter('|') else episodeId
        val content = getData(apiPath).str("content")
            ?: throw IllegalStateException("Chapter content is empty or unavailable.")
        val html = content.split(Regex("\r?\n+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("</p><p>", prefix = "<p>", postfix = "</p>")
        return paragraphsOf(Jsoup.parseBodyFragment(html).body())
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(key: String): Float? =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.floatOrNull

    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
}

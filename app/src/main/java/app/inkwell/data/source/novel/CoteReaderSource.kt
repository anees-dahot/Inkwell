package app.inkwell.data.source.novel

import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperNovelSource
import app.inkwell.data.source.paragraphsOf
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * COTE Reader (cote-reader.me). A JSON API (`/api/novels`, paged) plus static
 * asset files. "Volumes" are the chapter units; two webnovel ids (ORV, World)
 * use dedicated chapter summary/chapter files instead. Canonical ids read
 * whole volumes from `/assets/cache/<id>/<volume>.json`.
 */
class CoteReaderSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "cotereader"
    override val name = "COTE Reader"
    override val baseUrl = "https://cote-reader.me"
    override val lang = "en"

    private val canonicalIds = setOf(
        "cote", "lotm", "rezero", "bunny-girl", "mushoku-tensei", "orv", "world",
        "reverend-insanity", "apothecary-diaries", "tensura", "tbate", "eightysix",
        "monogatari",
    )

    private val aliasMap = mapOf(
        "4557" to "cote",
        "3336" to "mushoku-tensei",
        "3486" to "bunny-girl",
        "1014" to "monogatari",
        "3343" to "rezero",
        "6547" to "eightysix",
        "3580" to "tensura",
        "3768" to "apothecary-diaries",
        "10839" to "lotm",
        "200298" to "2958",
    )

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject?.str(key: String): String? = this?.get(key).str()

    private fun formatCover(cover: String?): String? {
        if (cover.isNullOrEmpty()) return null
        if (cover.startsWith("http://") || cover.startsWith("https://")) return cover
        if (cover.startsWith("//")) return "https:$cover"
        if (cover.startsWith("/")) return baseUrl + cover
        return "$baseUrl/$cover"
    }

    private fun cleanId(novelPath: String): String = novelPath
        .replace(Regex("^/?novel/", RegexOption.IGNORE_CASE), "")
        .trimStart('/')
        .split('/')[0]
        .trim()

    private fun dedup(items: List<JsonObject>): List<Entry> {
        val seenPaths = HashSet<String>()
        val seenTitles = HashSet<String>()
        val result = mutableListOf<Entry>()
        for (o in items) {
            val novelId = o.str("id") ?: continue
            val title = o.str("title") ?: continue
            val targetId = aliasMap[novelId] ?: novelId
            val path = "novel/$targetId"
            val normTitle = title.lowercase().replace(Regex("[^a-z0-9]"), "")
            if (!seenPaths.add(path) || !seenTitles.add(normTitle)) continue
            result += Entry(id, path, title, formatCover(o.str("cover")), ContentType.NOVEL)
        }
        return result
    }

    // ---- listing -----------------------------------------------------------

    private suspend fun listPage(page: Int, query: String?): PagedResult<Entry> {
        val url = absPath("api/novels").toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "20")
            .apply { query?.let { addQueryParameter("q", it) } }
            .build()
        val items = itemsOf(json(url))
        return PagedResult(dedup(items), items.isNotEmpty())
    }

    private fun itemsOf(root: JsonElement): List<JsonObject> =
        ((root as? JsonObject)?.get("items") as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            .orEmpty()

    override suspend fun popular(page: Int): PagedResult<Entry> = listPage(page, null)

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> =
        listPage(page, query)

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val rawId = cleanId(entryId)
        val novelId = aliasMap[rawId] ?: rawId
        val data = json(absPath("api/novels/$novelId")).jsonObject

        val genres = ((data["tags"] as? JsonArray)?.mapNotNull { it.str() })
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(", ")
            ?: (data["genres"] as? JsonArray)?.mapNotNull { it.str() }?.joinToString(", ")?.takeIf { it.isNotEmpty() }

        val chapters = webnovelChapters(novelId, data)
            ?: volumeChapters(novelId, data)
        return EntryDetails(
            entry = Entry(id, "novel/$novelId", data.str("title") ?: "Untitled", formatCover(data.str("cover")), ContentType.NOVEL),
            description = data.str("description")?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(data.str("author")?.takeIf { it.isNotEmpty() }),
            genres = genres?.split(", ")?.filter { it.isNotEmpty() }.orEmpty(),
            status = "Ongoing",
        ).also { cachedChapters["novel/$novelId"] = chapters }
    }

    /** Special handling for webnovels with dedicated chapter summary files. */
    private suspend fun webnovelChapters(novelId: String, data: JsonObject): List<Episode>? {
        if (novelId != "orv" && novelId != "world") return null
        val summary = runCatching {
            json(absPath("assets/$novelId/summary.json")).jsonObject
        }.getOrNull() ?: return null
        val all = buildList {
            addAll((summary["mainChapters"] as? JsonArray).orEmpty())
            addAll((summary["contChapters"] as? JsonArray).orEmpty())
            addAll((summary["sideChapters"] as? JsonArray).orEmpty())
        }
        if (all.isEmpty()) return null
        return all.mapIndexed { idx, el ->
            val ch = el.jsonObject
            Episode(
                id = "webnovel/$novelId/${ch.str("id").orEmpty()}",
                name = ch.str("title")?.takeIf { it.isNotEmpty() } ?: "Chapter ${idx + 1}",
                number = (idx + 1).toFloat(),
                dateMillis = null,
            )
        }
    }

    private fun volumeChapters(novelId: String, data: JsonObject): List<Episode> {
        val volumes = (data["volumes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val isCanonical = canonicalIds.contains(novelId.lowercase())
        return volumes.mapIndexed { index, volume ->
            val position = index + 1
            val rawTitle = volume.str("title")?.takeIf { it.isNotEmpty() } ?: "Volume $position"
            Episode(
                id = if (isCanonical) "canonical/$novelId/${volume.str("id").orEmpty()}"
                else "api/novels/$novelId/volume/${volume.str("id").orEmpty()}",
                name = "Vol. ${position.toString().padStart(2, '0')}: $rawTitle",
                number = position.toFloat(),
                dateMillis = null,
            )
        }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        val rawId = cleanId(entryId)
        val novelId = aliasMap[rawId] ?: rawId
        val key = "novel/$novelId"
        cachedChapters[key]?.let { return it }
        val data = json(absPath("api/novels/$novelId")).jsonObject
        val chapters = webnovelChapters(novelId, data) ?: volumeChapters(novelId, data)
        return chapters.also { cachedChapters[key] = it }
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val path = "/" + episodeId.trim('/')
        val html = when {
            path.startsWith("/webnovel/") -> webnovelHtml(path)
            path.startsWith("/canonical/") -> canonicalHtml(path)
            path.startsWith("/api/novels/") -> volumeHtml(path)
            else -> runCatching {
                fetch(if (path.startsWith("http")) path else absPath(path.trimStart('/')))
            }.getOrDefault("")
        }
        return paragraphsOf(Jsoup.parseBodyFragment(sanitize(html)).body())
    }

    private suspend fun webnovelHtml(path: String): String {
        val parts = path.removePrefix("/webnovel/").split('/')
        val novelId = parts.getOrNull(0).orEmpty()
        val chapterId = parts.getOrNull(1).orEmpty()
        val data = json(absPath("assets/$novelId/chapters/$chapterId.json")).jsonObject
        return data.str("contentHtml") ?: data.str("content").orEmpty()
    }

    private suspend fun canonicalHtml(path: String): String {
        val parts = path.removePrefix("/canonical/").split('/')
        val novelId = parts.getOrNull(0).orEmpty()
        val volumeId = parts.getOrNull(1).orEmpty()
        val chapterKey = parts.getOrNull(2)
        val root = runCatching { json(absPath("assets/cache/$novelId/$volumeId.json")).jsonObject }
            .getOrNull() ?: return ""
        val chapter = chapterKey?.let { root[it] as? JsonObject }
        if (chapter != null) return chapter.str("content").orEmpty()
        return sectionHtml(root, { it.str("title") }, { it.str("content").orEmpty() })
    }

    private suspend fun volumeHtml(path: String): String {
        val parts = path.removePrefix("/api/novels/").split('/')
        val novelId = parts.getOrNull(0).orEmpty()
        val volumeId = parts.getOrNull(2).orEmpty()
        val chapterIndex = parts.getOrNull(3)
        val volUrl = absPath("api/novels/$novelId/volume/$volumeId")

        var lastError: Exception? = null
        var volData: JsonObject? = null
        for (attempt in 0 until 3) {
            try {
                volData = json(volUrl).jsonObject
                break
            } catch (e: Exception) {
                lastError = e
                if (attempt < 2) delay(1000L * (attempt + 1))
            }
        }
        val data = volData ?: throw lastError
            ?: IllegalStateException("Failed to load volume: connection was interrupted")

        val chaptersObj = data["chapters"] as? JsonObject
        val chapter = chapterIndex?.let { chaptersObj?.get(it) as? JsonObject }
        if (chapter != null) return chapter.str("content").orEmpty()

        val toc = data["toc"] as? JsonArray
        return if (toc != null && toc.isNotEmpty()) {
            toc.joinToString("\n<hr />\n") { el ->
                val item = el.jsonObject
                val idx = item.str("chapterIndex")
                val content = (chaptersObj?.get(idx) as? JsonObject)?.str("content").orEmpty()
                "<section>${item.str("title").orEmpty()}$content</section>"
            }
        } else {
            sectionHtml(chaptersObj ?: JsonObject(emptyMap()), { null }, { it.str("content").orEmpty() })
        }
    }

    private fun sectionHtml(
        obj: JsonObject,
        titleOf: (JsonObject) -> String?,
        contentOf: (JsonObject) -> String,
    ): String = obj.keys
        .sortedBy { it.toDoubleOrNull() ?: Double.MAX_VALUE }
        .map { key -> key to (obj[key] as? JsonObject) }
        .filter { it.second != null }
        .joinToString("\n<hr />\n") { (key, item) ->
            val title = titleOf(item!!) ?: "Chapter $key"
            "<section><p>$title</p>${contentOf(item)}</section>"
        }

    private fun sanitize(html: String): String {
        var cleaned = html.replace(
            Regex("(<img[^>]+src=[\"'])(/assets/[^\"']+)([\"'])", RegexOption.IGNORE_CASE),
            "$1$baseUrl$2$3",
        )
        cleaned = cleaned.replace(
            Regex("<picture>[\\s\\S]*?<img([^>]+src=[\"']https?://[^\"']+[\"'][^>]*)>[\\s\\S]*?</picture>", RegexOption.IGNORE_CASE),
            "<img$1>",
        )
        return cleaned
    }
}

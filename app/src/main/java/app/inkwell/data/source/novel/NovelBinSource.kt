package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Novel Bin (novelbin.xyz) — the whole catalogue is pre-rendered JSON lists on
 * BunnyCDN (boxnovela.b-cdn.net), which requires the site's Origin header.
 */
class NovelBinSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelbin"
    override val name = "Novel Bin"
    override val baseUrl = "https://novelbin.xyz"
    override val lang = "en"

    private val apiBase = "https://boxnovela.b-cdn.net"
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    override val headers = mapOf("Origin" to baseUrl)

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        // The popular list is a single, unpaged list.
        if (page > 1) return PagedResult(emptyList(), false)
        val popular = fetchList("popular")
        val items = popular?.jsonArray?.map { toNovelItem(it.jsonObject) }.orEmpty()
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        val pageData = fetchList("latest_$page")?.jsonObject
        val items = pageData?.get("data")?.jsonArray?.map { toNovelItem(it.jsonObject) }.orEmpty()
        val hasMore = pageData?.get("has_more")?.let { it is JsonPrimitive && it.content == "true" } ?: false
        return PagedResult(items, hasMore)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val index = searchIndex()
        val term = query.lowercase().trim()
        val results = if (term.isEmpty()) index else index.filter { it.searchText.contains(term) }
        val start = (page - 1) * searchPageSize
        val items = results.drop(start).take(searchPageSize).map { it.entry }
        return PagedResult(items, results.size > start + searchPageSize)
    }

    private val searchPageSize = 50

    private data class IndexedNovel(val entry: Entry, val searchText: String)

    @Volatile
    private var searchIndexCache: List<IndexedNovel>? = null

    @kotlinx.serialization.Serializable
    private data class SearchRow(
        val slug: String? = null,
        val title: String? = null,
        val alternative_titles: String? = null,
        val cover: String? = null,
    )

    /** The search list is ~15MB: stream-decode into small rows instead of a JsonElement tree. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private suspend fun fetchSearchRows(file: String): List<SearchRow>? = withContext(Dispatchers.IO) {
        client.newCall(request("$apiBase/$file")).execute().use { r ->
            if (r.code == 403 || r.code == 404) return@withContext null
            check(r.isSuccessful) { "$name HTTP ${r.code}" }
            r.body?.byteStream()?.use { parser.decodeFromStream<List<SearchRow>>(it) }
        }
    }

    private suspend fun searchIndex(): List<IndexedNovel> {
        searchIndexCache?.let { return it }
        val rows = fetchSearchRows("lists/web_search.json") ?: fetchSearchRows("lists/search.json") ?: emptyList()
        val built = rows.mapNotNull { o ->
            val slug = o.slug ?: return@mapNotNull null
            val title = o.title ?: return@mapNotNull null
            IndexedNovel(
                entry = Entry(
                    sourceId = id,
                    id = "novel/$slug",
                    title = title,
                    coverUrl = abs(o.cover),
                    type = ContentType.NOVEL,
                ),
                searchText = "$title\u0000${o.alternative_titles.orEmpty()}".lowercase(),
            )
        }
        searchIndexCache = built
        return built
    }

    // ---- details -----------------------------------------------------------

    private fun getSlug(path: String): String =
        path.trimStart('/').split('/')[1]

    private var novelIdCache: HashMap<String, Long> = HashMap()

    override suspend fun details(entryId: String): EntryDetails {
        val slug = getSlug(entryId)
        val detail = fetchJson("novel/$slug.json")?.jsonObject
            ?: throw IllegalStateException("Novel not found, it may have been removed from this source.")
        val nid = detail["id"]?.asStringOrNull()?.toLongOrNull()
            ?: throw IllegalStateException("Novel not found, it may have been removed from this source.")
        novelIdCache[slug] = nid

        val status = when (detail["status"]?.asStringOrNull()) {
            "Ongoing" -> "Ongoing"
            "Completed" -> "Completed"
            else -> null
        }
        val chapters = chapterList(slug, nid)
        return EntryDetails(
            entry = Entry(
                sourceId = id,
                id = entryId,
                title = detail["title"]?.asStringOrNull() ?: "Untitled",
                coverUrl = abs(detail["cover"]?.asStringOrNull()),
                type = ContentType.NOVEL,
            ),
            description = detail["description"]?.asStringOrNull(),
            authors = listOfNotNull(detail["author"]?.asStringOrNull()?.takeIf { !it.isBlank() }),
            genres = detail["genres"]?.jsonArray
                ?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content }.orEmpty(),
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    private suspend fun chapterList(slug: String, novelId: Long): List<Episode> {
        val chapters = fetchJson("chapters/$novelId.json")?.jsonArray.orEmpty()
            .mapNotNull { el ->
                val o = el.jsonObject
                // older lists use "index", newer ones "tocIndex" (with "id" as a fallback)
                val index = (o["index"] ?: o["tocIndex"] ?: o["id"])?.asStringOrNull()?.toIntOrNull() ?: return@mapNotNull null
                val title = o["title"]?.asStringOrNull().orEmpty()
                Triple(slug, index, title)
            }
        return chapters.sortedBy { it.second }.map { (slug, index, title) ->
            Episode(
                id = "read/$slug/chapter-${index + 1}",
                name = title.ifEmpty { "Chapter ${index + 1}" },
                number = (index + 1).toFloat(),
                dateMillis = null,
            )
        }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val slug = getSlug(episodeId)
        val chapterSlug = episodeId.split('/').lastOrNull().orEmpty()
        val novelId = novelIdCache[slug]
            ?: fetchJson("novel/$slug.json")?.jsonObject?.get("id")?.asStringOrNull()?.toLongOrNull()
            ?: throw IllegalStateException("Novel not found, it may have been removed from this source.")
        novelIdCache[slug] = novelId

        val index = chapterSlug.removePrefix("chapter-").toIntOrNull()?.minus(1)
            ?: throw IllegalStateException("Chapter not found, refresh the novel to load its current chapter list.")
        val content = fetchJson("read/$novelId/$index.json")?.jsonObject?.get("content")
            ?.asStringOrNull()
            ?: throw IllegalStateException("Chapter not found, it may have been removed from this source.")
        return paragraphsOf(Jsoup.parseBodyFragment(content))
    }

    // ---- helpers -----------------------------------------------------------

    private fun toNovelItem(o: kotlinx.serialization.json.JsonObject): Entry {
        val slug = o["slug"]?.asStringOrNull().orEmpty()
        return Entry(
            sourceId = id,
            id = "novel/$slug",
            title = o["title"]?.asStringOrNull() ?: "Untitled",
            coverUrl = abs(o["cover"]?.asStringOrNull()),
            type = ContentType.NOVEL,
        )
    }

    private suspend fun fetchJson(file: String): JsonElement? = withContext(Dispatchers.IO) {
        client.newCall(request("$apiBase/$file")).execute().use { r ->
            // Missing files (unknown slugs, pages past the end) come back as 403/404.
            if (r.code == 403 || r.code == 404) return@withContext null
            check(r.isSuccessful) { "$name HTTP ${r.code}" }
            parser.parseToJsonElement(r.body?.string().orEmpty())
        }
    }

    /** The site reads `web_` lists first and falls back to the plain ones. */
    private suspend fun fetchList(name: String): JsonElement? =
        fetchJson("lists/web_$name.json") ?: fetchJson("lists/$name.json")

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

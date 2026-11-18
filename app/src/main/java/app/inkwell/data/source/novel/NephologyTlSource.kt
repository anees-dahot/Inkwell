package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * NephologyTL (nephologytl.wordpress.com) — reads the public WordPress.com
 * REST API. Every novel has an introduction post in the `romcom` category
 * whose slug names the category holding its chapter posts.
 */
class NephologyTlSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "nephologytl"
    override val name = "NephologyTL"
    override val baseUrl = "https://nephologytl.wordpress.com"
    override val lang = "en"

    private val api = "https://public-api.wordpress.com/wp/v2/sites/nephologytl.wordpress.com"
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }
    private val perPage = 100

    override suspend fun popular(page: Int): PagedResult<Entry> = listNovels(page, "")
    override suspend fun latest(page: Int): PagedResult<Entry> = listNovels(page, "")
    override suspend fun search(query: String, page: Int): PagedResult<Entry> = listNovels(page, query)

    // ---- listing -----------------------------------------------------------

    /** Novels are the introduction posts, newest first, filtered by title. */
    private suspend fun listNovels(page: Int, searchTerm: String): PagedResult<Entry> {
        val posts = fetchList(
            "/posts?categories=${introCategoryId()}&per_page=$perPage&page=$page" +
                "&orderby=date&order=desc&_fields=id,slug,link,title"
        )
        val needle = searchKey(searchTerm)
        val items = posts.mapNotNull { el ->
            val o = el.jsonObject
            val name = o["title"]?.jsonObject?.get("rendered")?.asStringOrNull()?.let { decode(it) }
                ?: return@mapNotNull null
            val link = o["link"]?.asStringOrNull() ?: return@mapNotNull null
            name to toPath(link).trimStart('/')
        }
            .filter { (n, _) -> searchKey(n).contains(needle) }
            .map { (n, p) -> Entry(sourceId = id, id = p, title = n, coverUrl = null, type = ContentType.NOVEL) }
        return PagedResult(items, items.isNotEmpty())
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val slug = lastSegment(entryId)
        val intro = findPost("slug=$slug&categories=${introCategoryId()}")
            ?: throw IllegalStateException("NephologyTL: novel not found at $entryId")
        val category = findCategory(slug)
            ?: throw IllegalStateException("NephologyTL: novel not found at $entryId")
        val catId = category.jsonObject["id"]?.asStringOrNull()?.toLongOrNull()
            ?: throw IllegalStateException("NephologyTL: novel not found at $entryId")
        val o = intro.jsonObject
        val path = toPath(o["link"]?.asStringOrNull() ?: entryId).trimStart('/')
        val title = o["title"]?.jsonObject?.get("rendered")?.asStringOrNull()?.let { decode(it) } ?: "Untitled"
        val synopsis = synopsisOf(o["content"]?.jsonObject?.get("rendered")?.asStringOrNull().orEmpty())
        val chapters = fetchChapters(catId)
        return EntryDetails(
            entry = Entry(sourceId = id, id = path, title = title, coverUrl = null, type = ContentType.NOVEL),
            description = synopsis,
            authors = emptyList(),
            genres = emptyList(),
            status = null,
        ).also { cachedChapters[path] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        val category = findCategory(lastSegment(entryId)) ?: return emptyList()
        val catId = category.jsonObject["id"]?.asStringOrNull()?.toLongOrNull() ?: return emptyList()
        return fetchChapters(catId).also { cachedChapters[entryId] = it }
    }

    /** Chapters are dated posts in the novel's category, oldest first. */
    private suspend fun fetchChapters(categoryId: Long): List<Episode> {
        val chapters = mutableListOf<Episode>()
        var page = 1
        while (true) {
            val posts = fetchList(
                "/posts?categories=$categoryId&per_page=$perPage&page=$page" +
                    "&orderby=date&order=asc&_fields=id,slug,link,title,date"
            )
            for (el in posts) {
                val o = el.jsonObject
                val name = o["title"]?.jsonObject?.get("rendered")?.asStringOrNull()?.let { decode(it) }
                    ?: continue
                val link = o["link"]?.asStringOrNull() ?: continue
                val numbered = Regex("""\bChapter\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(name)
                chapters.add(
                    Episode(
                        id = toPath(link).trimStart('/'),
                        name = name,
                        number = numbered?.groupValues?.get(1)?.toFloatOrNull()
                            ?: (chapters.size + 1).toFloat(),
                        dateMillis = parseDate(o["date"]?.asStringOrNull()),
                    )
                )
            }
            if (posts.size < perPage) return chapters
            page++
        }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val slug = lastSegment(episodeId)
        val post = findPost("slug=$slug")
            ?: throw IllegalStateException("NephologyTL: no chapter text at $episodeId")
        val html = post.jsonObject["content"]?.jsonObject?.get("rendered")?.asStringOrNull().orEmpty()
        if (html.isBlank()) throw IllegalStateException("NephologyTL: no chapter text at $episodeId")
        val root = Jsoup.parseBodyFragment(html)
        root.select("script, style, .sharedaddy, .jp-relatedposts").remove()
        return paragraphsOf(root)
    }

    // ---- helpers -----------------------------------------------------------

    private var introCategoryIdCache: Long? = null

    private suspend fun introCategoryId(): Long {
        introCategoryIdCache?.let { return it }
        val id = findCategory("romcom")?.jsonObject?.get("id")?.asStringOrNull()?.toLongOrNull()
            ?: throw IllegalStateException("NephologyTL: novel index category not found")
        introCategoryIdCache = id
        return id
    }

    private suspend fun findCategory(slug: String): JsonElement? =
        fetchList("/categories?slug=$slug&_fields=id,slug").firstOrNull()

    private suspend fun findPost(query: String): JsonElement? =
        fetchList("/posts?$query&_fields=id,slug,link,title,date,content").firstOrNull()

    /** Fetch a REST collection; WordPress answers 400 for a page past the end. */
    private suspend fun fetchList(route: String): List<JsonElement> = withContext(Dispatchers.IO) {
        client.newCall(request(api + route)).execute().use { r ->
            if (r.code == 400 || r.code == 404) return@withContext emptyList<JsonElement>()
            check(r.isSuccessful) { "$name HTTP ${r.code}" }
            val body = r.body?.string().orEmpty()
            if (body.isBlank()) emptyList() else parser.parseToJsonElement(body).jsonArray.toList()
        }
    }

    /** Site-relative path with a trailing slash, e.g. "/2022/08/05/slug/". */
    private fun toPath(href: String): String {
        val raw = href.trim()
        val host = "nephologytl.wordpress.com"
        val afterScheme = raw.substringAfter("://", "")
        val clean = if (afterScheme.startsWith(host)) {
            val rest = afterScheme.substringAfter(host, "")
            if (rest.isEmpty() || rest.startsWith("/")) rest else raw
        } else raw
        val noQuery = clean.substringBefore('?').substringBefore('#')
        val path = if (noQuery.startsWith("/")) noQuery else "/$noQuery"
        return if (path.endsWith("/") || path == "/") path else "$path/"
    }

    /** Last path segment of a post path. */
    private fun lastSegment(path: String): String =
        toPath(path).split('/').filter { it.isNotEmpty() }.lastOrNull().orEmpty()

    private fun decode(html: String): String = normalize(Jsoup.parse(html).text())

    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** Lowercase with typographic quotes flattened so "Parent's" matches. */
    private fun searchKey(text: String): String = normalize(text)
        .lowercase()
        .replace('\u2018', '\'').replace('\u2019', '\'')
        .replace('\u201c', '"').replace('\u201d', '"')

    /** The synopsis is the intro paragraphs before the site's own chapter index. */
    private fun synopsisOf(contentHtml: String): String? {
        val out = mutableListOf<String>()
        val chapterStart = Regex("""^Chapter\s*\d""", RegexOption.IGNORE_CASE)
        for (p in Jsoup.parseBodyFragment(contentHtml).select("p")) {
            val text = normalize(p.text())
            if (text.isEmpty() || text.startsWith("Links:", ignoreCase = true) ||
                text.startsWith("Chapters:", ignoreCase = true)
            ) continue
            if (chapterStart.containsMatchIn(text)) break
            out.add(text)
        }
        return out.joinToString("\n\n").ifEmpty { null }
    }

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

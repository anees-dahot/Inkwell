package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Divine Dao Library. The site moved to the Fictioneer WP theme: stories are
 * `fcn_story` posts (/story/slug/), chapters `fcn_chapter` posts. The TS plugin
 * (id 'DDL.com') still targets the old layout, so this follows the new one.
 */
class DivineDaoLibrarySource(client: OkHttpClient) : ScraperNovelSource(client) {
    // TS id is 'DDL.com' verbatim.
    override val id = "DDL.com"
    override val name = "Divine Dao Library"
    override val baseUrl = "https://www.divinedaolibrary.com"
    override val lang = "en"

    private val rest = "$baseUrl/wp-json/wp/v2"
    private val pageSize = 20
    private val chapterCache = HashMap<String, List<Episode>>()

    private fun numFromName(n: String): Float? =
        Regex("(\\d+(?:\\.\\d+)?)").findAll(n).lastOrNull()?.value?.toFloatOrNull()

    /** Site-relative path of an absolute URL on the site, or null otherwise. */
    private fun getPath(url: String?): String? {
        if (url == null || !url.startsWith("$baseUrl/")) return null
        val trimmed = url.substring("$baseUrl/".length).trim('/')
        return trimmed.ifEmpty { null }
    }

    private fun rendered(o: JsonElement, key: String): String =
        runCatching { o.jsonObject[key]!!.jsonObject["rendered"]!!.jsonPrimitive.content }.getOrDefault("")

    private suspend fun stories(vararg params: Pair<String, String>): List<Entry> {
        val url = "$rest/fcn_story".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("per_page", pageSize.toString())
            addQueryParameter("_embed", "wp:featuredmedia")
            addQueryParameter("_fields", "slug,title,link,_links,_embedded")
        }.build()
        return json(url).jsonArray.mapNotNull { o ->
            val path = getPath(runCatching { o.jsonObject["link"]!!.jsonPrimitive.content }.getOrNull())
                ?: return@mapNotNull null
            val title = Jsoup.parse(rendered(o, "title")).text().trim().ifEmpty { return@mapNotNull null }
            val cover = runCatching {
                o.jsonObject["_embedded"]!!.jsonObject["wp:featuredmedia"]!!.jsonArray[0]
                    .jsonObject["source_url"]!!.jsonPrimitive.content
            }.getOrNull()
            Entry(id, path, title, cover, ContentType.NOVEL)
        }
    }

    private suspend fun listPage(page: Int, orderby: String): PagedResult<Entry> {
        val items = stories("orderby" to orderby, "order" to "desc", "page" to page.toString())
        return PagedResult(items, items.size >= pageSize)
    }

    override suspend fun popular(page: Int) = listPage(page, "modified")

    override suspend fun latest(page: Int) = listPage(page, "date")

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return PagedResult(emptyList(), false)
        val items = stories("search" to query.trim(), "page" to page.toString())
        return PagedResult(items, items.size >= pageSize)
    }

    // ---- details -----------------------------------------------------------

    private suspend fun parseChapters(entryId: String): Pair<org.jsoup.nodes.Document, List<Episode>> {
        val d = doc("$baseUrl/${entryId.trim('/')}/")
        val eps = d.select("li.chapter-group__list-item a.chapter-group__list-item-link").mapNotNull { a ->
            val path = getPath(a.absUrl("href").ifBlank { a.attr("href") }) ?: return@mapNotNull null
            val name = a.text().trim()
            Episode(id = path, name = name, number = numFromName(name), dateMillis = null)
        }
        return d to eps
    }

    override suspend fun details(entryId: String): EntryDetails {
        val (d, eps) = parseChapters(entryId)
        chapterCache[entryId] = eps
        val title = d.selectFirst("h1")?.text()?.trim().orEmpty().ifEmpty { entryId.substringAfterLast('/') }
        val cover = d.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val slug = entryId.trim('/').substringAfterLast('/')
        val post = runCatching { json("$rest/fcn_story?slug=$slug&_fields=content,excerpt").jsonArray.firstOrNull() }.getOrNull()
        val content = post?.let { Jsoup.parse(rendered(it, "content")) }
        val author = content?.select("h3")?.firstOrNull { it.text().startsWith("Author") }?.text()
            ?.replace(Regex("^Author:\\s*"), "")?.trim()?.takeIf { it.isNotEmpty() }
        val summary = post?.let { Jsoup.parse(rendered(it, "excerpt")).select("p").firstOrNull()?.text() }
            ?.trim()?.takeIf { it.isNotEmpty() }
        return EntryDetails(
            entry = Entry(id, entryId, title, cover, ContentType.NOVEL),
            description = summary,
            authors = listOfNotNull(author),
            genres = emptyList(),
            status = null,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        chapterCache[entryId]?.let { return it }
        return parseChapters(entryId).second.also { chapterCache[entryId] = it }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val slug = episodeId.trim('/').substringAfterLast('/')
        val arr = json("$rest/fcn_chapter?slug=$slug&_fields=content").jsonArray
        if (arr.isEmpty()) return emptyList()
        val content = rendered(arr[0], "content")
        if (content.isBlank()) return emptyList()
        return paragraphsOf(Jsoup.parse(content).body())
    }
}

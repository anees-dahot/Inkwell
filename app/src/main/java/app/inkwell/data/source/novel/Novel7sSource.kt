package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Novel7s (novel7s.com) — a WordPress site where each novel is a category and
 * its chapters are posts; everything goes through the WP REST API.
 */
class Novel7sSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novel7s"
    override val name = "Novel7s"
    override val baseUrl = "https://novel7s.com"
    override val lang = "en"

    private val rest = "$baseUrl/wp-json/wp/v2"
    private val pageSize = 18

    private val sentenceEnd = Regex("""[.!?…]["'”’»]?$""")
    private val chapterNumRe = Regex("""Chapter\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val summaryPrefix = Regex("""^.*?Chapter\s+\d+(?:\.\d+)?(?:[:-]|\s)+""", RegexOption.IGNORE_CASE)
    private val coverInDesc = Regex("""src=["']([^"'>]+)""")

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int) = listPage(page, sort = "count")
    override suspend fun latest(page: Int) = listPage(page, sort = "id")

    private suspend fun listPage(page: Int, sort: String): PagedResult<Entry> {
        val cats = categories(
            "orderby" to sort,
            "order" to "desc",
            "per_page" to pageSize.toString(),
            "page" to page.toString(),
            "hide_empty" to "1",
            "_fields" to "id,name,slug,count,description,link",
        )
        val items = cats.mapNotNull { catToNovel(it) }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        // Empty term would return the default category list, not search results.
        if (query.isBlank()) return PagedResult(emptyList(), false)
        val cats = categories(
            "search" to query.trim(),
            "per_page" to pageSize.toString(),
            "page" to page.toString(),
            "hide_empty" to "1",
            "_fields" to "id,name,slug,count,description,link",
        )
        val items = cats.mapNotNull { catToNovel(it) }
        return PagedResult(items, items.isNotEmpty())
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.trim('/')
        val cat = categories(
            "slug" to slug,
            "_fields" to "id,name,count,description",
        ).firstOrNull()?.jsonObject

        val title = cat?.get("name")?.asStringOrNull() ?: slug
        val cover = cat?.get("description")?.asStringOrNull()?.let { coverFromDesc(it) }
        val catId = cat?.get("id")?.asStringOrNull()?.toLongOrNull()

        val posts = if (catId != null) allChapterPosts(catId) else emptyList()

        val chapters = posts.mapIndexed { i, post ->
            val o = post.jsonObject
            val rendered = o["title"]?.jsonObject?.get("rendered")?.asStringOrNull().orEmpty()
            val titleText = Jsoup.parse(rendered).text().trim()
            val num = chapterNumRe.find(titleText)?.groupValues?.get(1)
            Episode(
                id = toPath(o["link"]?.asStringOrNull().orEmpty()),
                name = num?.let { "Chapter $it" } ?: titleText.ifEmpty { "Chapter ${i + 1}" },
                number = num?.toFloatOrNull() ?: (i + 1).toFloat(),
                dateMillis = parseDate(o["date"]?.asStringOrNull()),
            )
        }

        var summary = ""
        val firstExcerpt = posts.firstOrNull()?.jsonObject?.get("excerpt")?.jsonObject
            ?.get("rendered")?.asStringOrNull()
        if (firstExcerpt != null) {
            summary = summaryPrefix
                .replaceFirst(Jsoup.parse(firstExcerpt).text().trim(), "")
                .trim()
        }

        return EntryDetails(
            entry = Entry(sourceId = id, id = entryId, title = title, coverUrl = cover, type = ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = listOf("Novel7s"),
            genres = emptyList(),
            status = "Completed",
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    /** All chapter posts, walking WP REST pages (max 100 per page). */
    private suspend fun allChapterPosts(categoryId: Long): List<JsonElement> {
        val all = mutableListOf<JsonElement>()
        var page = 1
        while (true) {
            val posts = posts(
                "categories" to categoryId.toString(),
                "orderby" to "date",
                "order" to "asc",
                "per_page" to "100",
                "page" to page.toString(),
                "_fields" to "id,title,slug,link,date,excerpt",
            )
            all.addAll(posts)
            if (posts.size < 100) return all
            page++
        }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val slug = episodeId.trim('/')
        val posts = posts(
            "slug" to slug,
            "_fields" to "content",
        )
        if (posts.isEmpty()) return emptyList()
        val rawHtml = posts[0].jsonObject["content"]?.jsonObject?.get("rendered")
            ?.asStringOrNull().orEmpty()
        val root = Jsoup.parseBodyFragment(rawHtml)

        // Remove the first <p> that only contains <strong> (repeated title).
        root.select("p:has(strong)").firstOrNull()?.remove()

        // Merge broken paragraphs (port of the site's own JS fix).
        val ps = root.select("p").toMutableList()
        var i = 0
        while (i < ps.size - 1) {
            val cur = ps[i]
            val next = ps[i + 1]
            if (cur.nextElementSibling() !== next) { i++; continue }
            val text = cur.text().trim()
            if (text.isEmpty() || sentenceEnd.containsMatchIn(text)) { i++; continue }
            cur.appendText(" ")
            next.childNodes().toList().forEach { cur.appendChild(it) }
            next.remove()
            ps.removeAt(i + 1)
        }
        return paragraphsOf(root)
    }

    // ---- helpers -----------------------------------------------------------

    /** Fetch categories from the WP REST API with the given query params. */
    private suspend fun categories(vararg params: Pair<String, String>): List<JsonElement> =
        wp("categories", *params)

    private suspend fun posts(vararg params: Pair<String, String>): List<JsonElement> =
        wp("posts", *params)

    private suspend fun wp(endpoint: String, vararg params: Pair<String, String>): List<JsonElement> {
        val url = "$rest/$endpoint".toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return runCatching { json(url).jsonArray.toList() }.getOrDefault(emptyList())
    }

    private fun toPath(url: String): String =
        url.toHttpUrlOrNull()?.encodedPath?.trim('/') ?: url.trim('/')

    /** Parse the cover image URL out of a WP category description field. */
    private fun coverFromDesc(description: String): String? =
        coverInDesc.find(description)?.groupValues?.get(1)?.let { abs(it) }

    private fun catToNovel(el: JsonElement): Entry? {
        val o = el.jsonObject
        val name = o["name"]?.asStringOrNull() ?: return null
        val link = o["link"]?.asStringOrNull() ?: return null
        return Entry(
            sourceId = id,
            id = toPath(link),
            title = name,
            coverUrl = coverFromDesc(o["description"]?.asStringOrNull().orEmpty()),
            type = ContentType.NOVEL,
        )
    }

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
}

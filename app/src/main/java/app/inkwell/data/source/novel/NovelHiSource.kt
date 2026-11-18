package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's novelhi plugin. */
class NovelHiSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelhi"
    override val name = "NovelHi"
    override val baseUrl = "https://novelhi.com"
    override val lang = "en"

    override val headers = mapOf("Referer" to "https://novelhi.com/")

    private class Info(val title: String, val cover: String?, val summary: String, val author: String, val status: String, val genres: List<String>)

    private val cache = LinkedHashMap<String, Info>()

    private suspend fun list(page: Int, keyword: String?): PagedResult<Entry> {
        val b = "$baseUrl/book/searchByPageInShelf".toHttpUrl().newBuilder()
            .addQueryParameter("curr", page.toString())
            .addQueryParameter("limit", "10")
        if (!keyword.isNullOrBlank()) b.addQueryParameter("keyword", keyword)
        val arr = json(b.build()).jsonObject["data"]?.jsonObject?.get("list") as? JsonArray ?: JsonArray(emptyList())
        val items = arr.mapNotNull { el ->
            val o = el.jsonObject
            val simple = o["simpleName"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val title = o["bookName"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val path = "s/$simple"
            val cover = o["picUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() && it != "null" }
            cache[path] = Info(
                title, cover,
                o["bookDesc"]?.jsonPrimitive?.content.orEmpty(),
                o["authorName"]?.jsonPrimitive?.content.orEmpty(),
                o["bookStatus"]?.jsonPrimitive?.content.orEmpty(),
                (o["genres"] as? JsonArray)?.mapNotNull { g -> g.jsonObject["genreName"]?.jsonPrimitive?.content }.orEmpty(),
            )
            if (cache.size > 100) cache.remove(cache.keys.first())
            Entry(id, path, title, cover, ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = list(page, null)
    override suspend fun latest(page: Int) = list(page, null)
    override suspend fun search(query: String, page: Int) = list(page, query)

    private var lastBookId = HashMap<String, String>()

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc("$baseUrl/$entryId")
        val title = d.selectFirst("b.layui-icon")?.text()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: d.selectFirst(".tit h1")?.text()?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled"
        val pageCover = d.selectFirst(".cover img, .decorate-img img")?.let { it.attr("src").ifEmpty { it.attr("data-src") } }
            ?.takeIf { it.isNotEmpty() }
        d.selectFirst("#bookId")?.attr("value")?.let { lastBookId[entryId] = it }
        var info = cache[entryId]
        if (info == null) {
            runCatching { search(title, 1) }
            info = cache[entryId]
        }
        val summary = info?.summary?.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            ?.let { Jsoup.parse(it).text().trim() }?.takeIf { it.isNotEmpty() }
        return EntryDetails(
            entry = Entry(id, entryId, title, abs(pageCover) ?: info?.cover, ContentType.NOVEL),
            description = summary,
            authors = listOfNotNull(info?.author?.takeIf { it.isNotBlank() }),
            genres = info?.genres.orEmpty(),
            status = when (info?.status) { "1" -> "Completed"; "0" -> "Ongoing"; else -> null },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val bookId = lastBookId[entryId] ?: run {
            doc("$baseUrl/$entryId").selectFirst("#bookId")?.attr("value")
        } ?: return emptyList()
        val url = "$baseUrl/book/queryIndexList".toHttpUrl().newBuilder()
            .addQueryParameter("bookId", bookId)
            .addQueryParameter("curr", "1")
            .addQueryParameter("limit", "42121")
            .build()
        val arr = json(url).jsonObject["data"]?.jsonObject?.get("list") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val c = el.jsonObject
            val num = c["indexNum"]?.jsonPrimitive?.content ?: return@mapNotNull null
            Episode(
                id = "$entryId/$num",
                name = c["indexName"]?.jsonPrimitive?.content ?: "Chapter $num",
                number = num.toFloatOrNull(),
                dateMillis = parseDate(c["createTime"]?.jsonPrimitive?.content, "yyyy-MM-dd HH:mm:ss"),
            )
        }.reversed()
    }

    private fun rot13(s: String): String = Regex("(<[^>]+>)|([a-zA-Z])").replace(s) { m ->
        val tag = m.groupValues[1]
        if (tag.isNotEmpty()) return@replace tag
        val c = m.groupValues[2][0]
        val base = if (c <= 'Z') 'A' else 'a'
        (((c - base) + 13) % 26 + base.code).toChar().toString()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val url = "$baseUrl/$episodeId"
        val d = doc(url)
        val path = d.selectFirst("#chapterContentPath")?.attr("value")
        val token = d.selectFirst("#chapterContentToken")?.attr("value")
        if (path.isNullOrEmpty() || token.isNullOrEmpty()) {
            return paragraphsOf(d.selectFirst("#translate")?.parent())
        }
        val contentUrl = (abs(path) ?: return emptyList()) + "?token=$token"
        val req = request(contentUrl).newBuilder()
            .header("Referer", url).header("X-Requested-With", "XMLHttpRequest").build()
        val body = client.newCall(req).bodyString()
        val content = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: return emptyList()
        val html = rot13(content)
            .replace(Regex("<sent\\b", RegexOption.IGNORE_CASE), "<p")
            .replace(Regex("</sent>", RegexOption.IGNORE_CASE), "</p>")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "")
        return paragraphsOf(Jsoup.parse(html).body())
    }
}

package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's novelight plugin. */
class NovelightSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelight"
    override val name = "Novelight"
    override val baseUrl = "https://novelight.net"
    override val lang = "en"

    private fun parseList(html: org.jsoup.nodes.Document, hasNextPage: Boolean = true): PagedResult<Entry> {
        val items = html.select("a.item").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = a.selectFirst("div.title")?.text()?.trim().orEmpty()
            val cover = a.selectFirst("img")?.attr("src")?.takeIf { it.isNotEmpty() }?.let { abs(it) }
            Entry(id, href.trimStart('/'), title, cover, ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty() && hasNextPage)
    }

    override suspend fun popular(page: Int) = parseList(doc("$baseUrl/catalog/?ordering=popularity&page=$page"))
    override suspend fun latest(page: Int) = parseList(doc("$baseUrl/catalog/?ordering=-time_updated&page=$page"))
    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val url = "$baseUrl/catalog/".toHttpUrl().newBuilder()
            .addQueryParameter("search", query).addQueryParameter("page", page.toString()).build()
        return parseList(doc(url))
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc("$baseUrl/$entryId")
        var status = ""
        var translation = ""
        var author: String? = null
        var genres = emptyList<String>()
        for (item in d.select("div.mini-info > .item")) {
            val info = item.selectFirst("div.info")
            when (item.selectFirst(".sub-header")?.text()?.trim()) {
                "Status" -> status = info?.text()?.trim()?.lowercase().orEmpty()
                "Translation" -> translation = info?.text()?.trim()?.lowercase().orEmpty()
                "Author" -> author = info?.text()?.trim()
                "Genres" -> genres = item.select("div.info > a").map { it.text() }
            }
        }
        val st = when {
            status == "cancelled" -> "Cancelled"
            status == "releasing" || translation == "ongoing" -> "Ongoing"
            status == "completed" && translation == "completed" -> "Completed"
            else -> null
        }
        return EntryDetails(
            entry = Entry(id, entryId, d.selectFirst("h1")?.text()?.takeIf { it.isNotEmpty() } ?: "Untitled",
                abs(d.selectFirst(".poster > img")?.attr("src")), ContentType.NOVEL),
            description = d.select("section.text-info.section > p").text().takeIf { it.isNotEmpty() },
            authors = listOfNotNull(author?.takeIf { it.isNotEmpty() }),
            genres = genres,
            status = st,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val url = "$baseUrl/$entryId"
        val raw = fetch(url)
        val csrf = Regex("window\\.CSRF_TOKEN = \"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: return emptyList()
        val bookId = Regex("const OBJECT_BY_COMMENT = ([0-9]+)").find(raw)?.groupValues?.get(1) ?: return emptyList()
        val totalPages = Regex("<option value=\"([0-9]+)\"").findAll(raw).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val all = ArrayList<Episode>()
        // Site pages count from newest; fetch oldest first like LNReader (page index = totalPages - p + 1).
        for (p in 1..totalPages) {
            val pg = totalPages - p + 1
            val req = request("$baseUrl/book/ajax/chapter-pagination?csrfmiddlewaretoken=$csrf&book_id=$bookId&page=$pg")
                .newBuilder().header("Referer", url).header("X-Requested-With", "XMLHttpRequest")
                .header("Cookie", "csrftoken=$csrf").build()
            val html = json.parseToJsonElement(client.newCall(req).bodyString())
                .jsonObject["html"]?.jsonPrimitive?.content ?: continue
            val chunk = Jsoup.parse("<html>$html</html>", baseUrl).select("a").mapNotNull { a ->
                val href = a.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val title = a.selectFirst(".title")?.text()?.trim().orEmpty()
                val locked = a.selectFirst(".cost")?.text()?.isNotBlank() == true
                Episode(
                    id = href.trimStart('/'),
                    name = if (locked) "🔒 $title" else title,
                    number = null,
                    dateMillis = parseDate(a.selectFirst(".date")?.text()?.trim()?.let { reformat(it) }, "yyyy-MM-dd HH:mm:ss"),
                )
            }
            all += chunk.reversed()
        }
        return all
    }

    /** "DD.MM.YYYY" -> "yyyy-MM-dd 00:00:00". */
    private fun reformat(s: String): String? {
        val m = Regex("""(\d{2})\.(\d{2})\.(\d{4})""").find(s) ?: return null
        return "${m.groupValues[3]}-${m.groupValues[2]}-${m.groupValues[1]} 00:00:00"
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val path = episodeId.trimStart('/')
        val url = "$baseUrl/$path"
        val raw = fetch(url)
        val csrf = Regex("window\\.CSRF_TOKEN = \"([^\"]+)\"").find(raw)?.groupValues?.get(1)
        val chapterId = Regex("const CHAPTER_ID = \"([0-9]+)").find(raw)?.groupValues?.get(1) ?: return emptyList()
        val req = request("$baseUrl/book/ajax/read-chapter/$chapterId").newBuilder()
            .header("Cookie", "csrftoken=$csrf").header("Referer", url)
            .header("X-Requested-With", "XMLHttpRequest").build()
        val o = json.parseToJsonElement(client.newCall(req).bodyString()).jsonObject
        val cls = o["class"]?.jsonPrimitive?.content ?: return emptyList()
        val content = o["content"]?.jsonPrimitive?.content ?: return emptyList()
        val d = Jsoup.parse(content)
        d.select("script, .advertisment").remove()
        return paragraphsOf(d.selectFirst(".$cls") ?: d.body())
    }
}

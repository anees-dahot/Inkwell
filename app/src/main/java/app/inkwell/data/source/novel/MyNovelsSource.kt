package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** Port of LNReader's mynovels plugin (mynovels.su, HTML + ajax endpoints). */
class MyNovelsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "mynovels"
    override val name = "Mynovels"
    override val baseUrl = "https://mynovels.su"
    override val lang = "en"

    private fun cleanPath(p: String) = p.trimStart('/')

    private fun parseList(d: Document): PagedResult<Entry> {
        val items = d.select("a.item").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val cover = a.selectFirst("img")?.attr("src")?.takeIf { it.isNotEmpty() }?.let { abs(it) }
            Entry(id, cleanPath(href), a.selectFirst("div.title")?.text()?.trim().orEmpty(), cover, ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = parseList(doc("$baseUrl/catalog/?&ordering=popularity&page=$page"))
    override suspend fun latest(page: Int) = parseList(doc("$baseUrl/catalog/?ordering=-time_updated&page=$page"))
    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return parseList(doc("$baseUrl/catalog/?search=" + java.net.URLEncoder.encode(query, "UTF-8"))).copy(hasNext = false)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(cleanPath(entryId)))
        var status = ""
        var translation = ""
        var author: String? = null
        var genres = emptyList<String>()
        for (el in d.select("div.mini-info > .item")) {
            val info = el.selectFirst("div.info")
            when (el.selectFirst(".sub-header")?.text()?.trim()) {
                "Status" -> status = info?.text()?.trim()?.lowercase().orEmpty()
                "Translation" -> translation = info?.text()?.trim()?.lowercase().orEmpty()
                "Author" -> author = info?.text()?.trim()?.takeIf { it.isNotEmpty() }
                "Genres" -> genres = el.select("div.info > a").map { it.text().trim() }.filter { it.isNotEmpty() }
            }
        }
        val st = when {
            status == "cancelled" -> "Cancelled"
            status == "releasing" || translation == "ongoing" -> "Ongoing"
            status == "completed" && translation == "completed" -> "Completed"
            else -> null
        }
        return EntryDetails(
            entry = Entry(
                id, cleanPath(entryId),
                d.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled",
                d.selectFirst(".poster > img")?.attr("src")?.takeIf { it.isNotEmpty() }?.let { abs(it) },
                ContentType.NOVEL,
            ),
            description = d.selectFirst("section.text-info.section > p")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(author),
            genres = genres,
            status = st,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val path = cleanPath(entryId)
        val raw = fetch(absPath(path))
        val csrf = Regex("""window\.CSRF_TOKEN = "([^"]+)"""").find(raw)?.groupValues?.get(1).orEmpty()
        val bookId = Regex("""const OBJECT_BY_COMMENT = ([0-9]+)""").find(raw)?.groupValues?.get(1).orEmpty()
        val d = Jsoup.parse(raw)
        val pages = d.select("#select-pagination-chapter > option").mapNotNull { it.`val`().toIntOrNull() }
        val out = ArrayList<Episode>()
        for (sp in pages) {
            val url = "$baseUrl/book/ajax/chapter-pagination?csrfmiddlewaretoken=$csrf&book_id=$bookId&page=$sp"
            val req = request(url).newBuilder()
                .header("Referer", absPath(path))
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            val html = runCatching {
                json.parseToJsonElement(client.newCall(req).bodyString()).jsonObject["html"]
                    ?.let { (it as? JsonPrimitive)?.contentOrNull }
            }.getOrNull() ?: continue
            val chunk = Jsoup.parse(html).select("a").mapNotNull { a ->
                val title = a.selectFirst(".title")?.text()?.trim().orEmpty()
                val locked = a.selectFirst(".cost")?.text()?.isNotBlank() == true
                val href = cleanPath(a.attr("href")).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Episode(
                    id = href,
                    name = if (locked) "🔒 $title" else title,
                    number = null,
                    dateMillis = a.selectFirst(".date")?.text()?.trim()?.let { s ->
                        runCatching {
                            java.time.LocalDate.parse(s, java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
                                .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        }.getOrNull()
                    },
                )
            }
            out.addAll(chunk.reversed())
        }
        return out
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val path = cleanPath(episodeId)
        val raw = fetch(absPath(path))
        val csrf = Regex("""window\.CSRF_TOKEN = "([^"]+)"""").find(raw)?.groupValues?.get(1)
        val chapterId = Regex("""const CHAPTER_ID = "([0-9]+)""").find(raw)?.groupValues?.get(1) ?: return emptyList()
        val req = request("$baseUrl/book/ajax/read-chapter/$chapterId").newBuilder()
            .header("Cookie", "csrftoken=$csrf")
            .header("Referer", absPath(path))
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        val o = json.parseToJsonElement(client.newCall(req).bodyString()).jsonObject
        val cls = (o["class"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val content = (o["content"] as? JsonPrimitive)?.contentOrNull ?: return emptyList()
        val d = Jsoup.parse(content)
        d.select("script, .advertisment").remove()
        val root = if (cls.isNotEmpty()) d.selectFirst(".$cls") ?: d.body() else d.body()
        return paragraphsOf(root)
    }
}

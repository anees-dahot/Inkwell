package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/** Port of LNReader's pinkmuffintl plugin (WordPress.com REST API; novel = category, chapter = post). */
class PinkMuffinTlSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "pinkmuffintl"
    override val name = "Pink Muffin TL"
    override val baseUrl = "https://pinkmuffinyum.wordpress.com"
    override val lang = "en"

    private val apiBase = "https://public-api.wordpress.com/wp/v2/sites/pinkmuffinyum.wordpress.com"
    private val notNovel = setOf("all", "announcements", "random", "uncategorized")
    private val chapterWords = "chapter|episode|prologue|epilogue|side\\s*story|extra|special|interlude|afterword"
    private val chapterTitle = Regex("\\b(?:$chapterWords)\\b", RegexOption.IGNORE_CASE)
    private val chapterNumber = Regex("\\b(?:chapter|episode)\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
    private val chapterStart = Regex("\\b(?:$chapterWords)\\b.*$", RegexOption.IGNORE_CASE)
    private val statusMap = mapOf(
        "completed" to "Completed", "complete" to "Completed", "ongoing" to "Ongoing",
        "hiatus" to "On Hiatus", "on hiatus" to "On Hiatus", "dropped" to "Cancelled",
    )
    private val pageSize = 100
    private val maxPages = 20

    private class Cat(val id: Int, val name: String, val slug: String, val count: Int, val description: String)

    private suspend fun api(path: String): Pair<JsonElement, Int> = withContext(Dispatchers.IO) {
        client.newCall(request(apiBase + path)).execute().use { r ->
            check(r.isSuccessful) { "$name HTTP ${r.code}" }
            val pages = r.header("x-wp-totalpages")?.toIntOrNull() ?: 1
            json.parseToJsonElement(r.body?.string().orEmpty()) to pages
        }
    }

    private suspend fun allPages(query: String): List<JsonElement> {
        val (first, pages) = api("$query&page=1")
        check(pages <= maxPages) { "$name: the list is too long to load." }
        val items = ArrayList(first.jsonArray)
        for (p in 2..pages) items.addAll(api("$query&page=$p").first.jsonArray)
        return items
    }

    private fun decode(text: String): String =
        Jsoup.parse("<p>$text</p>").select("p").text().replace("￼", "").replace(Regex("\\s+"), " ").trim()

    private fun toCat(o: JsonObject) = Cat(
        o["id"]!!.jsonPrimitive.content.toInt(),
        o["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o["slug"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o["count"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        o["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
    )

    private suspend fun categories(search: String? = null): List<Cat> {
        val q = "/categories?orderby=count&order=desc&_fields=id,name,slug,count,description&per_page=$pageSize" +
            (search?.let { "&search=" + URLEncoder.encode(it, "UTF-8") } ?: "")
        return allPages(q).map { toCat(it.jsonObject) }.filter { it.count > 0 && it.slug !in notNovel }
    }

    private fun entry(c: Cat) = Entry(id, "/category/${c.slug}/", decode(c.name), null, ContentType.NOVEL)

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(categories().map { entry(it) }, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val cats = categories()
        val listed = cats.map { it.id }.toSet()
        val rank = HashMap<Int, Int>()
        var p = 1
        while (p <= maxPages && rank.size < listed.size) {
            val (data, pages) = api("/posts?per_page=$pageSize&orderby=date&order=desc&_fields=categories&page=$p")
            for (post in data.jsonArray) {
                for (cid in post.jsonObject["categories"]?.jsonArray.orEmpty()) {
                    val i = cid.jsonPrimitive.content.toInt()
                    if (i in listed && i !in rank) rank[i] = rank.size
                }
            }
            if (p >= pages) break
            p++
        }
        val sorted = cats.sortedWith(compareBy<Cat> { rank[it.id] ?: Int.MAX_VALUE }.thenByDescending { it.count })
        return PagedResult(sorted.map { entry(it) }, false)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val t = query.trim()
        if (t.isEmpty() || page > 1) return PagedResult(emptyList(), false)
        return PagedResult(categories(t).map { entry(it) }, false)
    }

    private class Post(val id: String, val dateGmt: String, val link: String, val title: String)

    private val coverCache = HashMap<String, String?>()

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.split('/').filter { it.isNotEmpty() }.lastOrNull().orEmpty()
        val cat = api("/categories?slug=${URLEncoder.encode(slug, "UTF-8")}&_fields=id,name,slug,count,description")
            .first.jsonArray.firstOrNull()?.let { toCat(it.jsonObject) } ?: error("$name: novel not found.")
        val posts = allPages("/posts?categories=${cat.id}&orderby=date&order=asc&per_page=$pageSize&_fields=id,date_gmt,link,title")
            .map { it.jsonObject }.map {
                Post(
                    it["id"]!!.jsonPrimitive.content, it["date_gmt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    it["link"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    decode(it["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull.orEmpty()),
                )
            }
        val intro = posts.firstOrNull { !chapterTitle.containsMatchIn(it.title) }
        var cover: String? = null
        var summary: String? = cat.description.trim().takeIf { it.isNotEmpty() }
        var status: String? = null
        if (intro != null) {
            val html = api("/posts/${intro.id}?_fields=content").first.jsonObject["content"]?.jsonObject
                ?.get("rendered")?.jsonPrimitive?.contentOrNull.orEmpty()
            val d = Jsoup.parse(html)
            cover = d.selectFirst("img[src]")?.attr("src")?.substringBefore('?')?.takeIf { it.isNotEmpty() }
            d.select("script, style, img, figure").remove()
            d.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
            val lines = d.select("p").flatMap { it.wholeText().split('\n') }
                .map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
            val st = lines.firstNotNullOfOrNull { Regex("^status\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            status = st?.let { statusMap[it.lowercase()] }
            val text = lines.filterNot { Regex("^(?:status|main story|side stories?)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(it) }
                .joinToString("\n")
            summary = text.takeIf { it.isNotEmpty() } ?: summary
        }
        coverCache[entryId] = cover
        chapterPosts[entryId] = posts.filter { chapterTitle.containsMatchIn(it.title) }
        return EntryDetails(
            entry = Entry(id, entryId, decode(cat.name), cover, ContentType.NOVEL),
            description = summary,
            authors = emptyList(),
            genres = emptyList(),
            status = status,
        )
    }

    private val chapterPosts = HashMap<String, List<Post>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        if (entryId !in chapterPosts) details(entryId)
        val sorted = chapterPosts[entryId].orEmpty().mapIndexed { index, p ->
            val num = chapterNumber.find(p.title)?.groupValues?.get(1)?.toFloatOrNull()
            val order = num ?: if (Regex("prologue", RegexOption.IGNORE_CASE).containsMatchIn(p.title)) -1f else Float.POSITIVE_INFINITY
            val nm = chapterStart.find(p.title)?.value?.replaceFirstChar { it.uppercase() } ?: p.title
            Triple(index, order, Episode(
                id = java.net.URI(p.link).path,
                name = nm,
                number = num,
                dateMillis = parseDate(p.dateGmt + "Z"),
            ))
        }.sortedWith(compareBy<Triple<Int, Float, Episode>> { it.second }.thenBy { it.first })
        return sorted.map { it.third }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val slug = episodeId.split('/').filter { it.isNotEmpty() }.lastOrNull().orEmpty()
        val arr = api("/posts?slug=${URLEncoder.encode(slug, "UTF-8")}&_fields=link,content").first.jsonArray
        val post = arr.map { it.jsonObject }.firstOrNull { java.net.URI(it["link"]!!.jsonPrimitive.content).path == episodeId }
            ?: arr.firstOrNull()?.jsonObject ?: error("$name: chapter not found.")
        val html = post["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull.orEmpty()
        val d = Jsoup.parse("<div id=\"chapter\">$html</div>")
        val c = d.selectFirst("#chapter")!!
        c.select("script, style, iframe, .wp-block-spacer").remove()
        val nav = Regex("^(?:(?:last|prev(?:ious)?|toc|next)\\s*\\|?\\s*)+$", RegexOption.IGNORE_CASE)
        for (p in c.select("p")) {
            val t = p.text().replace(Regex("\\s+"), " ").trim()
            if (nav.matches(t)) p.remove()
        }
        return paragraphsOf(c)
    }
}

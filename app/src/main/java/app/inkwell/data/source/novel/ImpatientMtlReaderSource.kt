package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's impatientmtlreader plugin (WordPress.com REST API). */
class ImpatientMtlReaderSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "impatientmtlreader"
    override val name = "Impatient MTL Reader"
    override val baseUrl = "https://impatientmtlreader533234643.wordpress.com"
    override val lang = "en"

    private val api = "https://public-api.wordpress.com/wp/v2/sites/impatientmtlreader533234643.wordpress.com"
    private val perPage = 100

    private class Novel(val category: String, val stray: List<String> = emptyList())

    private val novels = mapOf(
        "fiancee" to Novel("omiai-shitakunakattanode", listOf("fiancee-ep-17", "fiancee-v5-ep-30")),
        "preaching-classmate" to Novel("tanin-wo-yosetsukenai-buaisouna-joshi", listOf("preaching-v2-ep-24")),
        "idol-girl-classmate" to Novel("idol-girl"),
        "mawaru-gakuen-to-senpai-to-boku-simple-life" to Novel("mawaru-gakuen-senpai-to-boku-simple-life", listOf("v4c1p2")),
        "nekura-and-hiria" to Novel("nekura-and-hiria"),
        "500-yen-imouto" to Novel("500-yen"),
    )

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()
    private fun decode(html: String) = norm(Jsoup.parse("<p>$html</p>").text())

    private fun toPath(href: String): String {
        val raw = href.trim()
        val m = Regex("^https?://impatientmtlreader533234643\\.wordpress\\.com(/.*)?$", RegexOption.IGNORE_CASE).find(raw)
        val clean = (if (m != null) m.groupValues[1].ifEmpty { "/" } else raw).split('?', '#')[0]
        val p = if (clean.startsWith("/")) clean else "/$clean"
        return if (p.endsWith("/")) p else "$p/"
    }

    private fun lastSegment(path: String) = toPath(path).split('/').filter { it.isNotEmpty() }.lastOrNull().orEmpty()

    private suspend fun list(route: String): JsonArray = runCatching {
        json(api + route) as JsonArray
    }.getOrElse { e ->
        if (e.message?.let { "HTTP 400" in it || "HTTP 404" in it } == true) JsonArray(emptyList()) else throw e
    }

    private fun s(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.content.orEmpty()
    private fun rendered(o: JsonObject, k: String) = o[k]?.jsonObject?.get("rendered")?.jsonPrimitive?.content.orEmpty()

    private fun pageCover(page: JsonObject): String? {
        val d = Jsoup.parse("<div>${rendered(page, "content")}</div>")
        val urls = d.select("img").map { it.attr("src") }
            .filter { it.isNotEmpty() && !Regex("kofi|discord|noimagefound", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        val cover = urls.firstOrNull { Regex("/cove", RegexOption.IGNORE_CASE).containsMatchIn(it) } ?: urls.firstOrNull()
        return cover?.substringBefore('?')
    }

    private suspend fun listNovels(page: Int, query: String): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val needle = query.lowercase().replace('‘', '\'').replace('’', '\'').let(::norm)
        val items = list("/pages?per_page=$perPage&_fields=id,slug,link,parent,title,content").mapNotNull {
            val o = it.jsonObject
            if (s(o, "slug") !in novels) return@mapNotNull null
            val title = decode(rendered(o, "title"))
            if (!title.lowercase().replace('‘', '\'').replace('’', '\'').contains(needle)) return@mapNotNull null
            Entry(id, toPath(s(o, "link")), title, pageCover(o), ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    override suspend fun popular(page: Int) = listNovels(page, "")
    override suspend fun latest(page: Int) = listNovels(page, "")
    override suspend fun search(query: String, page: Int) = listNovels(page, query)

    private suspend fun page(entryId: String): JsonObject {
        val slug = lastSegment(entryId)
        return list("/pages?slug=$slug&_fields=id,slug,link,parent,title,content").firstOrNull()?.jsonObject
            ?: error("Impatient MTL Reader: novel not found at $entryId")
    }

    override suspend fun details(entryId: String): EntryDetails {
        val p = page(entryId)
        val d = Jsoup.parse("<div>${rendered(p, "content")}</div>")
        val paras = ArrayList<String>()
        var started = false
        for (el in d.select("p")) {
            val t = norm(el.text())
            if (!started) { started = Regex("^Description:?$", RegexOption.IGNORE_CASE).matches(t); continue }
            if (Regex("^(Source|Novel Updates|Support Author|Raw)\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) break
            if (t.isNotEmpty()) paras.add(t)
        }
        return EntryDetails(
            entry = Entry(id, toPath(s(p, "link")), decode(rendered(p, "title")), pageCover(p), ContentType.NOVEL),
            description = paras.joinToString("\n\n").ifEmpty { null },
            authors = emptyList(), genres = emptyList(), status = null,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = lastSegment(entryId)
        val src = novels[slug] ?: return emptyList()
        val cat = list("/categories?slug=${src.category}&_fields=id,slug").firstOrNull()?.jsonObject ?: return emptyList()
        val catId = s(cat, "id")
        val fields = "&_fields=id,slug,link,title,date"
        val posts = ArrayList<JsonObject>()
        var pg = 1
        while (true) {
            val batch = list("/posts?categories=$catId&per_page=$perPage&page=$pg$fields")
            posts.addAll(batch.map { it.jsonObject })
            if (batch.size < perPage) break
            pg++
        }
        if (src.stray.isNotEmpty()) {
            val slugs = src.stray.joinToString("&") { "slug[]=$it" }
            posts.addAll(list("/posts?$slugs&per_page=$perPage$fields").map { it.jsonObject })
        }
        posts.sortBy { s(it, "date") }
        return posts.mapIndexed { i, o ->
            Episode(toPath(s(o, "link")), decode(rendered(o, "title")), (i + 1).toFloat(), parseDate(s(o, "date")))
        }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val slug = lastSegment(episodeId)
        val post = list("/posts?slug=$slug&_fields=id,slug,link,title,content").firstOrNull()?.jsonObject ?: return emptyList()
        val html = rendered(post, "content")
        if (html.isBlank()) return emptyList()
        val root = Jsoup.parse("<div id=\"chapter\">$html</div>").selectFirst("#chapter") ?: return emptyList()
        root.select(".sharedaddy, .jp-relatedposts, .wp-block-spacer, hr").remove()
        root.select("table:has(img[src*=ko-fi]), figure:has(img[src*=discord])").remove()
        for (p in root.select("p")) {
            val t = norm(p.text())
            if (Regex("^TOC\\s*\\|", RegexOption.IGNORE_CASE).containsMatchIn(t) ||
                Regex("^Join\\s+us on Discord", RegexOption.IGNORE_CASE).containsMatchIn(t)
            ) p.remove()
        }
        return paragraphsOf(root)
    }
}

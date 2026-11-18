package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's procrastranslation plugin (WordPress.com pages: novel = top-level page, chapter = child page). */
class ProcrastranslationSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "procrastranslation"
    override val name = "procrastranslation"
    override val baseUrl = "https://procrastranslation.wordpress.com"
    override val lang = "en"

    private val api = "https://public-api.wordpress.com/wp/v2/sites/procrastranslation.wordpress.com"
    private val perPage = 100

    private class WpPage(val id: Int, val slug: String, val link: String, val date: String, val parent: Int, val title: String)

    private fun norm(t: String) = t.replace(Regex("\\s+"), " ").trim()
    private fun decode(html: String) = norm(Jsoup.parse("<p>$html</p>").select("p").text())

    private fun toPath(href: String): String {
        val raw = href.trim()
        val m = Regex("^https?://procrastranslation\\.wordpress\\.com(/.*)?$", RegexOption.IGNORE_CASE).find(raw)
        val clean = (if (m != null) m.groupValues[1].ifEmpty { "/" } else raw).split('?', '#')[0]
        val p = if (clean.startsWith("/")) clean else "/$clean"
        return if (p.endsWith("/")) p else "$p/"
    }

    private fun lastSegment(path: String) = toPath(path).split('/').filter { it.isNotEmpty() }.lastOrNull().orEmpty()

    private suspend fun fetchList(route: String): List<JsonObject> = withContext(Dispatchers.IO) {
        client.newCall(request(api + route)).execute().use { r ->
            if (r.code == 400 || r.code == 404) return@use emptyList()
            check(r.isSuccessful) { "$name HTTP ${r.code}" }
            json.parseToJsonElement(r.body?.string().orEmpty()).jsonArray.map { it.jsonObject }
        }
    }

    private fun toPage(o: JsonObject) = WpPage(
        o["id"]!!.jsonPrimitive.content.toInt(),
        o["slug"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o["link"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o["date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        o["parent"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        decode(o["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull.orEmpty()),
    )

    private suspend fun allPages(): List<WpPage> {
        val out = ArrayList<WpPage>()
        var p = 1
        while (true) {
            val batch = fetchList("/pages?per_page=$perPage&page=$p&_fields=id,slug,link,title,date,parent")
            out += batch.map { toPage(it) }
            if (batch.size < perPage) return out
            p++
        }
    }

    private fun key(t: String) = norm(t).lowercase().replace('‘', '\'').replace('’', '\'')
        .replace('“', '"').replace('”', '"')

    private suspend fun listNovels(page: Int, term: String): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val pages = allPages()
        val parents = pages.map { it.parent }.toSet()
        val needle = key(term)
        val items = pages.filter { it.parent == 0 && it.id in parents }
            .sortedByDescending { it.date }
            .filter { key(it.title).contains(needle) }
            .map { Entry(id, toPath(it.link), it.title, null, ContentType.NOVEL) }
        return PagedResult(items, false)
    }

    override suspend fun popular(page: Int) = listNovels(page, "")
    override suspend fun latest(page: Int) = listNovels(page, "")
    override suspend fun search(query: String, page: Int) = listNovels(page, query)

    private val chapterCache = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val pages = allPages()
        val slug = lastSegment(entryId)
        val novel = pages.firstOrNull { it.parent == 0 && it.slug == slug } ?: error("$name: novel not found at $entryId")
        chapterCache[entryId] = pages.filter { it.parent == novel.id }.sortedBy { it.date }.mapIndexed { i, p ->
            Episode(toPath(p.link), p.title, (i + 1).toFloat(), parseDate(p.date, "yyyy-MM-dd'T'HH:mm:ss"))
        }
        val html = fetchList("/pages?include=${novel.id}&_fields=id,content").firstOrNull()
            ?.get("content")?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull.orEmpty()
        val d = Jsoup.parse("<div id=\"novel\">$html</div>")
        val author = d.select("p").map { norm(it.text()) }.firstOrNull { Regex("^Author\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            ?.replace(Regex("^Author\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        val summary = ArrayList<String>()
        var inSummary = false
        for (p in d.select("p")) {
            val t = norm(p.text())
            if (t.isEmpty()) continue
            if (Regex("^Summary\\b", RegexOption.IGNORE_CASE).containsMatchIn(t)) {
                inSummary = true
                val rest = t.replace(Regex("^Summary[^:]*:\\s*", RegexOption.IGNORE_CASE), "")
                if (rest.isNotEmpty() && rest != t) summary += rest
                continue
            }
            if (inSummary && !Regex("^(Chapter\\s*\\d|Prologue\\b)", RegexOption.IGNORE_CASE).containsMatchIn(t)) summary += t
            else if (inSummary) inSummary = false
        }
        return EntryDetails(
            entry = Entry(id, entryId, novel.title, null, ContentType.NOVEL),
            description = summary.joinToString("\n\n").takeIf { it.isNotEmpty() },
            authors = listOfNotNull(author?.takeIf { it.isNotEmpty() }),
            genres = emptyList(),
            status = null,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        chapterCache[entryId]?.let { return it }
        details(entryId)
        return chapterCache[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val path = toPath(episodeId)
        val cands = fetchList("/pages?slug=${lastSegment(path)}&_fields=id,slug,link,title,date,parent,content")
        val page = cands.firstOrNull { toPath(it["link"]?.jsonPrimitive?.contentOrNull.orEmpty()) == path } ?: return emptyList()
        val html = page["content"]?.jsonObject?.get("rendered")?.jsonPrimitive?.contentOrNull.orEmpty()
        val d = Jsoup.parse("<div id=\"chapter\">$html</div>")
        val root = d.selectFirst("#chapter")!!
        root.select("script, style, .sharedaddy, .jp-relatedposts").remove()
        return paragraphsOf(root)
    }
}

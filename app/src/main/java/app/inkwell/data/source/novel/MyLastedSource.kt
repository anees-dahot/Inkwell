package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's mylasted plugin (Machine Editing, Blogger JSON feeds). */
class MyLastedSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "mylasted"
    override val name = "Machine Editing (MyLasted)"
    override val baseUrl = "https://mylasted.blogspot.com"
    override val lang = "en"

    private fun t(e: JsonElement?): String? = ((e as? JsonObject)?.get("\$t") as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull

    private suspend fun feed(label: String, start: Int = 1, summary: Boolean = false): JsonObject {
        val kind = if (summary) "summary" else "default"
        val url = "$baseUrl/feeds/posts/$kind/-/${java.net.URLEncoder.encode(label, "UTF-8").replace("+", "%20")}?alt=json&max-results=150&start-index=$start"
        return json(url).jsonObject["feed"]?.jsonObject ?: error("Machine Editing: the public feed could not be read.")
    }

    private fun total(f: JsonObject) = t(f["openSearch\$totalResults"])?.toIntOrNull() ?: 0

    private fun altHref(e: JsonObject): String? =
        e["link"]?.jsonArray?.firstOrNull { (it as? JsonObject)?.get("rel")?.let { r -> (r as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull } == "alternate" }
            ?.jsonObject?.get("href")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }

    private fun pathOf(href: String): String = java.net.URI(href).path

    private suspend fun all(): List<Entry> {
        val f = feed("Series")
        return f["entry"]?.jsonArray.orEmpty().mapNotNull {
            val e = it.jsonObject
            val href = altHref(e) ?: return@mapNotNull null
            val cover = Jsoup.parse(t(e["content"]).orEmpty()).selectFirst("img")?.attr("src")?.takeIf { s -> s.isNotEmpty() }
            Entry(id, pathOf(href), t(e["title"]).orEmpty(), cover, ContentType.NOVEL)
        }
    }

    override suspend fun popular(page: Int) = if (page != 1) PagedResult(emptyList(), false) else PagedResult(all(), false)
    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val q = query.trim().lowercase()
        return PagedResult(popular(page).items.filter { it.title.lowercase().contains(q) }, false)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        fun field(label: String) = d.select("#extra-info dt").firstOrNull { it.text().trim() == label }
            ?.nextElementSibling()?.text()?.trim()?.takeIf { it.isNotEmpty() }
        val title = d.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: d.selectFirst("h1")?.text().orEmpty()
        return EntryDetails(
            entry = Entry(id, entryId, title, abs(d.selectFirst("article img")?.attr("src")), ContentType.NOVEL),
            description = d.selectFirst("#synopsis")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(field("Author")),
            genres = field("Tags")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            status = field("Chapter"),
        )
    }

    private class Ch(val name: String, val path: String, val num: Float?, val date: String)

    override suspend fun episodes(entryId: String): List<Episode> {
        val d = doc(absPath(entryId))
        val label = Regex("""clwd\.run\(\s*['"]([^'"]+)['"]\s*\)""").find(d.selectFirst("#clwd")?.html().orEmpty())?.groupValues?.get(1)
            ?: error("Machine Editing: the novel chapter label could not be read.")
        val first = feed(label, 1, true)
        val total = total(first)
        if (total < 1 || total > 15000) error("Machine Editing: invalid chapter count.")
        var start = 1
        val seen = HashSet<String>()
        val chapters = ArrayList<Ch>()
        while (start <= total) {
            val f = if (start == 1) first else feed(label, start, true)
            val entries = f["entry"]?.jsonArray.orEmpty()
            if (entries.isEmpty()) break
            for (el in entries) {
                val e = el.jsonObject
                val href = altHref(e) ?: continue
                val p = pathOf(href)
                if (!seen.add(p)) continue
                val title = t(e["title"]).orEmpty()
                val num = Regex("""(?:chapter|episode)\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)
                if (num == null && !Regex("prologue|epilogue|side story", RegexOption.IGNORE_CASE).containsMatchIn(title)) continue
                chapters.add(Ch(title, p, num?.toFloatOrNull(), t(e["published"]).orEmpty()))
            }
            start += entries.size
        }
        fun section(c: Ch) = when {
            c.num != null -> 1
            Regex("prologue", RegexOption.IGNORE_CASE).containsMatchIn(c.name) -> 0
            Regex("epilogue", RegexOption.IGNORE_CASE).containsMatchIn(c.name) -> 3
            else -> 2
        }
        return chapters.sortedWith(
            compareBy<Ch>({ section(it) }, { it.num ?: 0f }, { it.date }, { it.path })
        ).map { Episode(it.path, it.name, it.num, parseDate(it.date)) }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val body = d.selectFirst("article.txt") ?: return emptyList()
        if (body.selectFirst("#clwd") != null) return emptyList()
        body.select("script,style,iframe,form,.adsbygoogle,.separator").remove()
        body.select("div,p").filter { it.text().trim().startsWith("Consider supporting me by subscribing", true) }.forEach { it.remove() }
        return paragraphsOf(body)
    }
}

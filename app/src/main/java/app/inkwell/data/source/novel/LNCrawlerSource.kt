package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/** Port of LNReader's lncrawler plugin (JSON API at api.lncrawler.monster). */
class LNCrawlerSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "lncrawler"
    override val name = "LNCrawler"
    override val baseUrl = "https://lncrawler.monster"
    override val lang = "en"

    private val api = "https://api.lncrawler.monster"
    private val pageSize = 20

    private data class Src(val slug: String, val language: String, val available: Int)

    private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull

    private suspend fun list(page: Int, query: String?, sort: String): PagedResult<Entry> {
        val url = "$api/novels/search/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("page_size", pageSize.toString())
            .apply { if (!query.isNullOrBlank()) addQueryParameter("query", query) }
            .addQueryParameter("languages", "en")
            .addQueryParameter("sort_by", sort)
            .addQueryParameter("sort_order", "desc")
            .build()
        val o = json(url).jsonObject
        val items = o["results"]?.jsonArray?.mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            val slug = str(e["slug"])?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = str(e["title"])?.takeIf { it.isNotEmpty() } ?: slug
            val cover = str((e["prefered_source"] as? JsonObject)?.get("cover_min_url"))?.takeIf { it.isNotEmpty() }
            Entry(id, slug, title, cover, ContentType.NOVEL)
        } ?: emptyList()
        val total = o["total_pages"]?.jsonPrimitive?.intOrNull ?: 1
        return PagedResult(items, page < total)
    }

    override suspend fun popular(page: Int) = list(page, null, "popularity")
    override suspend fun latest(page: Int) = list(page, null, "last_updated")
    override suspend fun search(query: String, page: Int) = list(page, query, "popularity")

    private suspend fun chooseSource(slug: String): Pair<String, Src> {
        val o = json("$api/novels/$slug/").jsonObject
        val title = str(o["title"]).orEmpty()
        val preferred = str((o["prefered_source"] as? JsonObject)?.get("source_slug"))
        val sources = (o["sources"] as? JsonArray)?.mapNotNull {
            val s = it as? JsonObject ?: return@mapNotNull null
            val sl = str(s["source_slug"])?.takeIf { x -> x.isNotEmpty() } ?: return@mapNotNull null
            Src(sl, str(s["language"]).orEmpty(), s["available_chapters_count"]?.jsonPrimitive?.intOrNull ?: 0)
        }.orEmpty().filter { it.available > 0 }
        var bestEn: Src? = null
        var bestAny: Src? = null
        for (s in sources) {
            if (bestAny == null || s.available > bestAny.available) bestAny = s
            if (s.language == "en" && (bestEn == null || s.available > bestEn.available ||
                    (s.available == bestEn.available && s.slug == preferred))) bestEn = s
        }
        val chosen = bestEn ?: bestAny ?: error("This novel has no readable source on LNCrawler")
        return title to chosen
    }

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.substringBefore('?')
        val (title, src) = chooseSource(slug)
        val d = json("$api/novels/$slug/${src.slug}/").jsonObject
        val summary = str(d["synopsis"])?.let { Jsoup.parse(it.replace(Regex("(?i)<br\\s*/?>|</p>"), "\n")).wholeText().trim() }
            ?.takeIf { it.isNotEmpty() }
        val authors = d["authors"]?.jsonArray?.mapNotNull { str(it)?.takeIf { s -> s.isNotEmpty() } } ?: emptyList()
        val tags = d["tags"]?.jsonArray?.mapNotNull { str(it)?.takeIf { s -> s.isNotEmpty() } } ?: emptyList()
        val status = str(d["status"])?.takeIf { it in setOf("Ongoing", "Completed", "On Hiatus", "Cancelled") }
        return EntryDetails(
            entry = Entry(id, slug, title.ifEmpty { slug }, str(d["cover_url"])?.takeIf { it.isNotEmpty() }, ContentType.NOVEL),
            description = summary,
            authors = authors,
            genres = tags,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = entryId.substringBefore('?')
        val (_, src) = chooseSource(slug)
        val base = "$api/novels/$slug/${src.slug}"
        val out = ArrayList<Pair<Int, String>>()
        var page = 1
        var total = 1
        while (page <= total && page <= 500) {
            val o = json(if (page == 1) "$base/chapters/" else "$base/chapters/?page=$page").jsonObject
            total = o["total_pages"]?.jsonPrimitive?.intOrNull ?: 1
            o["chapters"]?.jsonArray?.forEach { el ->
                val c = el as? JsonObject ?: return@forEach
                if (str(c["has_content"]) != "true") return@forEach
                val cid = c["chapter_id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                out.add(cid to (str(c["title"])?.takeIf { it.isNotEmpty() } ?: "Chapter $cid"))
            }
            page++
        }
        return out.sortedBy { it.first }.map { (cid, title) ->
            Episode("$slug/${src.slug}/$cid", title, cid.toFloat(), null)
        }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val parts = episodeId.trim('/').split('/')
        if (parts.size != 3) return emptyList()
        val o = json("$api/novels/${parts[0]}/${parts[1]}/chapter/${parts[2]}/").jsonObject
        val body = str(o["body"]) ?: return emptyList()
        return paragraphsOf(Jsoup.parse(body).body())
    }
}

package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** vortexscans.org — Next.js site backed by a JSON API at api.vortexscans.org (not Madara/Themesia). */
class VortexScansSource(client: OkHttpClient) : ScraperMangaSource(client) {
    override val baseUrl = "https://vortexscans.org"
    override val id = "vortexscans"
    override val name = "Vortex Scans"
    override val lang = "en"

    override val headers = mapOf("Referer" to "$baseUrl/", "Origin" to baseUrl)

    private val api = "https://api.vortexscans.org"
    private val perPage = 20

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun slugOf(entryId: String) = entryId.trim('/').removePrefix("series/").substringBefore('/')

    private suspend fun query(order: String, term: String, page: Int): PagedResult<Entry> {
        val url = "$api/api/query".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("perPage", perPage.toString())
            .addQueryParameter("searchTerm", term)
            .addQueryParameter("orderBy", order).build()
        val o = json(url).jsonObject
        val items = o["posts"]?.jsonArray.orEmpty().mapNotNull { p ->
            val po = p.jsonObject
            val slug = po.str("slug") ?: return@mapNotNull null
            if (po["isNovel"]?.jsonPrimitive?.booleanOrNull == true) return@mapNotNull null
            Entry(id, "series/$slug", po.str("postTitle") ?: slug, ref(po.str("featuredImage")), type)
        }
        val total = o["totalCount"]?.jsonPrimitive?.intOrNull ?: 0
        return PagedResult(items, page * perPage < total)
    }

    override suspend fun popular(page: Int) = query("totalViews", "", page)
    override suspend fun latest(page: Int) = query("lastUpdated", "", page)
    override suspend fun search(query: String, page: Int) =
        if (query.isBlank()) popular(page) else query("totalViews", query.trim(), page)

    override suspend fun details(entryId: String): EntryDetails {
        val slug = slugOf(entryId)
        val p = json("$api/api/post?postSlug=$slug").jsonObject["post"]?.jsonObject ?: error("Page not found")
        val status = when (p.str("seriesStatus")?.uppercase()) {
            "ONGOING" -> "Ongoing"
            "COMPLETED" -> "Completed"
            "HIATUS" -> "Hiatus"
            "DROPPED", "CANCELLED" -> "Cancelled"
            else -> p.str("seriesStatus")
        }
        val desc = buildString {
            p.str("postContent")?.let { append(org.jsoup.Jsoup.parse(it).text().trim()) }
            p.str("alternativeTitles")?.let {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Names: ").append(it)
            }
        }.ifEmpty { null }
        return EntryDetails(
            entry = Entry(id, "series/$slug", p.str("postTitle") ?: slug, ref(p.str("featuredImage")), type),
            description = desc,
            authors = listOfNotNull(p.str("author"), p.str("artist")).distinct(),
            genres = p["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.str("name")?.trim() },
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = slugOf(entryId)
        val p = json("$api/api/post?postSlug=$slug").jsonObject["post"]?.jsonObject ?: return emptyList()
        val postId = p["id"]?.jsonPrimitive?.intOrNull ?: return emptyList()
        val out = mutableListOf<Episode>()
        var skip = 0
        while (true) {
            val url = "$api/api/chapters?postId=$postId&take=250&skip=$skip&order=desc"
            val arr = json(url).jsonObject["post"]?.jsonObject?.get("chapters")?.jsonArray ?: break
            arr.forEach { c ->
                val co = c.jsonObject
                val cs = co.str("slug") ?: return@forEach
                if (co["isAccessible"]?.jsonPrimitive?.booleanOrNull == false) return@forEach
                val num = co["number"]?.jsonPrimitive?.floatOrNull
                val title = co.str("title")
                Episode(
                    id = "series/$slug/$cs",
                    name = buildString {
                        append("Chapter ").append(num?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: cs)
                        if (title != null) append(" - ").append(title)
                    },
                    number = num,
                    dateMillis = parseDate(co.str("createdAt")),
                ).let(out::add)
            }
            if (arr.size < 250) break
            skip += 250
        }
        return out
    }

    override suspend fun pages(episodeId: String): List<Page> {
        val parts = episodeId.trim('/').removePrefix("series/").split('/')
        val c = json("$api/api/chapter?mangaslug=${parts[0]}&chapterslug=${parts.getOrElse(1) { "" }}")
            .jsonObject["chapter"]?.jsonObject ?: return emptyList()
        return c["images"]?.jsonArray.orEmpty()
            .sortedBy { it.jsonObject["order"]?.jsonPrimitive?.intOrNull ?: 0 }
            .mapNotNull { it.jsonObject.str("url") }
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
    }
}

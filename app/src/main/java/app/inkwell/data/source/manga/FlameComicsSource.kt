package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

/**
 * Flame Comics (flamecomics.xyz, Next.js). Reads the __NEXT_DATA__ blob of each page.
 * Entry id = "series/{id}", episode id = "series/{id}/{token}".
 */
class FlameComicsSource(client: OkHttpClient) : ScraperMangaSource(client) {
    override val baseUrl = "https://flamecomics.xyz"
    override val id = "flamecomics"
    override val name = "Flame Comics"
    override val lang = "en"

    override val headers = mapOf("Referer" to "$baseUrl/")

    private val cdn = "https://cdn.flamecomics.xyz/uploads/images/series"
    private val pageSize = 24

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

    private suspend fun pageProps(path: String): JsonObject {
        val html = fetch(absPath(path))
        val raw = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: error("$name: data not found")
        return json.parseToJsonElement(raw).jsonObject["props"]!!.jsonObject["pageProps"]!!.jsonObject
    }

    @Volatile private var cache: Pair<Long, List<JsonObject>>? = null

    private suspend fun allSeries(): List<JsonObject> {
        cache?.let { (t, l) -> if (System.currentTimeMillis() - t < 10 * 60_000) return l }
        val l = pageProps("browse")["series"]!!.jsonArray.map { it.jsonObject }
        cache = System.currentTimeMillis() to l
        return l
    }

    private fun cover(s: JsonObject): String? {
        val sid = s["series_id"].str() ?: return null
        val c = s["cover"].str() ?: "thumbnail.webp"
        val v = s["last_edit"].str()
        return ref("$cdn/$sid/$c" + (v?.let { "?$it" } ?: ""))
    }

    private fun toEntry(s: JsonObject): Entry? {
        val sid = s["series_id"].str() ?: return null
        val title = s["title"].str() ?: return null
        return Entry(id, "series/$sid", title, cover(s), type)
    }

    private fun slice(list: List<JsonObject>, page: Int): PagedResult<Entry> {
        val from = (page - 1) * pageSize
        if (from >= list.size) return PagedResult(emptyList(), false)
        val to = minOf(from + pageSize, list.size)
        return PagedResult(list.subList(from, to).mapNotNull { toEntry(it) }, to < list.size)
    }

    override suspend fun popular(page: Int) =
        slice(allSeries().sortedBy { it["popularityRank"].str()?.toIntOrNull() ?: Int.MAX_VALUE }, page)

    override suspend fun latest(page: Int) =
        slice(allSeries().sortedByDescending { it["last_edit"].str()?.toLongOrNull() ?: 0L }, page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (query.isBlank()) return popular(page)
        val q = query.trim().lowercase()
        val hits = allSeries().filter { s ->
            s["title"].str()?.lowercase()?.contains(q) == true ||
                s["altTitles"]?.jsonArray?.any { it.str()?.lowercase()?.contains(q) == true } == true
        }
        return slice(hits, page)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val sid = entryId.substringAfterLast('/')
        val pp = pageProps("series/$sid")
        val s = pp["series"]?.jsonObject ?: error("Page not found")
        val entry = toEntry(s) ?: error("Page not found")
        val desc = s["description"].str()?.let { Jsoup.parseBodyFragment(it).text().trim() }?.ifEmpty { null }
        return EntryDetails(
            entry = entry,
            description = desc,
            authors = (s["author"]?.jsonArray.orEmpty() + s["artist"]?.jsonArray.orEmpty())
                .mapNotNull { it.str()?.trim() }.filter { it.isNotEmpty() }.distinct(),
            genres = s["tags"]?.jsonArray.orEmpty().mapNotNull { it.str() },
            status = s["status"].str(),
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val sid = entryId.substringAfterLast('/')
        val pp = pageProps("series/$sid")
        return pp["chapters"]?.jsonArray.orEmpty().mapNotNull { e ->
            val c = e.jsonObject
            val token = c["token"].str() ?: return@mapNotNull null
            val num = c["chapter"].str()?.toFloatOrNull()
            val numStr = num?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }
            val title = c["title"].str()?.trim()?.takeIf { it.isNotEmpty() }
            Episode(
                id = "series/$sid/$token",
                name = (numStr?.let { "Chapter $it" } ?: "Chapter") + (title?.let { " - $it" } ?: ""),
                number = num,
                dateMillis = c["release_date"].str()?.toLongOrNull()?.times(1000),
            )
        }
    }

    override suspend fun pages(episodeId: String): List<Page> {
        val parts = episodeId.trim('/').split('/')
        val sid = parts[1]
        val token = parts[2]
        val ch = pageProps("series/$sid/$token")["chapter"]?.jsonObject ?: error("Chapter not found")
        val v = ch["release_date"].str()
        val images = ch["images"]?.jsonObject ?: error("No pages")
        return images.entries.sortedBy { it.key.toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { it.value.jsonObject["name"].str() }
            .mapIndexed { i, n -> Page(i, ref("$cdn/$sid/$token/$n" + (v?.let { "?$it" } ?: ""))!!) }
    }
}

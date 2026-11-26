package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Comick (comick.io moved to comick.dev; API host api.comick.dev). Port of Keiyoushi's Comick.
 * Search/browse is open; /comic and /chapter endpoints sit behind a Cloudflare challenge, so
 * details/chapters/pages rely on the app's Cloudflare WebView clearance (CloudflareException).
 */
class ComickSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    override val baseUrl = "https://comick.dev"
    override val id = "comick"
    override val name = "Comick"
    override val lang = "en"

    private val api = "https://api.comick.dev"
    private val pageSize = 49

    override val headers = mapOf("Referer" to "https://comick.dev/", "Origin" to "https://comick.dev")

    private fun s(e: JsonElement?) = e?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.contentOrNull

    private fun cover(o: kotlinx.serialization.json.JsonObject): String? {
        val key = o["md_covers"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray
            ?.firstOrNull()?.jsonObject?.get("b2key")?.let { s(it) }
        return ref(key?.let { "https://meo.comick.pictures/$it" } ?: s(o["cover_url"]))
    }

    private suspend fun browse(page: Int, sort: String?, q: String?, genre: String? = null): PagedResult<Entry> {
        val b = "$api/v1.0/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", pageSize.toString())
            .addQueryParameter("tachiyomi", "true")
        sort?.let { b.addQueryParameter("sort", it) }
        q?.let { b.addQueryParameter("q", it) }
        genre?.let { b.addQueryParameter("genres", it) }
        for (c in listOf("safe", "suggestive", "erotica", "pornographic")) b.addQueryParameter("content_rating", c)
        val arr = json(b.build()).jsonArray
        val items = arr.map { it.jsonObject }.map { o ->
            Entry(id, "/comic/${s(o["hid"])}", s(o["title"]).orEmpty(), cover(o), type,
                s(o["last_chapter"])?.toFloatOrNull())
        }
        return PagedResult(items, items.size >= pageSize)
    }

    override suspend fun popular(page: Int) = browse(page, "view", null)

    override suspend fun latest(page: Int) = browse(page, "uploaded", null)

    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> {
        val slug = app.inkwell.data.model.tagSlug(tag).ifEmpty { return PagedResult(emptyList(), false) }
        return browse(page, "view", null, slug)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> =
        if (query.isBlank()) popular(page) else browse(page, null, query.trim())

    private fun hid(entryId: String) = entryId.trimEnd('/').substringAfterLast('/')

    override suspend fun details(entryId: String): EntryDetails {
        val root = json("$api/comic/${hid(entryId)}/?tachiyomi=true").jsonObject
        val c = root["comic"]!!.jsonObject
        val genres = c["md_comic_md_genres"]?.jsonArray
            ?.mapNotNull { s(it.jsonObject["md_genres"]?.jsonObject?.get("name")) }.orEmpty()
        val people = (root["authors"]?.jsonArray.orEmpty() + root["artists"]?.jsonArray.orEmpty())
            .mapNotNull { s(it.jsonObject["name"]) }.distinct()
        return EntryDetails(
            entry = Entry(id, entryId, s(c["title"]).orEmpty(), cover(c), type),
            description = s(c["desc"])?.ifBlank { null },
            authors = people,
            genres = genres,
            status = when (c["status"]?.jsonPrimitive?.intOrNull) {
                1 -> "Ongoing"; 2 -> "Completed"; 3 -> "Cancelled"; 4 -> "On Hiatus"; else -> null
            },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val h = hid(entryId)
        val seen = HashSet<String>()
        val out = ArrayList<Episode>()
        var page = 1
        while (page <= 30) {
            val u = "$api/comic/$h/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("lang", "en")
                .addQueryParameter("limit", "300")
                .addQueryParameter("page", page.toString())
                .addQueryParameter("tachiyomi", "true").build()
            val chapters = json(u).jsonObject["chapters"]?.jsonArray ?: break
            if (chapters.isEmpty()) break
            for (o in chapters.map { it.jsonObject }) {
                val chap = s(o["chap"])
                // many groups upload the same chapter; keep the first (newest) per number
                if (chap != null && !seen.add(chap)) continue
                val vol = s(o["vol"])
                val title = s(o["title"])
                out += Episode(
                    id = "/chapter/${s(o["hid"])}",
                    name = buildString {
                        if (!vol.isNullOrEmpty()) append("Vol. $vol ")
                        append(if (chap != null) "Ch. $chap" else "Oneshot")
                        if (!title.isNullOrEmpty()) append(" - $title")
                    },
                    number = chap?.toFloatOrNull(),
                    dateMillis = parseDate(s(o["created_at"])),
                )
            }
            if (chapters.size < 300) break
            page++
        }
        return out
    }

    override suspend fun pages(episodeId: String): List<Page> {
        val ch = json("$api/chapter/${hid(episodeId)}/?tachiyomi=true").jsonObject["chapter"]!!.jsonObject
        return ch["images"]!!.jsonArray.mapNotNull { s(it.jsonObject["url"]) }
            .mapIndexed { i, u -> Page(i, ref(u)!!) }
    }
}

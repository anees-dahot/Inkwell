package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** atsu.moe: JSON API (Typesense search for browse, /api/manga + /api/read for the rest). */
class AtsuMoeSource(client: OkHttpClient) : ScraperMangaSource(client), app.inkwell.data.model.TagBrowsable {
    override val baseUrl = "https://atsu.moe"
    override val id = "atsumoe"
    override val name = "Atsu"
    override val lang = "en"

    override val headers = mapOf("Referer" to "https://atsu.moe/")

    private val perPage = 24

    private fun s(e: JsonElement?) = e?.jsonPrimitive?.contentOrNull
    private fun staticUrl(p: String?): String? = p?.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("http")) it else "https://cdn.atsu.moe/static/" + it.removePrefix("/static/").trimStart('/')
    }
    /** Posters ship as avif; the CDN also serves webp which every Android version decodes. */
    private fun poster(p: String?) = staticUrl(p)?.replace(Regex("""\.avif$"""), ".webp")
    private fun mangaId(entryId: String) = entryId.trimEnd('/').substringAfterLast('/')

    private suspend fun typesense(q: String, page: Int, sort: String?, tag: String? = null): PagedResult<Entry> {
        val b = "$baseUrl/collections/manga/documents/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("query_by", "title,englishTitle,otherNames,authors,acronyms")
            .addQueryParameter("query_by_weights", "4,3,2,2,1")
            .addQueryParameter("num_typos", "4,3,2,1,0")
            .addQueryParameter("prefix", "true,true,true,true,false")
            .addQueryParameter("include_fields", "id,title,poster,posterMedium")
            .addQueryParameter("per_page", perPage.toString())
            .addQueryParameter("page", page.toString())
        if (sort != null) {
            b.addQueryParameter("sort_by", sort)
            b.addQueryParameter("filter_by", "hidden:!=true && views:>0" + (tag?.let { " && tags:=[`$it`]" } ?: ""))
        } else {
            b.addQueryParameter("infix", "off,off,fallback,off,off")
            b.addQueryParameter("filter_by", "hidden:!=true")
        }
        val root = json(b.build()).jsonObject
        val items = root["hits"]!!.jsonArray.map { it.jsonObject["document"]!!.jsonObject }.map { d ->
            val mid = s(d["id"])!!
            Entry(id, "/manga/$mid", s(d["title"]).orEmpty(), ref(poster(s(d["posterMedium"]) ?: s(d["poster"]))), type)
        }
        val found = root["found"]?.jsonPrimitive?.intOrNull ?: 0
        return PagedResult(items, page * perPage < found)
    }

    override suspend fun popular(page: Int) = typesense("*", page, "views:desc")

    /** Typesense tag filters are case-sensitive; try the tag as given, then word-capitalised. */
    override suspend fun byTag(tag: String, page: Int): PagedResult<Entry> {
        val t = tag.trim().replace("`", "").ifEmpty { return PagedResult(emptyList(), false) }
        val r = typesense("*", page, "views:desc", t)
        if (r.items.isNotEmpty() || page > 1) return r
        val cap = t.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
        return if (cap == t) r else typesense("*", page, "views:desc", cap)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        val url = "$baseUrl/api/home2/recentlyUpdated?offset=${(page - 1) * perPage}&limit=$perPage&adult=1"
        val items = json(url).jsonObject["items"]!!.jsonArray.map { it.jsonObject }.map { d ->
            Entry(id, "/manga/${s(d["id"])}", s(d["title"]).orEmpty(),
                ref(poster(s(d["mediumImage"]) ?: s(d["image"]))), type)
        }
        return PagedResult(items, items.size >= perPage)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> =
        if (query.isBlank()) popular(page) else typesense(query.trim(), page, null)

    override suspend fun details(entryId: String): EntryDetails {
        val mp = json("$baseUrl/api/manga/page?id=${mangaId(entryId)}").jsonObject["mangaPage"]!!.jsonObject
        val names = mp["authors"]?.jsonArray?.mapNotNull { s(it.jsonObject["name"]) }.orEmpty().distinct()
        val other = mp["otherNames"]?.jsonArray?.mapNotNull { s(it) }.orEmpty()
        val desc = buildString {
            s(mp["synopsis"])?.let { append(it.trim()) }
            if (other.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternative Names:")
                other.forEach { append("\n- ").append(it) }
            }
        }
        val posterObj = mp["poster"]?.jsonObject
        return EntryDetails(
            entry = Entry(id, entryId, s(mp["title"]).orEmpty(),
                ref(poster(s(posterObj?.get("image")))), type),
            description = desc.ifEmpty { null },
            authors = names,
            genres = mp["genres"]?.jsonArray?.mapNotNull { s(it.jsonObject["name"]) }.orEmpty(),
            status = s(mp["status"]),
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val mid = mangaId(entryId)
        val chapters = json("$baseUrl/api/manga/allChapters?mangaId=$mid").jsonObject["chapters"]!!.jsonArray
            .map { it.jsonObject }
        // Several scanlators upload the same chapter; keep the longest version of each number.
        val best = LinkedHashMap<String, kotlinx.serialization.json.JsonObject>()
        for (c in chapters) {
            val key = s(c["number"]) ?: s(c["id"])!!
            val cur = best[key]
            val pc = c["pageCount"]?.jsonPrimitive?.intOrNull ?: 0
            if (cur == null || pc > (cur["pageCount"]?.jsonPrimitive?.intOrNull ?: 0)) best[key] = c
        }
        return best.values.sortedByDescending { it["number"]?.jsonPrimitive?.doubleOrNull ?: -1.0 }.map { c ->
            Episode(
                id = "/read/$mid/${s(c["id"])}",
                name = s(c["title"]) ?: "Chapter ${s(c["number"])}",
                number = c["number"]?.jsonPrimitive?.doubleOrNull?.toFloat(),
                dateMillis = c["createdAt"]?.jsonPrimitive?.longOrNull,
            )
        }
    }

    override suspend fun pages(episodeId: String): List<Page> {
        val parts = episodeId.trim('/').split('/')
        val mid = parts[1]; val cid = parts[2]
        val rc = json("$baseUrl/api/read/chapter?mangaId=$mid&chapterId=$cid").jsonObject["readChapter"]!!.jsonObject
        return rc["pages"]!!.jsonArray.mapIndexed { i, p ->
            Page(i, ref(staticUrl(s(p.jsonObject["image"])))!!)
        }
    }
}

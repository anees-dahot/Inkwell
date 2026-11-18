package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import java.net.URLEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/** Port of LNReader's atsumaru plugin (Typesense catalog + JSON API). */
class AtsumaruSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "atsumaru"
    override val name = "Atsumaru"
    override val baseUrl = "https://atsu.moe"
    override val lang = "en"

    private val cdn = "https://cdn.atsu.moe/static/"
    private val queryBy = "title,englishTitle,otherNames,authors,acronyms"
    private val filter = "medium:=Novel && hidden:!=true"
    private val perPage = 24
    private val scanlatorOrder = HashMap<String, List<String>>()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun str(e: JsonElement?): String? =
        runCatching { e?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotEmpty() && it != "null" }

    private suspend fun cards(query: String, sort: String, page: Int): PagedResult<Entry> {
        val url = "$baseUrl/collections/manga/documents/search?q=${enc(query)}&query_by=${enc(queryBy)}" +
            "&page=$page&per_page=$perPage&filter_by=${enc(filter)}&sort_by=${enc(sort)}"
        val hits = json(url).jsonObject["hits"]?.jsonArray ?: emptyList()
        val items = hits.mapNotNull { h ->
            val d = h.jsonObject["document"]?.jsonObject ?: return@mapNotNull null
            val did = str(d["id"]) ?: return@mapNotNull null
            Entry(
                id, did, str(d["title"]) ?: str(d["englishTitle"]) ?: did,
                str(d["poster"])?.let { "https://cdn.atsu.moe$it" }, ContentType.NOVEL,
            )
        }
        return PagedResult(items, items.size >= perPage)
    }

    override suspend fun popular(page: Int) = cards("*", "views:desc", page)
    override suspend fun latest(page: Int) = cards("*", "dateAdded:desc", page)
    override suspend fun search(query: String, page: Int) = cards(query, "views:desc", page)

    override suspend fun details(entryId: String): EntryDetails {
        val sid = entryId.trim('/')
        val p = json("$baseUrl/api/manga/page?id=${enc(sid)}").jsonObject["mangaPage"]?.jsonObject
            ?: error("Novel response had an unexpected shape")
        val title = str(p["title"]) ?: str(p["englishTitle"]) ?: sid
        scanlatorOrder[sid] = p["scanlators"]?.jsonArray?.mapNotNull { str(it.jsonObject["id"]) } ?: emptyList()
        val raw = str(p["status"]).orEmpty()
        val status = when {
            raw == "Completed" -> "Completed"
            raw == "Ongoing" -> "Ongoing"
            raw.contains("hiatus", true) -> "On Hiatus"
            raw.contains("cancel", true) -> "Cancelled"
            else -> null
        }
        return EntryDetails(
            entry = Entry(id, sid, title, str(p["poster"]?.jsonObject?.get("image"))?.let { cdn + it }, ContentType.NOVEL),
            description = str(p["synopsis"]),
            authors = emptyList(),
            genres = p["genres"]?.jsonArray?.mapNotNull { str(it.jsonObject["name"]) } ?: emptyList(),
            status = status,
        )
    }

    private class Ch(val id: String, val scanId: String, val number: Float, val title: String)

    override suspend fun episodes(entryId: String): List<Episode> {
        val sid = entryId.trim('/')
        val order = scanlatorOrder[sid] ?: run { details(sid); scanlatorOrder[sid].orEmpty() }
        val arr = json("$baseUrl/api/manga/allChapters?mangaId=${enc(sid)}").jsonObject["chapters"]?.jsonArray
            ?: error("Chapter list response had an unexpected shape")
        val entries = arr.mapNotNull { e ->
            val o = e.jsonObject
            val cid = str(o["id"]) ?: return@mapNotNull null
            val num = str(o["number"])?.toFloatOrNull() ?: return@mapNotNull null
            Ch(cid, str(o["scanlationMangaId"]).orEmpty(), num, str(o["title"]) ?: "Chapter $num")
        }
        var best: List<Ch> = emptyList()
        var bestRank = Int.MAX_VALUE
        for ((scan, list) in entries.groupBy { it.scanId }) {
            val idx = order.indexOf(scan)
            val rank = if (idx < 0) order.size else idx
            if (list.size > best.size || (list.size == best.size && rank < bestRank)) {
                best = list; bestRank = rank
            }
        }
        return best.sortedBy { it.number }.distinctBy { it.number }.map {
            Episode("$sid/${it.id}", it.title, it.number, null)
        }
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val parts = episodeId.trim('/').split('/')
        if (parts.size != 2) return emptyList()
        val o = json("$baseUrl/api/read/novelChapter?mangaId=${enc(parts[0])}&chapterId=${enc(parts[1])}").jsonObject
        val ps = o["readNovelChapter"]?.jsonObject?.get("paragraphs")?.jsonArray ?: return emptyList()
        return ps.mapNotNull { str(it)?.trim()?.takeIf { s -> s.isNotEmpty() } }
    }
}

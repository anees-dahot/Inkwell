package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

class LightNovelWorldSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "lightnovelworld"
    override val name = "LightNovelWorld"
    override val baseUrl = "https://lightnovelworld.org"
    override val lang = "en"

    // ---- listing -----------------------------------------------------------

    private suspend fun listPage(sort: String, page: Int): PagedResult<Entry> {
        val url = absPath("advanced-search/").toHttpUrl().newBuilder()
            .addQueryParameter("sort", sort)
            .addQueryParameter("order", "desc")
            .apply { if (page > 1) addQueryParameter("page", page.toString()) }
            .build()
        val d = doc(url)
        val items = d.select(".card-cover-link").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            var path = href.removePrefix("/").removePrefix(baseUrl).trim('/')
            if (path.isNotEmpty() && !path.endsWith("/")) path += "/"
            val img = a.selectFirst("img") ?: return@mapNotNull null
            val name = img.attr("alt").trim().ifBlank { null } ?: return@mapNotNull null
            Entry(id, path, name, abs(img.attr("src")), ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = listPage("rank", page)
    override suspend fun latest(page: Int) = listPage("new", page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1 || query.isBlank()) return PagedResult(emptyList(), false)
        val root = json(absPath("api/search/").toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("search_type", "title").build()).jsonObject
        val items = root["novels"]?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val slug = o["slug"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val cover = o["cover_path"]?.jsonPrimitive?.contentOrNull
            Entry(id, "novel/$slug/", title, cover?.let { abs(it) }, ContentType.NOVEL)
        } ?: emptyList()
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private suspend fun chapterPages(slug: String): List<Episode> {
        val apiBase = absPath("api/novel/$slug/chapters/?limit=500")
        val first = json("$apiBase&offset=0").jsonObject
        val total = first["total_chapters"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        val raw = mutableListOf<kotlinx.serialization.json.JsonElement>()
        first["chapters"]?.jsonArray?.let { raw.addAll(it) }
        var offset = 500
        while (offset < total) {
            json("$apiBase&offset=$offset").jsonObject["chapters"]?.jsonArray?.let { raw.addAll(it) }
            offset += 500
        }
        return raw.mapNotNull { el ->
            val o = el.jsonObject
            val number = o["number"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
            val title = o["title"]?.jsonPrimitive?.contentOrNull?.trim().takeUnless { it.isNullOrEmpty() }
            Episode(
                id = "novel/$slug/chapter/${o["number"]?.jsonPrimitive?.contentOrNull}/",
                name = title ?: "Chapter ${o["number"]?.jsonPrimitive?.contentOrNull}",
                number = number,
                dateMillis = null,
            )
        }
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val slug = entryId.replace(Regex("^/?novel/"), "").trim('/')
        val title = d.selectFirst(".novel-title")?.text()?.trim() ?: "Untitled Novel"
        val cover = d.selectFirst(".novel-cover")?.attr("src")?.let { abs(it) }
        val summary = d.selectFirst(".summary-content")?.text()?.trim()
        val author = d.selectFirst(".author-link")?.text()?.trim()?.takeIf { it.isNotBlank() && it != "Unknown Author" }
        val statusRaw = d.selectFirst(".status-badge")?.text()?.trim()?.lowercase()
        val status = when (statusRaw) {
            "ongoing" -> "Ongoing"; "completed", "complete" -> "Completed"
            "hiatus", "paused" -> "On Hiatus"; else -> null
        }
        val genres = d.select(".genre-tag").eachText().map { it.trim() }.filter { it.isNotBlank() }
        return EntryDetails(
            entry = Entry(id, entryId, title, cover, ContentType.NOVEL),
            description = summary,
            authors = listOfNotNull(author),
            genres = genres,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = entryId.replace(Regex("^/?novel/"), "").trim('/')
        return chapterPages(slug)
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val container = d.selectFirst("#chapterText") ?: return emptyList()
        container.select("script, style, ins, iframe, .ads, .ad-container, .watermark").remove()
        container.select("[style]").forEach { it.removeAttr("style") }
        return paragraphsOf(container)
    }
}

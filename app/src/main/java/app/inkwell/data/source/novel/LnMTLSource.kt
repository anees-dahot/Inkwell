package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/** Port of LNReader's lnmtl plugin (HTML + JSON volume/chapter endpoints). */
class LnMTLSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "lnmtl"
    override val name = "LnMTL"
    override val baseUrl = "https://lnmtl.com"
    override val lang = "en"

    private fun str(e: Any?): String? = (e as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull

    private suspend fun listing(page: Int, order: String): PagedResult<Entry> {
        val d = doc("$baseUrl/novel?orderBy=$order&order=desc&filter=all&page=$page")
        val items = d.select(".media-left").mapNotNull { m ->
            val href = m.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val img = m.selectFirst("img") ?: return@mapNotNull null
            val slug = href.substringAfter("/novel/", "").trim('/').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = img.attr("alt").ifBlank { return@mapNotNull null }
            Entry(id, slug, title, abs(img.attr("src")), ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty() && d.selectFirst("a[rel=next]") != null)
    }

    override suspend fun popular(page: Int) = listing(page, "favourites")
    override suspend fun latest(page: Int) = listing(page, "date")

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val home = fetch(baseUrl)
        val path = Regex("""prefetch: '/(.*?\.json)""").find(home)?.groupValues?.get(1)
            ?: return PagedResult(emptyList(), false)
        val arr = json("$baseUrl/$path") as? JsonArray ?: return PagedResult(emptyList(), false)
        val items = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = str(o["name"]) ?: return@mapNotNull null
            if (!name.contains(query, ignoreCase = true)) return@mapNotNull null
            val slug = str(o["slug"]) ?: return@mapNotNull null
            Entry(id, slug, name, str(o["image"]), ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.removePrefix("novel/")
        val d = doc("$baseUrl/novel/$slug")
        val img = d.selectFirst("img.img-rounded")
        val title = img?.attr("title")?.takeIf { it.isNotBlank() } ?: slug
        var author: String? = null
        var status: String? = null
        for (dl in d.select("dl")) {
            val key = dl.selectFirst("dt")?.text()?.trim()
            val v = dl.selectFirst("dd")?.text()?.trim()
            if (key == "Authors") author = v else if (key == "Current status") status = v
        }
        val desc = d.select(".description").joinToString("\n\n") { it.text().trim() }.takeIf { it.isNotBlank() }
            ?: d.selectFirst("meta[name=description]")?.attr("content")
        val genres = d.select("ul.list-inline li").map { it.text().trim() }.filter { it.isNotEmpty() }
        return EntryDetails(
            entry = Entry(id, slug, title, abs(img?.attr("src")), ContentType.NOVEL),
            description = desc,
            authors = listOfNotNull(author?.takeIf { it.isNotEmpty() }),
            genres = genres,
            status = status?.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val slug = entryId.removePrefix("novel/")
        val html = fetch("$baseUrl/novel/$slug")
        val raw = Regex("""lnmtl\.volumes = (.+])(?=;)""").find(html)?.groupValues?.get(1) ?: return emptyList()
        val volumes = json.parseToJsonElement(raw).jsonArray
        val out = ArrayList<Episode>()
        for (v in volumes) {
            val vid = str((v as JsonObject)["id"]) ?: continue
            var pageNo = 1
            var last = 1
            while (pageNo <= last) {
                delay(500)
                val o = json("$baseUrl/chapter?volumeId=$vid" + if (pageNo > 1) "&page=$pageNo" else "").jsonObject
                last = o["last_page"]?.let { (it as? JsonPrimitive)?.intOrNull } ?: 1
                o["data"]?.jsonArray?.forEach { el ->
                    val c = el as JsonObject
                    val cslug = str(c["slug"]) ?: return@forEach
                    val num = str(c["number"])
                    out.add(
                        Episode(
                            id = "chapter/$cslug",
                            name = "#$num ${str(c["title"]).orEmpty()}".trim(),
                            number = num?.toFloatOrNull(),
                            dateMillis = parseDate(str(c["created_at"]), "yyyy-MM-dd HH:mm:ss"),
                        )
                    )
                }
                pageNo++
            }
        }
        return out
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        return d.select("sentence.translated").map { it.text().trim().replace('„', '“') }.filter { it.isNotEmpty() }
    }
}

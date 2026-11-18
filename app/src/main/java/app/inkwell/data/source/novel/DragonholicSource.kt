package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder

class DragonholicSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "dragonholic"
    override val name = "Dragonholic"
    override val baseUrl = "https://dragonholictranslations.com"
    override val lang = "en"

    private val cachedChapters = HashMap<String, List<Episode>>()

    private fun urlEncode(term: String) = URLEncoder.encode(term, "UTF-8")

    private fun decodeEntities(text: String): String = Parser.unescapeEntities(text, false)

    private fun normalizePath(path: String): String = path
        .replace(Regex("/{2,}"), "/")
        .trim('/')
        .replace(Regex("^novel/"), "")

    private suspend fun jsonWithHeaders(url: String, extra: Map<String, String>): JsonElement =
        withContext(Dispatchers.IO) {
            val b = request(url).newBuilder()
            extra.forEach { (k, v) -> b.header(k, v) }
            json.parseToJsonElement(client.newCall(b.build()).bodyString())
        }

    /** cheerio's .closest('div[class*="rounded"]') */
    private fun Element.closestRoundedDiv(): Element? {
        var cur: Element? = this
        while (cur != null) {
            if (cur.tagName() == "div" && cur.className().contains("rounded")) return cur
            cur = cur.parent()
        }
        return null
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val url = if (page > 1) "$baseUrl/?updates_page=$page" else "$baseUrl/"
        val d = doc(url)
        val items = mutableListOf<Entry>()
        val seen = mutableSetOf<String>()
        for (el in d.select("[data-latest-updates-content] a[href*=\"/series/\"]")) {
            val m = Regex("/series/([^/]+)/?").find(el.attr("href")) ?: continue
            val slug = m.groupValues[1]
            if (!seen.add(slug)) continue
            val card = el.closestRoundedDiv()
            val nm = card?.select("h3 a")?.firstOrNull()?.text()?.trim()
                ?.takeUnless { it.isEmpty() } ?: el.text().trim()
            if (nm.isEmpty()) continue
            val cover = card?.select("img")?.firstOrNull()?.attr("src")?.takeIf { it.isNotBlank() }
                ?: el.select("img").firstOrNull()?.attr("src")?.takeIf { it.isNotBlank() }
            items.add(Entry(id, slug, nm, abs(cover), ContentType.NOVEL))
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val root = jsonWithHeaders(
            "$baseUrl/api/search?q=${urlEncode(query)}",
            mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"),
        )
        val results = runCatching { root.jsonObject["results"]?.jsonArray }.getOrNull().orEmpty()
        val items = results.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val m = Regex("/series/([^/]+)/?").find(o["url"]?.jsonPrimitive?.contentOrNull ?: "")
                ?: return@mapNotNull null
            Entry(
                id,
                m.groupValues[1],
                decodeEntities(title),
                abs(o["thumbnail"]?.jsonPrimitive?.contentOrNull),
                ContentType.NOVEL,
            )
        }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val novelPath = normalizePath(entryId)
        val body = fetch(absPath("series/$novelPath") + "/")
        val d = org.jsoup.Jsoup.parse(body)

        val title = d.selectFirst("h1")?.text()?.trim().orEmpty()
        val cover = d.selectFirst("[x-data=\"coverModal()\"] img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: d.selectFirst("h1")?.parent()?.parent()?.select("img")?.firstOrNull()?.attr("src")
                ?.takeIf { it.isNotBlank() }
        val author = d.select("a[href*=\"/author/\"]").firstOrNull()?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
        val genres = d.select("a[href*=\"/genre/\"]").map { it.text().trim() }.filter { it.isNotEmpty() }
        val statusText = d.select("div[class*=\"rounded-full\"]")
            .map { it.text().trim().lowercase() }
            .firstOrNull { Regex("ongoing|completed|hiatus|cancelled|dropped").containsMatchIn(it) }
        val status = when {
            statusText == null -> null
            "ongoing" in statusText -> "Ongoing"
            "completed" in statusText -> "Completed"
            "hiatus" in statusText -> "On Hiatus"
            else -> null
        }
        val summary = d.select("[x-ref=\"synopsis\"] p")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && !Regex("^synopsis:?$", RegexOption.IGNORE_CASE).containsMatchIn(it) }
            .joinToString("\n")

        val chapters = mutableListOf<Episode>()
        val seriesId = Regex("seriesId:\\s*(\\d+)").find(body)?.groupValues?.get(1)
        if (seriesId != null) {
            val root = jsonWithHeaders(
                "$baseUrl/api/chapters?series_id=$seriesId&load_all=1&sort_order=asc",
                mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest"),
            )
            val list = runCatching {
                root.jsonObject["data"]?.jsonObject?.get("chapters")?.jsonArray
                    ?: root.jsonObject["chapters"]?.jsonArray
            }.getOrNull().orEmpty()
            list.forEachIndexed { idx, el ->
                val o = runCatching { el.jsonObject }.getOrNull() ?: return@forEachIndexed
                val slug = o["slug"]?.jsonPrimitive?.contentOrNull ?: return@forEachIndexed
                val order = o["chapter_order"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                val heading = o["heading"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() }
                    ?: o["name"]?.jsonPrimitive?.contentOrNull
                val subtitle = o["subtitle"]?.jsonPrimitive?.contentOrNull
                var epName = listOfNotNull(
                    heading?.takeIf { it.isNotBlank() },
                    subtitle?.takeIf { it.isNotBlank() },
                ).joinToString(" - ").ifBlank { slug }
                epName = decodeEntities(epName)
                if (o["is_premium"]?.jsonPrimitive?.booleanOrNull == true) epName = "🔒 $epName"
                chapters.add(
                    Episode(
                        id = "$novelPath/$slug",
                        name = epName,
                        number = order?.takeIf { it > 0 } ?: (idx + 1).toFloat(),
                        dateMillis = parseDate(o["created_at"]?.jsonPrimitive?.contentOrNull, "yyyy-MM-dd'T'HH:mm:ss"),
                    )
                )
            }
        }

        return EntryDetails(
            entry = Entry(id, novelPath, title, abs(cover), ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = listOfNotNull(author),
            genres = genres,
            status = status,
        ).also { cachedChapters[novelPath] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val novelPath = normalizePath(entryId)
        cachedChapters[novelPath]?.let { return it }
        details(novelPath)
        return cachedChapters[novelPath].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath("series/$episodeId") + "/")
        val content = d.selectFirst(".chapter-content") ?: return emptyList()
        content.select("script, style, ins, .ad-container, [data-lumina-ad-code]").remove()
        return paragraphsOf(content)
    }
}

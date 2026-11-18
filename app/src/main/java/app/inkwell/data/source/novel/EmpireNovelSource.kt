package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.net.URLEncoder

/**
 * Empire Novel (empirenovel.com) — a custom Laravel site. Catalog at
 * `/novels-list`, latest at `/?page=N`, JSON search at `/search-live`, full
 * chapter list read from the reader's `<select>` at `/select-partial/...`.
 */
class EmpireNovelSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "empirenovel"
    override val name = "Empire Novel"
    override val baseUrl = "https://www.empirenovel.com"
    override val lang = "en"

    private fun urlEncode(term: String) = URLEncoder.encode(term, "UTF-8")

    private suspend fun fetchDoc(url: String) = org.jsoup.Jsoup.parse(fetch(url), url)

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val d = fetchDoc("$baseUrl/novels-list?page=$page")
        val items = d.select(".novellist_item").mapNotNull { el ->
            val link = el.selectFirst("a:has(h2)") ?: return@mapNotNull null
            val href = link.attr("href").removePrefix("/")
            val nm = link.selectFirst("h2")?.text()?.trim().orEmpty()
            if (href.isEmpty() || nm.isEmpty()) return@mapNotNull null
            Entry(id, href, nm, abs(el.selectFirst("img")?.attr("data-src")), ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        val d = fetchDoc("$baseUrl/?page=$page")
        val items = d.select(".home_release .col_home").mapNotNull { el ->
            val link = el.selectFirst("h4 a") ?: return@mapNotNull null
            val href = link.attr("href").removePrefix("/")
            val nm = link.text().trim()
            if (href.isEmpty() || nm.isEmpty()) return@mapNotNull null
            Entry(id, href, nm, abs(el.selectFirst("img")?.attr("data-src")), ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        // search-live returns every match at once.
        if (page > 1) return PagedResult(emptyList(), false)
        val results = json("$baseUrl/search-live?q=${urlEncode(query)}").jsonArray
        val items = results.mapNotNull { el ->
            val o = runCatching { el.jsonObject }.getOrNull() ?: return@mapNotNull null
            val nm = o["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val slug = o["slug"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val hasCover = (o["cover"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0) != 0
            val cover = if (hasCover) "$baseUrl/uploads/novel/$slug/cover/cover_thumb.jpg" else null
            Entry(id, "novel/$slug", nm, abs(cover), ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val d = fetchDoc(absPath(entryId))
        val title = d.selectFirst("h1[itemprop]")?.text()?.trim()
            ?.takeUnless { it.isEmpty() } ?: "Untitled"

        // The summary is cut into a visible part, a "..." span and a hidden
        // remainder; rejoin the halves where the site split them.
        val desc = d.selectFirst("[itemprop=description]")
        desc?.selectFirst("#read_more")?.remove()
        val summary = desc?.html().orEmpty()
            .replace(Regex(" ?<span id=\"dots\">[\\s\\S]*?</span><span id=\"more\">"), "")
            .let { Jsoup.parse(it).text().trim() }
            .ifBlank { null }

        val statusText = d.select(".show_details > div")
            .firstOrNull { it.text().trim().startsWith("Status") }
            ?.selectFirst("span")?.text()?.trim()
        val status = when (statusText) {
            "Ongoing" -> "Ongoing"
            "Completed" -> "Completed"
            "Abandoned" -> "Cancelled"
            else -> null
        }

        // The novel page only lists 30 chapters per page; read the full list
        // from the reader's chapter selector instead.
        val chapters = mutableListOf<Episode>()
        val chapterHref = d.selectFirst("a.chapter_link")?.attr("href")
        val chapterSlug = chapterHref?.substringAfterLast('/')
        val novelSlug = entryId.substringAfterLast('/').trim('/')
        if (!chapterSlug.isNullOrBlank() && novelSlug.isNotBlank()) {
            val list = fetchDoc("$baseUrl/select-partial/$novelSlug/$chapterSlug")
            for (option in list.select("option")) {
                val href = option.attr("value").removePrefix("/")
                val nm = option.text().trim()
                if (href.isEmpty() || nm.isEmpty()) continue
                val number = href.substringAfterLast('/').toDoubleOrNull()?.toFloat()
                chapters.add(Episode(id = href, name = nm, number = number, dateMillis = null))
            }
            // The selector lists the newest chapter first.
            chapters.reverse()
        }

        return EntryDetails(
            entry = Entry(id, entryId, title, abs(d.selectFirst("img.show_image")?.attr("data-src")), ContentType.NOVEL),
            description = summary,
            authors = listOfNotNull(
                d.selectFirst("[itemprop=author]")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            ),
            genres = d.select("[itemprop=genre]").eachText().map { it.trim() }.filter { it.isNotEmpty() },
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = fetchDoc(absPath(episodeId))
        val content = d.selectFirst("#read-novel") ?: return emptyList()
        content.select("script, style, iframe, object, embed, form, noscript, ins").remove()
        // Drop event handlers and javascript: URLs; the text is rendered as-is.
        for (el in content.select("*")) {
            val keys = el.attributes().map { it.key }
            for (key in keys) {
                val value = el.attr(key)
                if (key.startsWith("on", ignoreCase = true) ||
                    value.replace(Regex("\\s+"), "").contains("javascript:", ignoreCase = true)
                ) el.removeAttr(key)
            }
        }
        return paragraphsOf(content)
    }
}

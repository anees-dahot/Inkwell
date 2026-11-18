package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/** Port of LNReader's puffinFolio plugin (Next.js site, plain HTML). */
class PuffinFolioSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "puffinfolio"
    override val name = "Puffin Folio"
    override val baseUrl = "https://www.puffinfolio.com"
    override val lang = "en"

    /** Covers go through next/image; the real file sits in a url= param. */
    private fun cover(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cand = raw.trim().split(Regex("\\s+"))[0]
        val emb = Regex("url=([^&]+)").find(cand)
        val file = emb?.let { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") } ?: cand
        return abs(file)
    }

    private suspend fun novelList(): List<Entry> {
        val d = doc("$baseUrl/browse")
        val out = ArrayList<Entry>()
        val seen = HashSet<String>()
        for (a in d.select("a[href^=/novel/]")) {
            val href = a.attr("href")
            if (href.contains("/chapter/")) continue
            val img = a.selectFirst("img")
            val title = a.selectFirst("h3")?.text()?.trim().takeUnless { it.isNullOrEmpty() } ?: img?.attr("alt")?.trim()
            if (title.isNullOrEmpty()) continue
            val path = href.trimStart('/')
            if (!seen.add(path)) continue
            out += Entry(id, path, title, cover(img?.attr("src")?.ifEmpty { img.attr("srcset") }), ContentType.NOVEL)
        }
        return out
    }

    override suspend fun popular(page: Int) =
        if (page > 1) PagedResult(emptyList(), false) else PagedResult(novelList(), false)

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val t = query.lowercase()
        return PagedResult(novelList().filter { it.title.lowercase().contains(t) }, false)
    }

    private fun chapters(d: Document): List<Episode> {
        val out = ArrayList<Episode>()
        val seen = HashSet<String>()
        for (a in d.select("li a[href*=/chapter/]")) {
            val href = a.attr("href").takeIf { it.isNotEmpty() } ?: continue
            val path = href.trimStart('/')
            if (!seen.add(path)) continue
            val time = a.selectFirst("time")
            val label = a.selectFirst("span")?.clone()
            label?.select("span")?.remove()
            val num = Regex("/chapter/(\\d+)").find(href)?.groupValues?.get(1)?.toFloatOrNull()
            out += Episode(
                id = path,
                name = label?.text()?.trim().takeUnless { it.isNullOrEmpty() } ?: href.substringAfterLast('/'),
                number = num,
                dateMillis = parseDate(time?.attr("datetime")?.ifEmpty { null } ?: time?.attr("title")),
            )
        }
        return out.sortedBy { it.number ?: 0f }
    }

    private val chapterCache = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc("$baseUrl/${entryId.trimStart('/')}")
        val coverEl = d.selectFirst("img[src*=covers], img[srcset*=covers]")
        val statusText = d.select("span").map { it.text().trim() }.firstOrNull { it == "Ongoing" || it == "Completed" }
        chapterCache[entryId] = chapters(d)
        return EntryDetails(
            entry = Entry(id, entryId, d.selectFirst("h1")?.text()?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled",
                cover(coverEl?.attr("src")?.ifEmpty { coverEl.attr("srcset") }), ContentType.NOVEL),
            description = d.selectFirst("meta[name=description]")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() },
            authors = emptyList(),
            genres = d.select("a[href^=/browse?tag=]").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = statusText,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        chapterCache[entryId]?.let { return it }
        details(entryId)
        return chapterCache[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> =
        paragraphsOf(doc("$baseUrl/${episodeId.trimStart('/')}").selectFirst(".reader-prose"))
}

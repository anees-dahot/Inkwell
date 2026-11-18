package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import java.net.URLEncoder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Port of LNReader's indraTranslations plugin. */
class IndraTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "indratranslations"
    override val name = "Indra Translations"
    override val baseUrl = "https://indratranslations.com"
    override val lang = "en"

    override val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"
    override val headers = mapOf(
        "Referer" to "https://indratranslations.com",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun clean(s: String?) = (s ?: "").replace(Regex("\\s+"), " ").trim()

    private fun relative(url: String) =
        url.replace(Regex("^https?://(www\\.)?indratranslations\\.com(?=[/?#]|$)"), "")

    private fun imgUrl(img: Element?): String? {
        img ?: return null
        val s = img.attr("data-src").ifEmpty { img.attr("data-lazy-src") }.ifEmpty { img.attr("src") }
        return if (s.isBlank()) null else abs(s.trim())
    }

    private fun seriesUrl(page: Int, params: Map<String, String>): String {
        val q = params.filter { it.value.isNotEmpty() }.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val base = if (page > 1) "$baseUrl/series/page/$page/" else "$baseUrl/series/"
        return if (q.isNotEmpty()) "$base?$q" else base
    }

    private suspend fun cards(url: String): PagedResult<Entry> {
        val d = doc(url)
        val onclick = Regex("location\\.href\\s*=\\s*'([^']+)'")
        val items = d.select(".series-grid .series-card").mapNotNull { card ->
            val href = onclick.find(card.attr("onclick"))?.groupValues?.get(1)
                ?: card.selectFirst("a[href]")?.attr("href")?.ifEmpty { null } ?: return@mapNotNull null
            val title = clean(card.selectFirst(".series-card-title")?.attr("title"))
                .ifEmpty { clean(card.selectFirst(".series-card-title")?.text()) }
                .ifEmpty { clean(card.selectFirst("img")?.attr("alt")) }
            if (title.isEmpty()) return@mapNotNull null
            Entry(id, relative(href), title, imgUrl(card.selectFirst(".series-card-cover img")), ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = cards(seriesUrl(page, mapOf("orderby" to "new")))
    override suspend fun latest(page: Int) = cards(seriesUrl(page, mapOf("orderby" to "update")))
    override suspend fun search(query: String, page: Int) = cards(seriesUrl(page, mapOf("keyword" to query)))

    private fun sitePath(entryId: String) =
        relative(entryId).replace(Regex("^/series/([^/?#]+)/?(?:[?#].*)?$"), "/$1/")

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(sitePath(entryId)))
        val title = clean(d.selectFirst("h1.story-main-title")?.text())
            .ifEmpty { clean(d.selectFirst("h1")?.text()) }.ifEmpty { "Untitled" }
        val summary = d.select("#story-synopsis p").map { clean(it.text()) }.filter { it.isNotEmpty() }
            .joinToString("\n\n").ifEmpty { clean(d.selectFirst("#story-synopsis")?.text()) }.ifEmpty { null }
        val st = clean(d.selectFirst(".story-meta-list a[href*=/trang-thai/]")?.text()).lowercase()
        val status = when {
            st.contains("complete") -> "Completed"
            st.contains("ongoing") -> "Ongoing"
            st.contains("drop") -> "Cancelled"
            st.contains("hiatus") -> "On Hiatus"
            else -> null
        }
        return EntryDetails(
            entry = Entry(id, entryId, title, imgUrl(d.selectFirst(".story-cover-card img")), ContentType.NOVEL),
            description = summary,
            authors = emptyList(),
            genres = d.select(".story-meta-list a[href*=/series-genre/]").map { clean(it.text()) }.filter { it.isNotEmpty() },
            status = status,
        )
    }

    private fun chapterNum(name: String) =
        Regex("(\\d+(\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toFloatOrNull()

    override suspend fun episodes(entryId: String): List<Episode> {
        val html = fetch(absPath(sitePath(entryId)))
        val raw = Regex("TD_Story_Chapters\\s*=\\s*(\\[.*\\]);").find(html)?.groupValues?.get(1)
        if (raw != null) {
            val list = runCatching {
                json.parseToJsonElement(raw).jsonArray.mapNotNull { el ->
                    val o = el.jsonObject
                    val link = o["link"]?.jsonPrimitive?.content?.ifEmpty { null } ?: return@mapNotNull null
                    val title = clean(o["title"]?.jsonPrimitive?.content)
                    val vip = o["vip"]?.jsonPrimitive?.content?.toDoubleOrNull() == 1.0
                    Episode(
                        relative(link), (if (vip) "🔒 " else "") + title,
                        o["num"]?.jsonPrimitive?.content?.toFloatOrNull() ?: chapterNum(title),
                        parseDate(o["date"]?.jsonPrimitive?.content),
                    )
                }
            }.getOrNull()
            if (list != null) return list
        }
        val d = Jsoup.parse(html, baseUrl)
        return d.select("#chapter-list-container .chapter-item-wrapper a[href]").map { a ->
            val nm = clean(a.selectFirst(".text-truncate")?.text())
            Episode(
                relative(a.attr("href")),
                (if (a.selectFirst(".chap-price-badge") != null) "🔒 " else "") + nm,
                chapterNum(nm), null,
            )
        }.reversed()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.selectFirst("#chapter-content-text") ?: d.selectFirst(".chapter-content") ?: return emptyList()
        content.select(
            ".td-copy-honeypot, .td-ad-container, .td-s-noise, .td-s-para-noise, .td-hidden-watermark, .td-canary-trap",
        ).remove()
        val flow = content.selectFirst(".td-reading-flow")
        if (flow != null) {
            val sorted = flow.children().withIndex()
                .sortedWith(compareBy({ it.value.attr("data-order").toDoubleOrNull() ?: 0.0 }, { it.index }))
                .map { it.value }
            val out = ArrayList<String>()
            for (el in sorted) {
                el.select("script, style, ins, iframe, noscript").remove()
                val t = el.text().replace(' ', ' ').trim()
                if (t.isNotEmpty()) out.add(t)
            }
            return out
        }
        return paragraphsOf(content)
    }
}

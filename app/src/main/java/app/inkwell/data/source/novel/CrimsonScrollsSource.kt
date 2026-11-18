package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

/** Port of LNReader's crimsonscrolls plugin (WordPress + custom REST). */
class CrimsonScrollsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "crimsonscrolls"
    override val name = "Crimson Scrolls"
    override val baseUrl = "https://crimsonscrolls.net"
    override val lang = "en"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun toPath(url: String): String =
        runCatching { URI(baseUrl).resolve(url).path.removePrefix("/") }.getOrDefault(url)

    private fun img(el: Element?): String? {
        el ?: return null
        val s = el.attr("data-src").ifEmpty { el.attr("src") }
        return if (s.isEmpty() || s.startsWith("data:")) null else abs(s)
    }

    private suspend fun browse(page: Int, params: String): PagedResult<Entry> {
        val d = doc("$baseUrl/novels/?$params&cs_page=$page")
        val items = d.select("article.cs-browse-card").mapNotNull { card ->
            val link = card.selectFirst("h2 a") ?: return@mapNotNull null
            val href = link.attr("href").ifEmpty { return@mapNotNull null }
            val title = link.attr("title").ifEmpty { link.text() }.trim()
            Entry(id, toPath(href), title, img(card.selectFirst(".cs-browse-card__cover img")), ContentType.NOVEL)
        }
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = browse(page, "sort=top-rated")
    override suspend fun latest(page: Int) = browse(page, "sort=latest-updated")
    override suspend fun search(query: String, page: Int) = browse(page, "s=${enc(query)}")

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc("$baseUrl/$entryId")
        val status = when (d.selectFirst(".cs-cover-status")?.text()?.trim()?.lowercase()) {
            "ongoing" -> "Ongoing"
            "completed" -> "Completed"
            "on hiatus", "hiatus" -> "On Hiatus"
            "dropped", "cancelled" -> "Cancelled"
            else -> null
        }
        return EntryDetails(
            entry = Entry(
                id, entryId,
                d.selectFirst(".cs-novel-info h1")?.text()?.trim().orEmpty().ifEmpty { "Untitled" },
                img(d.selectFirst(".cs-cover img")), ContentType.NOVEL,
            ),
            description = d.select(".cs-synopsis-content p").map { it.text().trim() }
                .filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { null },
            authors = listOfNotNull(
                d.selectFirst(".cs-novel-creator-card--author strong")?.text()?.trim()?.ifEmpty { null },
            ),
            genres = d.select(".cs-detail-genres a").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val d = doc("$baseUrl/$entryId")
        val panel = d.selectFirst("[data-novel-chapters]")
        val novelId = panel?.attr("data-novel-chapters")?.ifEmpty { null }
            ?: d.selectFirst("main.cs-novel-page")?.attr("data-view-id")?.ifEmpty { null }
            ?: return emptyList()
        val tiers = d.select("[data-chapter-access-tier]").map { it.attr("data-chapter-access-tier") }
            .filter { it.isNotEmpty() }.toMutableList()
        if (tiers.isEmpty()) tiers.add(panel?.attr("data-default-tier")?.ifEmpty { null } ?: "free")

        val out = ArrayList<Episode>()
        for (tier in tiers) {
            val locked = tier != "free"
            var page = 1
            var pages = 1
            do {
                val o = json(
                    "$baseUrl/wp-json/crimsonscrolls/v2/novel-chapters?novel_id=${enc(novelId)}" +
                        "&tier=${enc(tier)}&page=$page&per_page=100&order=ASC",
                ).jsonObject
                o["items"]?.jsonArray?.forEach { el ->
                    val c = el.jsonObject
                    val num = c["number"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val title = c["title"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    val nm = "Chapter $num" + if (title.isNotEmpty() && title != "null") ": $title" else ""
                    val url = c["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    out.add(
                        Episode(
                            toPath(url), if (locked) "🔒 $nm" else nm,
                            (out.size + 1).toFloat(), parseDate(c["date"]?.jsonPrimitive?.contentOrNull),
                        ),
                    )
                }
                pages = o["pages"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
                page++
            } while (page <= pages)
        }
        return out
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val url = if (episodeId.startsWith("http")) episodeId
        else if (!episodeId.contains('/')) "$baseUrl/chapter/$episodeId/"
        else "$baseUrl/$episodeId"
        val content = doc(url).selectFirst("article.cs-reader") ?: return emptyList()
        if (content.select(".cs-tier-gate").isNotEmpty()) return emptyList()
        content.select("header.cs-reader-title, .cs-chapter-ad, .cs-copy-watermark").remove()
        return paragraphsOf(content)
    }
}

package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FaqWikiUsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    // TS id is 'FWK.US' verbatim.
    override val id = "FWK.US"
    override val name = "Faq Wiki"
    override val baseUrl = "https://faqwiki.xyz"
    override val lang = "en"

    private fun coverOf(el: Element?): String? {
        val img = el?.selectFirst("img") ?: return null
        val ez = img.attr("data-ezsrc").takeIf { it.isNotBlank() }
            ?: img.attr("src").takeIf { it.isNotBlank() }
            ?: return null
        return abs(ez.replace(Regex("\\?ezimgfmt=.*$"), ""))
    }

    private fun parseNovels(d: Document, searchTerm: String? = null): List<Entry> {
        val novels = d.select(".plt-page-item").mapNotNull { el ->
            val nm = el.text().replace("Novel – All Chapters", "").trim()
            val href = el.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            // "http:" -> "https:", then strip the site prefix and trailing slashes.
            val path = href.replaceFirst("tp:", "tps:")
                .replaceFirst(baseUrl + "/", "")
                .trimEnd('/')
            if (path.isEmpty()) return@mapNotNull null
            Entry(id, path, nm, coverOf(el), ContentType.NOVEL)
        }
        if (searchTerm != null) {
            return novels.filter { it.title.lowercase().contains(searchTerm.lowercase()) }
        }
        return novels
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(parseNovels(doc(baseUrl)), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(parseNovels(doc(baseUrl), query), false)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        d.select("script").remove()

        val name = d.select(".entry-title").text()
            .replace("Novel – All Chapters", "").trim()
            .ifBlank { "Untitled" }

        val statusText = d.selectFirst("#lcp_instance_0")?.nextElementSibling()?.text()?.lowercase().orEmpty()
        val status = if (statusText.contains("complete")) "Completed" else "Ongoing"

        var summary: String? = null
        var author: String? = null
        var genres: List<String> = emptyList()
        for (strong in d.select(".entry-content strong")) {
            val key = strong.text().trim().lowercase()
            val parent = strong.parent() ?: continue
            val values = buildList {
                add(parent.text().drop(key.length).trim())
                // Siblings after the label paragraph, up to the next labeled one.
                var next: Element? = parent.nextElementSibling()
                while (next != null) {
                    val isLabeled = next.tagName() == "p" && next.select("strong").isNotEmpty()
                    if (isLabeled) break
                    add(next.text().trim())
                    next = next.nextElementSibling()
                }
            }
            when (key) {
                "description:" -> summary = values.joinToString("\n").ifBlank { null }
                "author(s):" -> author = values.firstOrNull()?.ifBlank { null }
                "genre:" -> {
                    var genreText = values.joinToString(" ").trim()
                    val multiWordGenres = listOf("Slice of Life", "School Life")
                    for (genre in multiWordGenres) {
                        genreText = genreText.replace(Regex("\\b${Regex.escape(genre)}\\b"), genre.replace(" ", "_"))
                    }
                    genres = genreText.split(Regex("\\s+"))
                        .map { it.replace("_", " ").trim() }
                        .filter { it.isNotEmpty() && it != "," }
                }
            }
        }

        val chapters = d.select(".lcp_catlist li a").mapIndexed { index, a ->
            val nm = a.text().replace(name, "").replace("Novel", "").trim()
            val path = a.attr("href").replaceFirst(baseUrl + "/", "").trimEnd('/')
            Episode(id = path, name = nm, number = (index + 1).toFloat(), dateMillis = null)
        }

        return EntryDetails(
            entry = Entry(id, entryId, name, coverOf(d.selectFirst(".wp-block-image")), ContentType.NOVEL),
            description = summary,
            authors = listOfNotNull(author),
            genres = genres,
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
        val d = doc(absPath(episodeId))
        val content = d.selectFirst(".entry-content") ?: return emptyList()
        d.select("script").remove()
        content.select("span, div").remove()
        return paragraphsOf(content)
    }
}

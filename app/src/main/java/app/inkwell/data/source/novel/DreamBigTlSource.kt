package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient
import java.net.URLEncoder

class DreamBigTlSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "dreambigtl"
    override val name = "Dream Big Translations"
    override val baseUrl = "https://www.dreambigtl.com"
    override val lang = "en"

    private fun urlEncode(term: String) = URLEncoder.encode(term, "UTF-8")

    /** Site-relative path, no leading slash ("p/some-novel.html"). */
    private fun pathOf(href: String): String =
        href.removePrefix("$baseUrl/").removePrefix("/")

    // ---- listing -----------------------------------------------------------

    private suspend fun listPage(page: Int, latest: Boolean): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val d = doc("$baseUrl/p/disclaimer.html")
        val items = mutableListOf<Entry>()
        val categories = listOf("New Novels", "Ongoing Novels", "Completed Novels")

        for (cat in d.select("#webify-pro-main-nav-menu > li.has-sub")) {
            val categoryName = cat.children().firstOrNull { it.tagName() == "a" }?.text()?.trim() ?: continue
            if (categoryName !in categories) continue
            for (a in cat.select("ul.sub-menu.m-sub li > a")) {
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
                items.add(Entry(id, pathOf(href), a.text().trim(), null, ContentType.NOVEL))
            }
        }

        if (items.isEmpty()) {
            // Fallback: try to parse every content page link as a novel.
            for (a in d.select("a")) {
                val href = a.attr("href")
                if (href.contains("/p/") && !href.contains("disclaimer")) {
                    items.add(Entry(id, pathOf(href), a.text().trim(), null, ContentType.NOVEL))
                }
            }
        }

        val finalItems = if (latest) items.sortedByDescending { it.id } else items
        return PagedResult(finalItems, false)
    }

    override suspend fun popular(page: Int) = listPage(page, latest = false)

    override suspend fun latest(page: Int) = listPage(page, latest = true)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val d = doc("$baseUrl/search?q=${urlEncode(query)}")
        val items = d.select(".blog-posts.index-post-wrap .blog-post.hentry.index-post").mapNotNull { el ->
            val a = el.selectFirst(".entry-title a") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Entry(
                id,
                pathOf(href),
                a.text().trim(),
                abs(el.selectFirst(".entry-image")?.attr("data-image")),
                ContentType.NOVEL,
            )
        }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private fun numFromName(n: String): Float? =
        Regex("(\\d+(?:\\.\\d+)?)").findAll(n).lastOrNull()?.value?.toFloatOrNull()

    private fun getNovelStatus(novelPath: String): String? =
        if (novelPath.contains("completed")) "Completed" else "Ongoing"

    private suspend fun parseChapters(d: org.jsoup.nodes.Document): List<Episode> {
        val chapters = mutableListOf<Episode>()

        // Parse "Free Tier" chapters from collapsible panels.
        for (panel in d.select(".chapter-panel")) {
            val panelTitle = panel.selectFirst("summary")?.text()?.trim().orEmpty()
            if (panelTitle != "Free Tier") continue
            for (a in panel.select("ul li a")) {
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
                chapters.add(Episode(pathOf(href), a.text().trim(), numFromName(a.text()), null))
            }
        }

        // Fallback: "List of Chapters" header followed by a list.
        if (chapters.isEmpty()) {
            for (header in d.select("h2:contains(List of Chapters), span:contains(List of Chapters)")) {
                val list = header.nextElementSibling()?.takeIf { it.tagName() == "ul" } ?: continue
                for (a in list.select("li a")) {
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
                    chapters.add(Episode(pathOf(href), a.text().trim(), numFromName(a.text()), null))
                }
            }
        }

        return chapters.reversed()
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val postBody = d.selectFirst(".post-body")
        val author = postBody?.text()
            ?.let { Regex("Author:\\s*(.+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.trim()
        val title = d.selectFirst("h1.entry-title")?.text()?.trim()
            ?.takeUnless { it.isEmpty() } ?: "Untitled"
        return EntryDetails(
            entry = Entry(id, entryId, title, abs(postBody?.select("img")?.firstOrNull()?.attr("src")), ContentType.NOVEL),
            description = postBody?.select("p")?.firstOrNull()?.text()?.trim()?.ifBlank { null },
            authors = listOfNotNull(author?.takeIf { it.isNotEmpty() }),
            genres = emptyList(),
            status = getNovelStatus(entryId),
        ).also { cachedChapters[entryId] = parseChapters(d) }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        return paragraphsOf(d.selectFirst(".post-body"))
    }
}

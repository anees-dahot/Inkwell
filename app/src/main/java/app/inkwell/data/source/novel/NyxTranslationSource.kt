package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

/**
 * Port of LNReader's nyxtranslation plugin — a WordPress.com blog where the
 * novels are the pages linked from the top menu, grouped by reading status,
 * and chapters are dated posts (e.g. /2024/01/03/some-novel-chapter-30).
 */
class NyxTranslationSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "nyxtranslation"
    override val name = "NyX Translation"
    override val baseUrl = "https://nyx-translation.com"
    override val lang = "en"

    private data class NovelEntry(val name: String, val path: String, val section: String?)

    // The blog moved from nyxtranslation.home.blog; old links still resolve.
    private val siteHost = Regex("^(?:(?:www\\.)?nyx-translation\\.com|nyxtranslation\\.home\\.blog)$", RegexOption.IGNORE_CASE)
    private val nonNovelPaths = setOf(
        "novel-list", "jp-novel", "kr-novel", "active", "completed",
        "on-hold", "dropped", "patronage", "contact",
    )
    private val sections = mapOf(
        "active" to "active", "completed" to "completed",
        "on-hold" to "on-hold", "dropped" to "dropped",
    )
    private val sectionStatus = mapOf(
        "active" to "Ongoing", "completed" to "Completed",
        "on-hold" to "On Hiatus", "dropped" to "Cancelled",
    )
    // Chapters are dated posts, e.g. 2024/01/03/some-novel-chapter-30.
    private val chapterPath = Regex("^(\\d{4})/(\\d{2})/(\\d{2})/[^/]+$")
    private val genericChapterName =
        Regex("^(?:part\\s*\\d+|chapter\\s*\\d+|prologue|epilogue|afterword|illustrations?|interlude|extra)$", RegexOption.IGNORE_CASE)
    private val volumeLabel = Regex("^(?:volume|vol\\.?)\\s*\\d+\\s*:?$", RegexOption.IGNORE_CASE)
    private val navigationLink = Regex("^(?:<<|«)?\\s*(?:previous|next|table of contents?)", RegexOption.IGNORE_CASE)
    private val pageSize = 20

    private val cachedChapters = HashMap<String, List<Episode>>()

    /** Site-relative path (no leading slash) of an on-site link, or null. */
    private fun pathOf(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val url = runCatching { baseUrl.toHttpUrl().resolve(href) }.getOrNull() ?: return null
        if (!siteHost.matches(url.host)) return null
        return url.encodedPath.trimEnd('/').trimStart('/').ifEmpty { null }
    }

    /** The novels are the pages linked from the top menu, grouped by status. */
    private suspend fun novelList(): List<NovelEntry> {
        val d = doc(baseUrl)
        val novels = mutableListOf<NovelEntry>()
        val seen = HashSet<String>()
        d.select("#top-menu a").forEach { anchor ->
            val path = pathOf(anchor.attr("href")) ?: return@forEach
            // Novel pages live at a single-segment address.
            if (path.contains('/')) return@forEach
            if (path in nonNovelPaths || path in seen) return@forEach
            val novelName = anchor.text().replace(Regex("\\s+"), " ").trim()
            if (novelName.isEmpty()) return@forEach
            seen.add(path)
            var section: String? = null
            for (li in anchor.parents()) {
                if (li.tagName() != "li") continue
                val parentPath = pathOf(li.children().firstOrNull { it.tagName() == "a" }?.attr("href"))
                if (section == null && parentPath != null) section = sections[parentPath]
            }
            novels.add(NovelEntry(novelName, path, section))
        }
        return novels
    }

    private fun coverOf(content: Element?): String? {
        val src = content?.select("img")?.firstOrNull()?.attr("src") ?: return null
        return if (src.startsWith("http") && src.isNotBlank()) abs(src) else null
    }

    private suspend fun toNovelItems(novels: List<NovelEntry>): List<Entry> = coroutineScope {
        novels.map { novel ->
            async {
                val cover = runCatching { coverOf(doc(absPath(novel.path))) }.getOrNull()
                Entry(id, novel.path, novel.name, cover, ContentType.NOVEL)
            }
        }.awaitAll()
    }

    private suspend fun pageOf(novels: List<NovelEntry>, pageNo: Int): PagedResult<Entry> {
        val from = (pageNo - 1) * pageSize
        val to = pageNo * pageSize
        if (from >= novels.size) return PagedResult(emptyList(), false)
        val slice = novels.subList(from, to.coerceAtMost(novels.size))
        return PagedResult(toNovelItems(slice), to < novels.size)
    }

    // Default filters: section "all".
    override suspend fun popular(page: Int): PagedResult<Entry> = pageOf(novelList(), page)
    override suspend fun latest(page: Int): PagedResult<Entry> = pageOf(novelList(), page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return PagedResult(emptyList(), false)
        // The site's own search returns chapter posts, so match titles against the menu.
        val novels = novelList().filter { novel -> terms.all { novel.name.lowercase().contains(it) } }
        return pageOf(novels, page)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val entries = runCatching { novelList() }.getOrDefault(emptyList())
        val entry = entries.firstOrNull { it.path == entryId.trim('/') }
        val d = doc(absPath(entryId))
        val content = d.selectFirst(".entry-content") ?: return EntryDetails(
            entry = Entry(id, entryId, entry?.name ?: "Untitled", null, ContentType.NOVEL),
            description = null, authors = emptyList(), genres = emptyList(), status = null,
        )
        content.select("script, style, noscript, .sharedaddy, #jp-post-flair").remove()

        val title = entry?.name
            ?: d.selectFirst("h1.entry-title")?.text()?.replace(Regex("\\s+"), " ")?.trim()
            ?: "Untitled"
        val cover = coverOf(content)

        var genres: String? = null
        var author: String? = null
        val summaryParts = mutableListOf<String>()
        var volume = ""
        var label = ""
        var inDescription = false
        var inContents = false
        val chapters = mutableListOf<Episode>()
        val seen = HashSet<String>()

        content.select("p, h1, h2, h3, h4, h5, h6").forEach { block ->
            // Nested blocks are visited on their own.
            if (block.select("p, h1, h2, h3, h4, h5, h6").any { it !== block }) return@forEach
            val text = block.text().replace(Regex("\\s+"), " ").trim()

            var hasChapter = false
            for (a in block.select("a[href]")) {
                val path = pathOf(a.attr("href")) ?: continue
                val linkText = a.text().replace(Regex("\\s+"), " ").trim()
                if (linkText.isEmpty() || !chapterPath.matches(path)) continue
                hasChapter = true
                if (path in seen) continue
                seen.add(path)
                val parts = mutableListOf<String>()
                if (genericChapterName.matches(linkText)) {
                    // "Volume 2 Epilogue" already names its volume.
                    if (volume.isNotEmpty() && !label.lowercase().startsWith(volume.lowercase())) parts.add(volume)
                    if (label.isNotEmpty()) parts.add(label)
                    if (!label.lowercase().contains(linkText.lowercase())) parts.add(linkText)
                } else {
                    parts.add(linkText)
                }
                val m = chapterPath.find(path)!!
                val (year, month, day) = Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                val dateMillis = runCatching {
                    java.time.LocalDate.of(year.toInt(), month.toInt(), day.toInt())
                        .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
                }.getOrNull()
                chapters.add(
                    Episode(
                        id = path,
                        name = parts.joinToString(" – "),
                        number = null,
                        dateMillis = dateMillis,
                    ),
                )
            }
            if (hasChapter || text.isEmpty()) return@forEach

            if (inContents) {
                // Context printed between chapter links: volumes and chapter groups.
                when {
                    volumeLabel.matches(text) -> {
                        volume = text.replace(Regex("\\s*:$"), "")
                        label = ""
                    }
                    text.length <= 80 && !text.startsWith("You can read", ignoreCase = true) -> label = text
                }
                return@forEach
            }

            if (Regex("^(?:type|genre|author|artist)\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                val lines = block.html().split(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE))
                    .map { line ->
                        org.jsoup.Jsoup.parse("<p>$line</p>").selectFirst("p")?.text()
                            ?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
                    }
                for (line in lines) {
                    val field = Regex("^(type|genre|author|artist)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(line) ?: continue
                    val key = field.groupValues[1].lowercase()
                    val value = field.groupValues[2].trim()
                    when (key) {
                        "genre" -> genres = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")
                        "author" -> author = value
                        else -> {} // "artist" is tracked separately in the TS plugin
                    }
                }
                return@forEach
            }
            if (Regex("^description$", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                inDescription = true
                return@forEach
            }
            if (Regex("^(?:alternative names?|table of contents?)", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                inDescription = false
                inContents = Regex("^table of contents?", RegexOption.IGNORE_CASE).containsMatchIn(text)
                return@forEach
            }
            if (inDescription) summaryParts.add(text)
        }

        val chaptersEpisodes = chapters.also { cachedChapters[entryId] = it }
        return EntryDetails(
            entry = Entry(id, entryId, title, cover, ContentType.NOVEL),
            description = summaryParts.joinToString("\n\n").ifBlank { null },
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = (genres ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() },
            status = entry?.section?.let { sectionStatus[it] },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.selectFirst(".entry-content") ?: return emptyList()
        content.select(
            "script, style, iframe, noscript, form, .sharedaddy, #jp-post-flair, " +
                ".wp-block-spacer, #wordads-inline-marker",
        ).remove()
        // "<< Previous | Table of Content | Next >>" line at the end.
        content.select("p").forEach { p ->
            val links = p.select("a")
            when {
                links.isNotEmpty() && links.all { navigationLink.containsMatchIn(it.text().replace(Regex("\\s+"), " ").trim()) } -> p.remove()
                p.text().trim().isEmpty() && p.select("img").isEmpty() -> p.remove()
            }
        }
        if (content.select("img").isEmpty() && content.text().trim().length < 200) {
            return emptyList()
        }
        return paragraphsOf(content)
    }
}

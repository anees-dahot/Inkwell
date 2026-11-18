package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class MiumiuSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "miumiu"
    override val name = "Miumiu's musings"
    override val baseUrl = "https://miumiu596.wordpress.com"
    override val lang = "en"

    private data class CatalogueEntry(
        val slug: String,
        val novelName: String,
        val cover: String?,
        val summary: String,
        val status: String?,
    )

    private data class NovelRule(
        val slug: String,
        val heading: Regex,
        val categories: List<String>,
        val title: Regex,
    )

    private val rules = listOf(
        NovelRule(
            "rolling-love",
            Regex("rolling\\s*love", RegexOption.IGNORE_CASE),
            listOf("Rolling Love"),
            Regex("rolling\\s*love", RegexOption.IGNORE_CASE),
        ),
        NovelRule(
            "same-place-not-same-bed",
            Regex("same\\s*place", RegexOption.IGNORE_CASE),
            listOf("Same bed"),
            Regex("same\\s*place|\\bsp[nsb]{3,4}\\b", RegexOption.IGNORE_CASE),
        ),
        NovelRule(
            "nuptial-chamber-next-door",
            Regex("nuptial", RegexOption.IGNORE_CASE),
            listOf("Nuptial Chamber", "NCND"),
            Regex("nuptial|ncnd", RegexOption.IGNORE_CASE),
        ),
        NovelRule(
            "loving-you-is-too-difficult",
            Regex("loving\\s*you", RegexOption.IGNORE_CASE),
            listOf("Love you"),
            Regex("loving\\s*you|lyitd", RegexOption.IGNORE_CASE),
        ),
        NovelRule(
            "if-you-dont-go-to-hell-who-will",
            Regex("go\\s*to\\s*hell", RegexOption.IGNORE_CASE),
            listOf("IYDGTH", "If you"),
            Regex("go\\s*to\\s*hell|ifydgth|ifygth|iydgth|iydth|iydgh", RegexOption.IGNORE_CASE),
        ),
    )

    /** Novel names and abbreviations repeated in front of / behind chapter titles. */
    private val tags =
        "(?:rolling\\s*love|nuptial\\s*chamber\\s*next\\s*door|same\\s*place\\s*not\\s*same\\s*bed|" +
            "loving\\s*you\\s*is\\s*too\\s*difficult|if\\s*you\\s*don.?t\\s*go\\s*to\\s*hell,?\\s*who\\s*will\\s*\\??|" +
            "sp[nsb]{3,4}|ncnd|lyitd|ifydgth|ifygth|iydgth|iydth|iydgh)"
    private val leadingTag = Regex("^$tags\\s*[–—:-]*\\s*", RegexOption.IGNORE_CASE)
    private val trailingTag = Regex("\\s*[–—:-]+$tags$", RegexOption.IGNORE_CASE)

    /** A post title that names a chapter, as opposed to an intro or announcement. */
    private val chapterTitle = Regex("prologue|epilogue|side\\s*story|\\bss\\b|\\d", RegexOption.IGNORE_CASE)

    private fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun novelPathOf(slug: String): String = "index/#$slug"

    private fun toSlug(novelPath: String): String =
        novelPath.substringAfter('#', "").ifBlank { novelPath.trim('/') }

    /** Site-relative path of a chapter post, from a path or absolute URL. */
    private fun toChapterPath(href: String): String =
        href.replace(Regex("^https?://miumiu596\\.wordpress\\.com", RegexOption.IGNORE_CASE), "")

    // ---- catalogue ---------------------------------------------------------

    /** Novels in index-page order, each with its cover and synopsis. */
    private suspend fun fetchCatalogue(): List<CatalogueEntry> {
        val d = doc(absPath("index/"))
        val entries = mutableListOf<CatalogueEntry>()
        var current: CatalogueEntry? = null
        var summary = mutableListOf<String>()

        for (p in d.selectFirst(".entry-content")?.select("> p").orEmpty()) {
            val text = clean(p.text())

            val rule = if (p.select("b, strong").isNotEmpty()) {
                rules.firstOrNull { it.heading.containsMatchIn(text) }
            } else {
                null
            }
            if (rule != null && text.length < 150) {
                current?.let { entries += it.copy(summary = summary.joinToString("\n")) }
                summary = mutableListOf()
                current = CatalogueEntry(rule.slug, headingName(text), null, "", headingStatus(text))
                continue
            }
            val cur = current ?: continue

            val image = p.selectFirst("img[src]")?.attr("src")?.takeIf { it.isNotBlank() }
            if (image != null) {
                if (cur.cover == null) current = cur.copy(cover = abs(image))
                continue
            }
            // Chapter links and blank lines are not part of the synopsis.
            if (text.isNotEmpty() && p.select("a[href]").isEmpty()) {
                summary += cleanSummaryLine(text)
            }
        }
        current?.let { entries += it.copy(summary = summary.joinToString("\n")) }
        return entries
    }

    /** "洞房花烛，隔壁 ~ Nuptial Chamber Next Door ( 54 + 2 SS Completed)" -> name. */
    private fun headingName(heading: String): String =
        heading.substringBefore("(").substringAfterLast("~").trim()

    private fun headingStatus(heading: String): String? = when {
        Regex("completed", RegexOption.IGNORE_CASE).containsMatchIn(heading) -> "Completed"
        Regex("in progress", RegexOption.IGNORE_CASE).containsMatchIn(heading) -> "Ongoing"
        else -> null
    }

    private fun cleanSummaryLine(line: String): String =
        Regex("^.*~\\s*synopsis\\s*:\\s*", RegexOption.IGNORE_CASE).replace(line, "")

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        // The index page holds the whole catalogue; there is no pagination.
        if (page > 1) return PagedResult(emptyList(), false)
        val items = fetchCatalogue().map {
            Entry(id, novelPathOf(it.slug), it.novelName, it.cover, ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val term = query.trim().lowercase()
        val items = fetchCatalogue()
            .filter { it.novelName.lowercase().contains(term) }
            .map { Entry(id, novelPathOf(it.slug), it.novelName, it.cover, ContentType.NOVEL) }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val slug = toSlug(entryId)
        val entry = fetchCatalogue().firstOrNull { it.slug == slug }
            ?: throw IllegalStateException("Miumiu: novel $slug is not listed on the index page")
        val chapters = fetchChapters(slug)
        return EntryDetails(
            entry = Entry(id, novelPathOf(slug), entry.novelName, entry.cover, ContentType.NOVEL),
            description = entry.summary.ifBlank { null },
            authors = emptyList(),
            genres = emptyList(),
            status = entry.status,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId] ?: emptyList()
    }

    // ---- chapters ----------------------------------------------------------

    /** Every post of the blog, via the public WordPress.com API (100 a page). */
    private suspend fun fetchPosts(): List<JsonObject> {
        val posts = mutableListOf<JsonObject>()
        var found = 0
        for (page in 1..20) {
            val data = json(
                "https://public-api.wordpress.com/rest/v1.1/sites/miumiu596.wordpress.com/posts/" +
                    "?number=100&page=$page&order=ASC&order_by=date&fields=ID,URL,title,date,categories",
            ).jsonObject
            val batch = data["posts"]?.jsonArray
                ?: throw IllegalStateException("Miumiu: the post list could not be read")
            found = data["found"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0
            posts += batch.map { it.jsonObject }
            if (batch.size < 100 || posts.size >= found) break
        }
        return posts
    }

    private suspend fun fetchChapters(slug: String): List<Episode> {
        if (rules.none { it.slug == slug }) return emptyList()

        data class Item(
            val name: String,
            val path: String,
            val date: String,
            val postId: Long,
            val order: Double,
        )

        val items = mutableListOf<Item>()
        for (post in fetchPosts()) {
            val title = clean(Jsoup.parse(post["title"]?.jsonPrimitive?.contentOrNull ?: "").text())
            if (!chapterTitle.containsMatchIn(title) || novelOf(post, title) != slug) continue
            items += Item(
                name = chapterName(title),
                path = toChapterPath(post["URL"]?.jsonPrimitive?.contentOrNull ?: "").trimStart('/'),
                date = post["date"]?.jsonPrimitive?.contentOrNull ?: "",
                postId = post["ID"]?.jsonPrimitive?.longOrNull ?: 0L,
                order = chapterOrder(title),
            )
        }
        items.sortWith(compareBy({ it.order }, { it.date }, { it.postId }))
        // Reading order comes from the title, so the number is the position.
        return items.mapIndexed { index, item ->
            Episode(item.path, item.name, (index + 1).toFloat(), parseDate(item.date))
        }
    }

    /** The novel a post belongs to: its category first, then words in its title. */
    private fun novelOf(post: JsonObject, title: String): String? {
        val categories = (post["categories"] as? JsonObject)?.keys ?: emptySet()
        rules.firstOrNull { r -> r.categories.any { it in categories } }?.let { return it.slug }
        return rules.firstOrNull { it.title.containsMatchIn(title) }?.slug
    }

    /** Reading position from a title: "Ch 16/3 and 17" -> 16.3, "Epilogue" last. */
    private fun chapterOrder(title: String): Double {
        if (Regex("prologue", RegexOption.IGNORE_CASE).containsMatchIn(title)) return -1.0
        val match = Regex("(\\d+)(?:[./](\\d+))?").find(title)
        val number = if (match != null) {
            (match.groupValues[1] + "." + match.groupValues[2].ifBlank { "0" }).toDoubleOrNull() ?: 0.0
        } else {
            0.0
        }
        if (Regex("side\\s*story|\\bss\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)) return 10000.0 + number
        if (Regex("epilogue", RegexOption.IGNORE_CASE).containsMatchIn(title)) return 5000.0
        return number
    }

    /** "NCND – Ch 41.1" -> "Chapter 41.1": drop the repeated novel name. */
    private fun chapterName(title: String): String {
        val name = leadingTag.replace(title, "").let { trailingTag.replace(it, "") }.trim()
        val chapter = Regex("^(?:chapter|ch|c)\\.?\\s*[–—:-]*\\s*(\\d.*)$", RegexOption.IGNORE_CASE).find(name)
        if (chapter != null) return "Chapter " + chapter.groupValues[1]
        val label = name.ifBlank { title }
        return if (Regex("^\\d").containsMatchIn(label)) {
            "Chapter $label"
        } else {
            label.replaceFirstChar { it.uppercase() }
        }
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val path = toChapterPath(episodeId).trimStart('/')
        val d = doc(absPath(path))
        val content = d.selectFirst(".entry-content") ?: return emptyList()
        content.select("script, style, .sharedaddy, .jp-relatedposts").remove()
        content.select("[id^=atatags], #wordads-inline-marker").remove()
        content.select("p").forEach { p ->
            if (p.text().replace(Regex("\\s+"), "").isEmpty() && p.select("img").isEmpty()) p.remove()
        }
        return paragraphsOf(content)
    }
}

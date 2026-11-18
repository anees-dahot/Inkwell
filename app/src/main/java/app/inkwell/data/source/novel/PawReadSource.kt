package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.ZoneId

/**
 * Port of LNReader's pawread plugin (m.pawread.com).
 */
class PawReadSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "pawread"
    override val name = "PawRead"
    override val baseUrl = "https://m.pawread.com"
    override val lang = "en"

    private val cachedChapters = HashMap<String, List<Episode>>()

    private fun parseNovelCards(d: org.jsoup.nodes.Document): List<Entry> =
        d.select(".list-comic, .itemBox").mapNotNull { box ->
            val a = box.select("a").firstOrNull {
                it.attr("class") == "txtA" || it.attr("class") == "title"
            } ?: return@mapNotNull null
            val href = a.attr("href")
            if (href.isBlank()) return@mapNotNull null
            // href "/book/12345/x" -> "book/12345"
            val path = href.split('/').drop(1).take(2).joinToString("/")
            if (path.isEmpty()) return@mapNotNull null
            val name = a.text().trim()
            val cover = box.selectFirst("img")?.attr("src")
            if (name.isEmpty() || cover == null) return@mapNotNull null
            Entry(id, path, name, abs(cover), ContentType.NOVEL)
        }

    // Default filters: no status/lang/genre segment, order off, sort "click".
    override suspend fun popular(page: Int): PagedResult<Entry> {
        val url = absPath("list/") + "click/?page=$page"
        val items = parseNovelCards(doc(url))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val url = absPath("search/").toHttpUrl().newBuilder()
            .addQueryParameter("keywords", query)
            .addQueryParameter("page", page.toString())
            .build()
        val items = parseNovelCards(doc(url.toString()))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun details(entryId: String): EntryDetails {
        val novelPath = entryId.trim('/')
        val d = doc(absPath("$novelPath/"))

        val coverImg = d.selectFirst("#Cover img")
        val title = coverImg?.attr("title")?.trim()?.takeIf { it.isNotEmpty() } ?: "Untitled"
        val cover = abs(coverImg?.attr("src"))

        val meta = d.select("p.txtItme").map { it.text().trim() }
        val status = meta.getOrNull(0)?.takeIf { it.isNotEmpty() }
        val author = meta.getOrNull(1)?.takeIf { it.isNotEmpty() }

        val genres = d.select("a.btn-default").eachText().map { it.trim() }.filter { it.isNotEmpty() }

        val summary = d.selectFirst("#full-des")?.let { des -> textWithBreaks(des) }?.trim().orEmpty()

        val chapters = d.select("div.item-box").mapNotNull { box ->
            val number = Regex("\\d+").find(box.attr("onclick"))?.value ?: return@mapNotNull null
            val path = "$novelPath/$number.html"
            val timeText = box.children().firstOrNull { it.tagName() == "span" }?.text()?.trim().orEmpty()
            val name = box.select("span").firstOrNull { it.parent() !== box }?.text()?.trim()
                ?: box.select("span").first()?.text()?.trim().orEmpty()
            val dateMillis = parseLocalDate(timeText)
            Episode(
                id = path,
                name = name.ifEmpty { "Chapter $number" },
                number = number.toFloatOrNull(),
                dateMillis = dateMillis,
            )
        }.also { cachedChapters[novelPath] = it }

        return EntryDetails(
            entry = Entry(id, novelPath, title, cover, ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = listOfNotNull(author),
            genres = genres,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val novelPath = entryId.trim('/')
        cachedChapters[novelPath]?.let { return it }
        details(novelPath)
        return cachedChapters[novelPath].orEmpty()
    }

    /** "dd.mm.yyyy" printed in the chapter list, parsed like the TS plugin. */
    private fun parseLocalDate(raw: String): Long? {
        val parts = raw.split('.').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 3) return null
        return runCatching {
            LocalDate.of(parts[0], parts[1], parts[2])
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun textWithBreaks(el: Element): String {
        val sb = StringBuilder()
        el.childNodes().forEach { node ->
            when (node) {
                is org.jsoup.nodes.TextNode -> sb.append(node.text())
                is Element -> if (node.tagName() == "br") sb.append('\n') else sb.append(textWithBreaks(node))
            }
        }
        return sb.toString()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val root = d.selectFirst("div.main") ?: return emptyList()
        // Promo links ("pawread", "tinyurl", "bit.ly") hide their whole tag.
        root.select("*").forEach { el ->
            val own = el.ownText().lowercase()
            if (listOf("pawread", "tinyurl", "bit.ly").any { own.contains(it) }) {
                el.remove()
            }
        }
        return paragraphsOf(root)
    }
}

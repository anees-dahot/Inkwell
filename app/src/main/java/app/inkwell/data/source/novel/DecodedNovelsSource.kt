package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DecodedNovelsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "decodednovels"
    override val name = "Decoded Novels"
    override val baseUrl = "https://decodednovels.ca"
    override val lang = "en"

    private val pageSize = 20
    private val novelPathRegex = Regex("^/novels/[^/]+/?$", RegexOption.IGNORE_CASE)

    private data class ListedNovel(val name: String, val path: String, val cover: String?, val status: String?)

    private val cachedChapters = HashMap<String, List<Episode>>()

    private fun cleanText(text: String) = text.replace(Regex("\\s+"), " ").trim()

    private fun urlEncode(term: String) = URLEncoder.encode(term, "UTF-8")

    /** Site-relative path of a link to this site, or null for any other host. */
    private fun sitePath(href: String?): String? {
        if (href.isNullOrBlank()) return null
        val url = runCatching { baseUrl.toHttpUrlOrNull()!!.resolve(href) }.getOrNull() ?: return null
        return if (url.host == "decodednovels.ca" || url.host == "www.decodednovels.ca") {
            url.encodedPath
        } else null
    }

    /**
     * The "Novel Lists" page is the whole catalogue; a closing blurb line says
     * "Completed: <month>" or "DROPPED"; novels without one have no status.
     */
    private suspend fun catalogue(): List<ListedNovel> {
        val d = doc("$baseUrl/novels/")
        val novels = mutableListOf<ListedNovel>()
        val seen = mutableSetOf<String>()
        for (column in d.select(".entry-content .wp-block-column")) {
            val link = column.selectFirst("figure a[href]") ?: continue
            val path = sitePath(link.attr("href")) ?: continue
            if (!novelPathRegex.matches(path)) continue
            val nm = column.select("h2").firstOrNull()?.text()?.let { cleanText(it) }.orEmpty()
            if (nm.isEmpty() || !seen.add(path)) continue
            val note = column.select("p").lastOrNull()?.text()?.let { cleanText(it) }.orEmpty()
            val status = when {
                Regex("^completed\\b", RegexOption.IGNORE_CASE).containsMatchIn(note) -> "Completed"
                Regex("^dropped\\b", RegexOption.IGNORE_CASE).containsMatchIn(note) -> "Dropped"
                else -> null
            }
            novels.add(ListedNovel(nm, path, link.select("img").firstOrNull()?.attr("src"), status))
        }
        return novels
    }

    private fun toItems(novels: List<ListedNovel>, page: Int): List<Entry> =
        novels.drop((page - 1) * pageSize).take(pageSize).map {
            Entry(id, it.path, it.name, abs(it.cover), ContentType.NOVEL)
        }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val filtered = catalogue() // default status filter is "all"
        return PagedResult(toItems(filtered, page), page * pageSize < filtered.size)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val term = cleanText(query)
        if (term.isEmpty()) return PagedResult(emptyList(), false)
        val listed = catalogue()
        val matches: List<ListedNovel> = try {
            // The page search returns the matching novel pages.
            val root = json("$baseUrl/wp-json/wp/v2/pages?per_page=100&_fields=link&search=${urlEncode(term)}").jsonArray
            val found = root.mapNotNull { el ->
                runCatching {
                    el.jsonObject["link"]?.jsonPrimitive?.contentOrNull?.let { sitePath(it)?.trimEnd('/') }
                }.getOrNull()
            }.toHashSet()
            listed.filter { it.path.trimEnd('/') in found }
        } catch (e: Exception) {
            val words = term.lowercase().split(' ')
            listed.filter { n -> words.all { n.name.lowercase().contains(it) } }
        }
        return PagedResult(toItems(matches, page), page * pageSize < matches.size)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val listed = runCatching { catalogue() }.getOrDefault(emptyList())
        val listedEntry = listed.firstOrNull { it.path.trimEnd('/') == entryId.trimEnd('/') }
        val content = d.select(".entry-content").first()
            ?: throw IllegalStateException("$name: no content at $entryId")

        val name = listedEntry?.name
            ?: d.selectFirst("meta[property=og:title]")?.attr("content")?.let { cleanText(it) }
                ?.takeUnless { it.isEmpty() }
            ?: cleanText(d.title().replace(Regex("\\s+[–-]\\s+[^–-]*$"), ""))
                .takeUnless { it.isEmpty() }
            ?: "Untitled"

        var author: String? = null
        var artist: String? = null
        var genres: List<String> = emptyList()
        val fieldRx = Regex("^(Author|Artist|Genre)s?\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        for (p in content.select("p")) {
            val m = fieldRx.matchEntire(cleanText(p.text())) ?: continue
            val value = m.groupValues[2].trim()
            when (m.groupValues[1].lowercase()) {
                "author" -> author = value
                "artist" -> artist = value
                else -> genres = value.split(Regex("\\s*,\\s*")).map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        // The blurb is every paragraph after the "Summary:" label in its box.
        val label = content.select("p").firstOrNull {
            Regex("^Summary\\s*:?$", RegexOption.IGNORE_CASE).matches(cleanText(it.text()))
        }
        val summary = label?.nextElementSiblings()
            ?.filter { (it as Element).tagName() == "p" }
            ?.mapNotNull { cleanText(it.text()).takeUnless(String::isEmpty) }
            ?.joinToString("\n\n")
            ?.takeUnless { it.isEmpty() }

        val chapters = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        val dateRx = Regex("^/(\\d{4})/(\\d{2})/(\\d{2})/")
        for (a in content.select(".collapsible-container .content a[href]")) {
            val path = sitePath(a.attr("href")) ?: continue
            if (!seen.add(path)) continue
            val dateM = dateRx.find(path)
            val nm = cleanText(a.text()).trimEnd(':')
                .ifBlank { path.split('/').filter { it.isNotEmpty() }.lastOrNull() ?: path }
            chapters.add(
                Episode(
                    id = path,
                    name = nm,
                    number = null,
                    dateMillis = dateM?.let {
                        parseDate("${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}", "yyyy-MM-dd")
                    },
                )
            )
        }
        // The chapter list is newest first; the app wants reading order.
        val numbered = chapters.reversed().mapIndexed { i, e -> e.copy(number = (i + 1).toFloat()) }

        return EntryDetails(
            entry = Entry(id, entryId, name, abs(content.select("figure img").firstOrNull()?.attr("src")), ContentType.NOVEL),
            description = summary,
            authors = listOfNotNull(author, artist),
            genres = genres,
            status = listedEntry?.status,
        ).also { cachedChapters[entryId] = numbered }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.select(".entry-content .post-content").first() ?: return emptyList()
        // A chapter selector and TOC button, plus prev/next links, frame the text.
        content.children().filter { it.tagName() == "div" && it.selectFirst("select") != null }
            .forEach { it.remove() }
        content.select("script, style, iframe, noscript, form, .wp-post-navigation, .sharedaddy").remove()
        content.select("p").forEach { p ->
            if (p.text().isBlank() && p.select("img").isEmpty()) p.remove()
        }
        if (content.select("img").isEmpty() && content.text().trim().length < 200) return emptyList()
        return paragraphsOf(content)
    }
}

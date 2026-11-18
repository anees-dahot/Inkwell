package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * EatApplePies (eatapplepies.com) — a small WordPress fan-translation site.
 * Novels are menu pages linking to a hand-written table of contents page;
 * chapters keep their text in `.entry-content`.
 */
class EatApplePiesSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "eatapplepies"
    override val name = "EatApplePies"
    override val baseUrl = "https://eatapplepies.com"
    override val lang = "en"

    /** Chapters live directly under the site root, e.g. `/tcf-chapter-12/`. */
    private val chapterPath = Regex("^/[^/]+/$")

    /** Menu entries that are never a novel. */
    private val notANovel = Regex(
        "^/(?:mybookmarks|information|eap-merchandise-store|recruitment|wp-login\\.php)(?:/|$)|^/originals/$",
        RegexOption.IGNORE_CASE,
    )

    /** Previous / Index / Next navigation words at the end of a chapter. */
    private val navWords = Regex("(previous|next|index|toc|chapter)", RegexOption.IGNORE_CASE)

    /** Info lines on a novel page that are not part of the synopsis. */
    private val metaLine = Regex(
        "^(author|translator|editor|artist|release rate|estimated chapter dates?|series logo|tcf subscriptions)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun normalizeText(text: String) = text.replace(Regex("\\s+"), " ").trim()

    /** Normalize a URL or href to a site-relative path with a trailing slash. */
    private fun toPath(href: String): String {
        val raw = href.trim()
        val sameSite = Regex("^https?://(?:www\\.)?eatapplepies\\.com(/.*)?$", RegexOption.IGNORE_CASE).find(raw)
        if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(raw) && sameSite == null) return raw
        val clean = (sameSite?.groupValues?.get(1) ?: raw).split(Regex("[?#]"))[0]
        val path = if (clean.startsWith("/")) clean else "/$clean"
        return if (path.endsWith("/")) path else "$path/"
    }

    private fun entryContent(d: Document): Element? = d.select(".entry-content").first()

    private class NovelPage(val path: String, val name: String, val cover: String?)

    private suspend fun loadPage(path: String): Document {
        val d = doc(absPath(path.removePrefix("/")))
        d.select("script, style, [class*=\"cbxwpbkmark\"]").remove()
        return d
    }

    private fun toNovelPage(path: String, d: Document): NovelPage {
        val name = normalizeText(d.select("h1.entry-title").first()?.text() ?: "")
        val image = entryContent(d)?.select("img[src]")?.firstOrNull()?.attr("src")
        return NovelPage(path, name, image?.takeIf { it.startsWith(baseUrl) })
    }

    /** Links to the table(s) of contents; one per volume for a series. */
    private fun tocLinks(d: Document): List<Pair<String, String>> {
        val links = mutableListOf<Pair<String, String>>()
        entryContent(d)?.select("a[href]")?.forEach { a ->
            val label = normalizeText(a.text())
            if (!Regex("table of contents", RegexOption.IGNORE_CASE).containsMatchIn(label)) return@forEach
            val path = toPath(a.attr("href"))
            if (path.startsWith("/") && links.none { it.first == path }) links.add(path to label)
        }
        return links
    }

    /** Novels are the menu pages that link to a table of contents. */
    private suspend fun fetchCatalogue(): List<NovelPage> {
        val d = doc(baseUrl + "/")
        val paths = mutableListOf<String>()
        d.select(".menu > li > a, .menu a[href*=\"/originals/\"]").forEach { a ->
            val path = toPath(a.attr("href"))
            if (path.startsWith("/") && path != "/" && !notANovel.containsMatchIn(path) && path !in paths) {
                paths.add(path)
            }
        }
        val pages = mutableListOf<NovelPage>()
        var failures = 0
        for (path in paths) {
            try {
                val novelPage = loadPage(path)
                if (tocLinks(novelPage).isNotEmpty()) pages.add(toNovelPage(path, novelPage))
            } catch (e: Exception) {
                failures++
            }
        }
        if (pages.isEmpty() && failures > 0) throw IllegalStateException("$name: could not load any novel page")
        return pages
    }

    private fun toItem(novel: NovelPage) =
        Entry(id, novel.path.trimStart('/'), novel.name, abs(novel.cover), ContentType.NOVEL)

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(fetchCatalogue().map(::toItem), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val term = query.trim().lowercase()
        return PagedResult(fetchCatalogue().filter { it.name.lowercase().contains(term) }.map(::toItem), false)
    }

    // ---- details -----------------------------------------------------------

    /** Value of an "Author:" line on the novel page. */
    private fun novelMeta(d: Document, key: String): String? {
        var value: String? = null
        entryContent(d)?.select("p")?.forEach { el ->
            if (value != null) return value
            el.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
            for (line in el.wholeText().split('\n')) {
                val m = Regex("^$key\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(normalizeText(line)) ?: continue
                value = m.groupValues[1].trim()
                break
            }
        }
        return value
    }

    /** Synopsis paragraphs: the text between the info lines and the contents. */
    private fun novelSummary(d: Document): String? {
        val lines = mutableListOf<String>()
        entryContent(d)?.select("p")?.forEach { el ->
            if (el.select("img").isNotEmpty() && el.text().trim().isEmpty()) return@forEach
            el.select("br").forEach { it.replaceWith(org.jsoup.nodes.TextNode("\n")) }
            val text = el.wholeText().split('\n')
                .map(::normalizeText)
                .filter { it.isNotEmpty() && !metaLine.containsMatchIn(it) }
                .joinToString("\n")
                .replace(Regex("^(synopsis|summary|description)\\s*:?\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            if (Regex("table of contents$", RegexOption.IGNORE_CASE).containsMatchIn(text)) return@forEach
            if (Regex("^begin reading$", RegexOption.IGNORE_CASE).containsMatchIn(text)) return@forEach
            if (text.isNotEmpty() && !Regex("^[-—–_\\s]+$").matches(text)) lines.add(text)
        }
        return lines.joinToString("\n").ifBlank { null }
    }

    /** Chapters of every table of contents, in site order. */
    private suspend fun novelChapters(d: Document): List<Episode> {
        val tocs = tocLinks(d)
        val chapters = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for ((path, label) in tocs) {
            val volume = if (tocs.size > 1) Regex("^volume\\s*\\d+", RegexOption.IGNORE_CASE).find(label)?.value ?: "" else ""
            val tocPage = loadPage(path)
            entryContent(tocPage)?.select("a[href]")?.forEach { a ->
                val linkPath = toPath(a.attr("href"))
                if (!chapterPath.matches(linkPath) || notANovel.containsMatchIn(linkPath) || !seen.add(linkPath)) {
                    return@forEach
                }
                var name = normalizeText(a.text()).trimStart('-', '–', '—', ' ')
                if (name.isEmpty() || Regex("^chapter\\s*\\d*\\s*:?$", RegexOption.IGNORE_CASE).matches(name)) {
                    val number = Regex("(\\d+)/$").find(linkPath)?.groupValues?.get(1)
                    name = number?.let { "Chapter $it" } ?: name.ifEmpty { linkPath }
                }
                chapters.add(
                    Episode(
                        id = linkPath.trimStart('/'),
                        name = if (volume.isNotEmpty()) "$volume - $name" else name,
                        number = (chapters.size + 1).toFloat(),
                        dateMillis = null,
                    )
                )
            }
        }
        return chapters
    }

    override suspend fun details(entryId: String): EntryDetails {
        val path = "/" + entryId.trim('/')
        val d = loadPage(path)
        val page = toNovelPage(path, d)
        val chapters = novelChapters(d)
        if (chapters.isEmpty()) throw IllegalStateException("$name: no chapters listed at $path")
        return EntryDetails(
            entry = Entry(id, entryId, page.name.ifBlank { "Untitled" }, abs(page.cover), ContentType.NOVEL),
            description = novelSummary(d),
            authors = listOfNotNull(novelMeta(d, "author")?.takeIf { it.isNotBlank() }),
            genres = emptyList(),
            status = null,
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId.trimStart('/')))
        val content = entryContent(d) ?: return emptyList()
        content.select("script, style, .entry-meta, .sharedaddy").remove()
        content.select("[class*=\"cbxwpbkmark\"]").remove()
        // Drop the "Previous | Index | Next Chapter" navigation line.
        content.select("h3, p").forEach { el ->
            if (el.select("a[href]").isNotEmpty() &&
                el.text().replace(navWords, "").replace(Regex("[|<>\\s]"), "").isEmpty()
            ) el.remove()
        }
        if (content.text().trim().isEmpty() && content.select("img").isEmpty()) return emptyList()
        return paragraphsOf(content)
    }
}

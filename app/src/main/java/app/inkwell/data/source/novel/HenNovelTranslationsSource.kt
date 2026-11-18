package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

/**
 * Port of LNReader's `hennoveltranslations` plugin. Novels are listed on the
 * homepage; free chapters live in the page's single `episode-list2` list
 * (episode-list1 is paid advance access), which the plugin validates strictly.
 */
class HenNovelTranslationsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "hennoveltranslations"
    override val name = "Hen Novel Translations"
    override val baseUrl = "https://hennoveltranslations.org"
    override val lang = "en"

    private val chapterListRegex = Regex(
        "<ul\\b[^>]*\\bclass\\s*=\\s*([\"'])[^\"']*\\bepisode-list2\\b[^\"']*\\1[^>]*>[\\s\\S]*?</ul\\s*>",
        RegexOption.IGNORE_CASE,
    )

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val d = doc(absPath(""))
        val novels = mutableListOf<Entry>()
        for (card in d.select(".book-item-feature")) {
            val title = card.selectFirst(".book-title a") ?: continue
            val href = title.attr("href")
            if (href.isEmpty() || title.text().trim().isEmpty()) continue
            val image = card.selectFirst("img")
            val cover = (image?.attr("data-src")?.takeIf { it.isNotEmpty() } ?: image?.attr("src"))
            novels += Entry(
                sourceId = id,
                id = resolvePath(href),
                title = title.text().trim(),
                coverUrl = abs(cover),
                type = ContentType.NOVEL,
            )
        }
        check(novels.isNotEmpty()) { "Hen Novel Translations: the project list could not be read." }
        return PagedResult(novels, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val needle = query.trim().lowercase()
        val items = popular(page).items.filter { novel ->
            "${novel.title} ${novel.id.replace('-', ' ')}".lowercase().contains(needle)
        }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    override suspend fun details(entryId: String): EntryDetails {
        val path = resolvePath(entryId)
        val html = fetch(absPath(path))
        val d = org.jsoup.Jsoup.parse(html, absPath(path))
        val name = d.selectFirst(".single-novel-title h1")?.text()?.trim().orEmpty()
        check(name.isNotEmpty()) { "Hen Novel Translations: the novel could not be read." }
        val detailsEl = d.selectFirst(".custom-fields")
        val chapters = parseChapterList(html, d)
        val summaryEl = detailsEl?.clone()
        summaryEl?.select("h2, .alternate-chapters, p")?.remove()
        return EntryDetails(
            entry = Entry(
                sourceId = id,
                id = path,
                title = name,
                coverUrl = abs(d.selectFirst(".novel-content img")?.attr("src")),
                type = ContentType.NOVEL,
            ),
            description = summaryEl?.text()?.trim()?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(field(detailsEl, "Author:")),
            genres = listOfNotNull(field(detailsEl, "Genre:")),
            status = field(detailsEl, "Light Novel Status(Korean):"),
        ).also { cachedChapters[path] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        val path = resolvePath(entryId)
        cachedChapters[path]?.let { return it }
        val html = fetch(absPath(path))
        return parseChapterList(html, org.jsoup.Jsoup.parse(html, absPath(path)))
            .also { cachedChapters[path] = it }
    }

    private fun field(details: Element?, label: String): String? {
        val paragraph = details?.select("p")
            ?.firstOrNull { it.selectFirst("strong")?.text()?.trim() == label } ?: return null
        val clone = paragraph.clone()
        clone.select("strong").remove()
        return clone.text().trim().takeIf { it.isNotEmpty() }
    }

    private fun parseChapterList(html: String, d: org.jsoup.nodes.Document): List<Episode> {
        val lists = d.select(".episode-list2")
        val completeLists = chapterListRegex.findAll(html).count()
        val list = lists.first() ?: throw IllegalStateException("Hen Novel Translations: the free chapter list is incomplete or has changed.")
        val rows = list?.children()?.filter { it.tagName() == "li" }.orEmpty()
        val group = generateSequence(list?.parent()) { it.parent() }
            .firstOrNull { it.hasClass("episode-group") }
        val groupHasPagination = group?.select(".pagination, a[rel=next], .load-more, [data-next-page]")
            ?.isNotEmpty() == true
        check(
            lists.size == 1 && completeLists == 1 && rows.isNotEmpty() &&
                list.select("li").size == rows.size && list.select("a").size == rows.size &&
                !groupHasPagination,
        ) { "Hen Novel Translations: the free chapter list is incomplete or has changed." }
        val declaredTotal = list.attr("data-total").takeIf { it.isNotBlank() }
        if (declaredTotal != null && declaredTotal.toIntOrNull() != rows.size) {
            throw IllegalStateException("Hen Novel Translations: not all free chapters were returned.")
        }

        val chapters = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (row in rows) {
            val links = row.select("a[href]")
            check(links.size == 1 && links.first()?.text()?.trim()?.isNotEmpty() == true) {
                "Hen Novel Translations: a free chapter link is missing."
            }
            val link = links.first()!!
            val url = runCatching { abs(link.attr("href"))!!.toHttpUrl() }
                .getOrElse { throw IllegalStateException("Hen Novel Translations: a free chapter link is missing.") }
            val pathname = url.encodedPath
            val postId = url.queryParameter("p")
            val chapterPath = when {
                pathname.startsWith("/episodes/") -> pathname.trimStart('/')
                pathname == "/" && url.queryParameter("post_type") == "episodes" &&
                    postId != null && Regex("^\\d+$").matches(postId) && postId.toLong() > 0 ->
                    "?post_type=episodes&p=${postId.toLong()}"
                else -> null
            }
            check(chapterPath != null && seen.add(chapterPath)) {
                "Hen Novel Translations: an invalid or repeated free chapter was returned."
            }
            val title = link.text().trim()
            val number = Regex("(?:episode|chapter)\\s+(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
                .find(title)?.groupValues?.get(1)
            chapters += Episode(
                id = chapterPath,
                name = title,
                number = number?.toFloatOrNull(),
                dateMillis = parseDate(row.selectFirst("time")?.attr("datetime")),
            )
        }
        return chapters.reversed()
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val body = d.selectFirst(".episode-content")
            ?: throw IllegalStateException("Hen Novel Translations: no readable public chapter was found.")
        check(body.select("input[type=password]").isEmpty() &&
            !Regex("^(?:This content is|Please log in|You must be logged)", RegexOption.IGNORE_CASE)
                .containsMatchIn(body.text().trim())
        ) { "Hen Novel Translations: this chapter is not publicly readable." }
        body.select("script, style, iframe, form, button, .episode-navigation, .adsbygoogle").remove()
        body.select("[style]").forEach { it.removeAttr("style") }
        check(body.text().trim().length >= 200) {
            "Hen Novel Translations: no readable public chapter was found."
        }
        return paragraphsOf(body)
    }

    /** Resolve a href against the site; reject foreign origins (URL-class check). */
    private fun resolvePath(path: String): String {
        val base = baseUrl.toHttpUrl()
        val resolved = runCatching { base.resolve(path.trim()) }
            .getOrElse { throw IllegalStateException("Hen Novel Translations: invalid page address.") }
            ?: throw IllegalStateException("Hen Novel Translations: invalid page address.")
        check(resolved.host == base.host) { "Hen Novel Translations: invalid page address." }
        return resolved.encodedPath.trimStart('/') + (resolved.query?.let { "?$it" } ?: "")
    }
}

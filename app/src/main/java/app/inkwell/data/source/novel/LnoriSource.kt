package app.inkwell.data.source.novel

import org.jsoup.nodes.Element
import kotlinx.serialization.json.contentOrNull

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class LnoriSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "lnori"
    override val name = "LNORI"
    override val baseUrl = "https://lnori.com"
    override val lang = "en"

    private data class LibNovel(
        val path: String,
        val title: String,
        val cover: String?,
        val author: String,
        val tags: List<String>,
    )

    // ---- listing -----------------------------------------------------------

    private suspend fun libraryNovels(): List<LibNovel> {
        val d = doc(absPath("library"))
        return d.select("article.card").mapNotNull { el ->
            val title = el.attr("data-t").trim()
            val author = el.attr("data-a").trim()
            val tags = el.attr("data-tags").split(',').map { it.trim().lowercase() }
            val cover = el.selectFirst(".card-cover img")?.attr("src")
            val path = el.selectFirst("a.stretched-link")?.attr("href")
                ?.removePrefix("/")?.takeIf { it.isNotBlank() }
            if (path == null || title.isEmpty()) return@mapNotNull null
            LibNovel(path, title, cover?.takeIf { it.isNotBlank() }, author, tags)
        }
    }

    private fun pageOf(list: List<LibNovel>, page: Int): PagedResult<Entry> {
        val pageSize = 36
        val offset = (page - 1) * pageSize
        val items = list.drop(offset).take(pageSize).map {
            Entry(sourceId = id, id = it.path, title = it.title, coverUrl = abs(it.cover), type = ContentType.NOVEL)
        }
        return PagedResult(items, offset + pageSize < list.size)
    }

    override suspend fun popular(page: Int): PagedResult<Entry> = pageOf(libraryNovels(), page)

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val term = query.lowercase()
        val filtered = libraryNovels().filter {
            it.title.lowercase().contains(term) ||
                it.author.lowercase().contains(term) ||
                it.tags.any { t -> t.contains(term) }
        }
        return pageOf(filtered, page)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    private fun cleanText(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val title = d.selectFirst(".hero-card h1.s-title")?.text()?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled"
        val cover = d.selectFirst(".hero-card .cover-wrap img")?.attr("src")?.takeIf { it.isNotBlank() }

        val tagsAttr = d.selectFirst("nav.tags-box.desktop")?.attr("data-tags")
        val genres = tagsAttr?.let { attr ->
            runCatching {
                kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                    .parseToJsonElement(attr).jsonArray
                    .mapNotNull { el -> el.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            }.getOrNull()
        }?.filter { it.isNotBlank() }
            ?: d.select("nav.tags-box.desktop a, nav.tags-box a").eachText().map { it.trim() }.filter { it.isNotEmpty() }

        val summary = d.select("section.desc-box p.description")
            .map { it.text().trim() }.filter { it.isNotEmpty() }
            .joinToString("\n\n")
        val author = d.selectFirst(".hero-card p.author")?.text()?.trim()

        val chapters = volumeChapters(d)
        return EntryDetails(
            entry = Entry(id, entryId, title, abs(cover), ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = genres,
            status = null,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        val d = doc(absPath(entryId))
        return volumeChapters(d).also { cachedChapters[entryId] = it }
    }

    // Volume pages: linked from the novel page, each holding one volume's
    // chapters as anchored sections.
    private suspend fun volumeChapters(novelPage: org.jsoup.nodes.Document): List<Episode> {
        val volumes = LinkedHashMap<String, String>() // href -> longest label
        for (a in novelPage.select("a[href^=/book/]")) {
            val href = a.attr("href")
            val text = cleanText(a.text())
            if (href.isEmpty()) continue
            val existing = volumes[href]
            if (existing == null || (text.isNotEmpty() && text.length > existing.length)) volumes[href] = text
        }
        val chapters = coroutineScope {
            volumes.entries.chunked(4).flatMap { batch ->
                batch.map { (href, label) -> async { parseVolume(href, label) } }.awaitAll()
            }.flatten()
        }
        return chapters.mapIndexed { idx, ep -> ep.copy(number = (idx + 1).toFloat()) }
    }

    private suspend fun parseVolume(volHref: String, rawLabel: String): List<Episode> {
        val volTitle = volumeName(volHref, rawLabel)
        val vol = doc(absPath(volHref.trimStart('/')))
        val volPath = volHref.trimStart('/')
        val chapters = mutableListOf<Episode>()

        val tocAnchors = vol.select("nav.toc-view a[href^=#], nav#toc-list a[href^=#]")
        if (tocAnchors.isNotEmpty()) {
            for (a in tocAnchors) {
                val anchor = a.attr("href").removePrefix("#")
                val tocTitle = cleanText(a.text())
                val h2 = vol.getElementById(anchor)
                    ?.selectFirst("h2.chapter-title, h2, h3")?.text()?.trim().orEmpty()
                val chapterName = tocTitle.ifBlank { h2 }.ifBlank { "Page " + anchor.filter { it.isDigit() } }
                chapters += Episode(
                    id = "$volPath#$anchor",
                    name = "$volTitle - $chapterName",
                    number = (chapters.size + 1).toFloat(),
                    dateMillis = null,
                )
            }
        } else {
            for (sec in vol.select("section.chapter")) {
                val anchor = sec.attr("id")
                val h2 = sec.selectFirst("h2.chapter-title, h2, h3")?.text()?.trim().orEmpty()
                if (h2.isEmpty() || anchor.isEmpty()) continue
                chapters += Episode(
                    id = "$volPath#$anchor",
                    name = "$volTitle - $h2",
                    number = (chapters.size + 1).toFloat(),
                    dateMillis = null,
                )
            }
        }
        return chapters
    }

    private fun volumeName(volHref: String, rawLabel: String): String {
        val clean = rawLabel.replace(Regex("Start Reading", RegexOption.IGNORE_CASE), "").trim()
        if (clean.isNotEmpty()) return clean
        val parts = volHref.split('/')
        val slug = parts.last().ifBlank { parts.getOrNull(parts.size - 2).orEmpty() }
        return slug.split('-').joinToString(" ") { word ->
            word.replaceFirstChar { it.uppercase() }
        }
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val path = episodeId.substringBefore('#')
        val anchor = episodeId.substringAfter('#', "")
        val d = doc(absPath(path))
        val section = (if (anchor.isNotBlank()) d.getElementById(anchor) else d.selectFirst("section.chapter"))
            ?: return emptyList()

        val paragraphs = mutableListOf<String>()
        if (anchor.isNotBlank()) {
            val tocAnchors = d.select("nav.toc-view a[href^=#], nav#toc-list a[href^=#]")
                .map { it.attr("href").removePrefix("#") }
            val nextAnchor = tocAnchors.getOrNull(tocAnchors.indexOf(anchor) + 1)
            var step: Element? = section
            while (step != null) {
                val main = (step.selectFirst(".main") ?: step).clone()
                main.select("h2, h3, .chapter-title").remove()
                paragraphs += paragraphsOf(main)
                var next = step.nextElementSibling()
                while (next != null && !(next.tagName() == "section" && next.hasClass("chapter"))) {
                    next = next.nextElementSibling()
                }
                if (next != null && nextAnchor != null && next.attr("id") == nextAnchor) break
                step = next
            }
        } else {
            val main = (section.selectFirst(".main") ?: section).clone()
            main.select("h2, h3, .chapter-title").remove()
            paragraphs += paragraphsOf(main)
        }
        return paragraphs
    }
}

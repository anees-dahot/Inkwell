package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Port of LNReader's HangulPlanet plugin.
 *
 * The site does NOT render its full chapter list as real <a> DOM elements:
 * beyond a small SSR-visible slice, the list lives inside a Next.js RSC
 * ("flight") payload embedded in the page text, which is parsed with regexes.
 */
class HangulPlanetSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "hangulplanet"
    override val name = "HangulPlanet"
    override val baseUrl = "https://hangulplanet.com"
    override val lang = "en"

    // ---- requests ----------------------------------------------------------

    private val captchaTitles = setOf(
        "Bot Verification",
        "You are being redirected...",
        "Un instant...",
        "Just a moment...",
        "Redirecting...",
    )

    /** Search tolerates non-2xx responses; listings treat them as fatal. */
    private suspend fun fetchPage(url: String, search: Boolean = false): Pair<Document, String> =
        withContext(Dispatchers.IO) {
            val text = client.newCall(request(url)).execute().use { r ->
                if (!r.isSuccessful && !search) {
                    throw IllegalStateException("Could not reach site (${r.code}) try to open in webview.")
                }
                r.body?.string().orEmpty()
            }
            val d = Jsoup.parse(text, url)
            if (d.title().trim() in captchaTitles) {
                throw IllegalStateException("Captcha error, please open in webview")
            }
            d to text
        }

    // ---- listing -----------------------------------------------------------

    private fun parseNovelCards(d: Document): List<Entry> =
        d.select("a[href^=\"/novel/\"]").mapNotNull { a ->
            val href = a.attr("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val img = a.selectFirst("img.object-cover") ?: return@mapNotNull null
            val title = img.attr("alt").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Entry(id, href.trimStart('/'), title, abs(img.attr("src")), ContentType.NOVEL)
        }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val (d, _) = fetchPage(absPath("browse") + "?sort=popular")
        return PagedResult(parseNovelCards(d), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val (d, _) = fetchPage(absPath("browse") + "?sort=latest")
        return PagedResult(parseNovelCards(d), false)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val (d, _) = fetchPage(absPath("browse") + "?q=" + URLEncoder.encode(query, "UTF-8"), search = true)
        return PagedResult(parseNovelCards(d), false)
    }

    // ---- chapters (RSC payload + DOM) --------------------------------------

    private val rscHref = Regex("""\\"href\\":\\"([^"\\]*/chapter-(\d+))\\"""")
    private val rscName = Regex("""\\"line-clamp-1 flex-1 text-sm\\",\\"children\\":\\"([^\\]*)\\"""")
    private val rscDate = Regex("""\\"dateTime\\":\\"([^\\]*)\\"""")
    private val domChapterNum = Regex("chapter-(\\d+)$")

    private fun parseChaptersFromText(
        novelPath: String,
        text: String,
        chapters: MutableList<Episode>,
        seen: MutableSet<Int>,
    ) {
        // Primary: escaped-JSON form from the RSC payload.
        for (m in rscHref.findAll(text)) {
            val chapterNumber = m.groupValues[2].toIntOrNull() ?: continue
            if (!seen.add(chapterNumber)) continue
            val window = text.substring(m.range.first, minOf(m.range.first + 800, text.length))
            val chapterName = rscName.find(window)?.groupValues?.get(1)
                ?.takeUnless { it.isBlank() } ?: "Chapter $chapterNumber"
            chapters.add(
                Episode(
                    id = m.groupValues[1].trimStart('/'),
                    name = chapterName,
                    number = chapterNumber.toFloat(),
                    dateMillis = parseDate(rscDate.find(window)?.groupValues?.get(1)),
                ),
            )
        }

        // Secondary: real DOM links.
        val d = Jsoup.parse(text, absPath(novelPath))
        for (el in d.select("#chapters a[href*=\"/chapter-\"]")) {
            val href = el.attr("href").takeIf { it.isNotEmpty() } ?: continue
            val chapterNumber = domChapterNum.find(href)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (!seen.add(chapterNumber)) continue
            val name = el.selectFirst(".line-clamp-1")?.text()?.trim()
                .takeUnless { it.isNullOrEmpty() } ?: "Chapter $chapterNumber"
            chapters.add(
                Episode(
                    id = href.trimStart('/'),
                    name = name,
                    number = chapterNumber.toFloat(),
                    dateMillis = parseDate(el.selectFirst("time")?.attr("datetime")),
                ),
            )
        }
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val (d, text) = fetchPage(absPath(entryId))
        val heading = d.selectFirst("h1")
        val title = heading?.text()?.trim().takeUnless { it.isNullOrEmpty() } ?: "Untitled"
        val author = heading?.nextElementSibling()
            ?.takeIf { it.tagName() == "p" }?.text()?.trim()
        val cover = d.selectFirst("img.object-cover")?.attr("src")
        val summary = d.selectFirst(".prose")?.text()?.trim()
        val statusText = d.selectFirst("span[data-slot=\"badge\"]")?.text()?.trim().orEmpty()
        val status = when {
            statusText.contains("Ongoing") -> "Ongoing"
            statusText.contains("Completed") -> "Completed"
            else -> null
        }
        val genres = d.select("a[href*=\"browse?genre=\"]").eachText().map { it.trim() }

        val chapters = mutableListOf<Episode>()
        val seen = mutableSetOf<Int>()
        parseChaptersFromText(entryId, text, chapters, seen)

        val maxPages = 100 // safety cap so a parsing quirk can't loop forever
        var page = 2
        while (page <= maxPages) {
            val (_, pageText) = fetchPage(absPath(entryId) + "?cpage=$page")
            val before = chapters.size
            parseChaptersFromText(entryId, pageText, chapters, seen)
            if (chapters.size == before) break
            page++
        }
        chapters.sortBy { it.number ?: 0f }

        return EntryDetails(
            entry = Entry(id, entryId, title, abs(cover), ContentType.NOVEL),
            description = summary,
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = genres,
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter text ------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val (d, _) = fetchPage(absPath(episodeId))
        val content = d.selectFirst("article[data-reader-article=\"true\"] .reader-prose")
            ?: return emptyList()
        // Remove the TL/ED credit (annoying for TTS).
        val first = content.selectFirst("p")
        if (first != null && first.text().trim().startsWith("TL/ED")) first.remove()
        return paragraphsOf(content)
    }
}

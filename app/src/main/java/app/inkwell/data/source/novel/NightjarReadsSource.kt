package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/**
 * Nightjar Reads (nightjarreads.com) — a Next.js app-router site; all data is
 * embedded in `self.__next_f.push(...)` flight payloads, parsed with regexes.
 */
class NightjarReadsSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "nightjarreads"
    override val name = "Nightjar Reads"
    override val baseUrl = "https://nightjarreads.com"
    override val lang = "en"

    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- shared regex building blocks --------------------------------------

    /** Matches a JSON string body with escaped quotes. */
    private val jsonStr = """((?:[^"\\]|\\.)*)"""

    private fun entryPattern(slugPattern: String): String =
        ("\"slug\":\"$slugPattern\",\"title\":\"$jsonStr\",\"author\":\"$jsonStr\"" +
            ",\"genres\":(\\[[^\\]]*\\]),\"glyph\":\"[^\"]*\",\"cover\":\\[[^\\]]*\\]," +
            "\"coverUrl\":\"([^\"]*)\",\"chapters\":(\\d+),\"status\":\"([^\"]*)\"")

    private val entryRe = Regex(entryPattern("([a-z0-9-]+)"))
    private val titleRe = Regex(
        "\"\\$\",\"h1\",null,\\{\"className\":\"text-3xl[^\"]*\",\"children\":\"" + jsonStr + "\"\\}"
    )
    private val authorRe = Regex(
        "\"children\":\"Author\"\\},\\[\"\\$\",\"dd\",null,\\{\"className\":\"mt-0\\.5 text-sm font-medium\",\"children\":\"" +
            jsonStr + "\"\\}"
    )
    private val chapterRe = Regex(
        "\"number\":(\\d+(?:\\.\\d+)?),\"title\":\"" + jsonStr +
            "\",\"publishedAt\":\"([^\"]*)\",\"words\":(\\d+),\"tier\":\"([^\"]*)\""
    )
    private val bodyRe = Regex("\"body\":\\[\"([\\s\\S]*?)\"\\],\"translatorNotes\"")
    private val statusChipRe = Regex(
        "\"className\":\"chip\"[\\s\\S]{0,150}?\"children\":\"(Ongoing|Completed|Hiatus|Dropped|On Hiatus|Cancelled)\""
    )
    private val coverRe = Regex("\"src\":\"(https://[^\"]*?supabase[^\"]*?covers[^\"]*?)\"")
    private val metaDescRe = Regex("<meta name=\"description\" content=\"([\\s\\S]*?)\"")
    private val flightRe = Regex("self\\.__next_f\\.push\\(\\[1,\"([\\s\\S]*?)\"\\]\\)\\s*;?\\s*</script>")
    private val chipRe = Regex("\"children\":\\[\"#\",\"([^\"]*)\"\\]")

    private fun escapeRe(s: String): String = buildString {
        for (c in s) {
            if (c in ".*+?^${'$'}{}()|[]\\") append('\\')
            append(c)
        }
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val flight = extractFlightText(fetch(absPath("browse?sort=popular")))
        val items = parseNovelEntries(flight).map { n ->
            Entry(sourceId = id, id = "novel/" + n.slug, title = n.title, coverUrl = abs(n.coverUrl), type = ContentType.NOVEL)
        }
        return PagedResult(items, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val url = absPath("search").toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .build()
        val flight = extractFlightText(fetch(url.toString()))
        val index = parseNovelEntries(flight).associateBy { it.slug }
        val items = parseSearchSlugs(flight, query).mapNotNull { slug -> index[slug] }
            .map { n ->
                Entry(sourceId = id, id = "novel/" + n.slug, title = n.title, coverUrl = abs(n.coverUrl), type = ContentType.NOVEL)
            }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private data class NChapter(
        val numberStr: String,
        val number: Float,
        val title: String,
        val publishedAt: String,
    )

    private data class NDetails(
        val name: String,
        val author: String,
        val genres: List<String>,
        val status: String,
        val cover: String,
        val summary: String,
        val chapters: List<NChapter>,
    )

    override suspend fun details(entryId: String): EntryDetails {
        val slug = entryId.split('/').filter { it.isNotEmpty() }.lastOrNull().orEmpty()
        val html = fetch(absPath(entryId))
        val flight = extractFlightText(html)
        val d = parseNovelPage(html, flight, slug)
        val status = when (d.status) {
            "Ongoing" -> "Ongoing"
            "Completed" -> "Completed"
            "On Hiatus", "Hiatus" -> "On Hiatus"
            "Cancelled", "Dropped" -> "Cancelled"
            else -> null
        }
        val chapters = d.chapters.map { c ->
            Episode(
                id = "novel/$slug/${c.numberStr}",
                name = "Chapter ${c.numberStr}: ${c.title}",
                number = c.number,
                dateMillis = parseDate(c.publishedAt),
            )
        }
        return EntryDetails(
            entry = Entry(sourceId = id, id = entryId, title = d.name, coverUrl = abs(d.cover), type = ContentType.NOVEL),
            description = d.summary.ifBlank { null },
            authors = listOfNotNull(d.author.takeIf { it.isNotBlank() }),
            genres = d.genres,
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val flight = extractFlightText(fetch(absPath(episodeId)))
        val paras = parseChapterParas(flight)
        if (paras != null) return paras
        return listOf(
            "Could not load this chapter.",
            "It may be locked or temporarily unavailable on Nightjar Reads.",
        )
    }

    /** Returns the chapter paragraphs, or null when nothing readable. */
    private fun parseChapterParas(flight: String): List<String>? {
        val m = bodyRe.find(flight) ?: return null
        // Locked flag sits in the record fields right AFTER the body value.
        val from = m.range.last + 1
        if (flight.substring(from, minOf(flight.length, from + 400)).contains("\"locked\":true")) {
            return listOf(
                "This chapter is locked on Nightjar Reads.",
                "It is a premium chapter — unlock it on nightjarreads.com to read it here.",
            )
        }
        val parsed = runCatching {
            parser.parseToJsonElement("[\"" + m.groupValues[1] + "\"]").jsonArray
        }.getOrNull() ?: return null
        val paras = parsed.mapNotNull { el -> (el as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains("Advance chapters at Nightjar Reads") }
        return paras.ifEmpty { null }
    }

    // ---- flight parsing ----------------------------------------------------

    /** Decode every self.__next_f payload into one searchable text blob. */
    private fun extractFlightText(html: String): String {
        check(html.length <= MAX_PAGE_CHARS) { "Page too large to parse safely" }
        val out = StringBuilder()
        for (m in flightRe.findAll(html)) {
            runCatching {
                out.append(parser.parseToJsonElement("\"" + m.groupValues[1] + "\"").jsonPrimitive.content)
            }
        }
        return out.toString()
    }

    /** Decode one flight string field; fall back to the two common escapes. */
    private fun unescapeFlight(s: String): String =
        runCatching { parser.parseToJsonElement("\"" + s + "\"").jsonPrimitive.content }
            .getOrDefault(s.replace("\\\"", "\"").replace("\\'", "'"))

    private data class NCard(
        val slug: String,
        val title: String,
        val author: String,
        val genres: List<String>,
        val coverUrl: String,
        val status: String,
    )

    /** Parse every novel card object embedded in flight data (browse/search). */
    private fun parseNovelEntries(flight: String): List<NCard> {
        val out = mutableListOf<NCard>()
        val seen = mutableSetOf<String>()
        for (m in entryRe.findAll(flight)) {
            if (!seen.add(m.groupValues[1])) continue
            out.add(
                NCard(
                    slug = m.groupValues[1],
                    title = unescapeFlight(m.groupValues[2]),
                    author = unescapeFlight(m.groupValues[3]),
                    genres = parseGenres(m.groupValues[4]),
                    coverUrl = m.groupValues[5],
                    status = m.groupValues[7],
                )
            )
        }
        return out
    }

    private fun parseGenres(raw: String): List<String> =
        runCatching {
            parser.parseToJsonElement(raw).jsonArray.mapNotNull { (it as? JsonPrimitive)?.content }
        }.getOrDefault(emptyList())

    /** Find the novel's own card in the page's embedded novel index. */
    private fun findIndexEntry(flight: String, slug: String): NCard? {
        val re = Regex(entryPattern(escapeRe(slug)))
        val m = re.find(flight) ?: return null
        return NCard(
            slug = slug,
            title = unescapeFlight(m.groupValues[1]),
            author = unescapeFlight(m.groupValues[2]),
            genres = parseGenres(m.groupValues[3]),
            coverUrl = m.groupValues[4],
            status = m.groupValues[6],
        )
    }

    /** Result slugs of a /search page, in order, bounded to the results section. */
    private fun parseSearchSlugs(flight: String, query: String): List<String> {
        val marker = "\"initial\":\"" +
            query.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val start = flight.indexOf(marker)
        var section = flight
        if (start >= 0) {
            val endIdx = flight.indexOf("\"initial\":\"", start + marker.length)
            var end = if (endIdx < 0 || endIdx > start + SEARCH_SECTION_MAX_CHARS) {
                minOf(flight.length, start + SEARCH_SECTION_MAX_CHARS)
            } else endIdx
            section = flight.substring(start, end)
        }
        var limit = Int.MAX_VALUE
        runCatching {
            val cm = Regex("\\[(\\d+),\" \",\"result").find(section.take(2000))
            if (cm != null) limit = cm.groupValues[1].toInt()
        }
        val hrefRe = Regex("\"href\":\"/novel/([a-z0-9-]+)\"")
        val order = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        for (hm in hrefRe.findAll(section)) {
            if (order.size >= limit) break
            if (seen.add(hm.groupValues[1])) order.add(hm.groupValues[1])
        }
        return order
    }

    /** Parse a /novel/<slug> page into its details + full chapter list. */
    private fun parseNovelPage(html: String, flight: String, slug: String): NDetails {
        var name = slug
        var author = ""
        var status = ""
        var cover = ""
        var summary = ""
        val genres = mutableListOf<String>()

        titleRe.find(flight)?.let { name = unescapeFlight(it.groupValues[1]) }
        authorRe.find(flight)?.let { author = unescapeFlight(it.groupValues[1]) }

        val indexEntry = findIndexEntry(flight, slug)
        if (indexEntry != null && indexEntry.genres.isNotEmpty()) {
            genres.addAll(indexEntry.genres)
        } else {
            val chipRow = flight.indexOf("mt-3 flex flex-wrap gap-1.5")
            if (chipRow >= 0) {
                val section = flight.substring(chipRow)
                chipRe.findAll(section)
                    .takeWhile { it.range.first < 6000 }
                    .take(40)
                    .forEach { genres.add(it.groupValues[1]) }
            }
        }
        if (indexEntry != null) {
            if (author.isEmpty() && indexEntry.author.isNotEmpty()) author = indexEntry.author
            if (status.isEmpty() && indexEntry.status.isNotEmpty()) status = indexEntry.status
            if (cover.isEmpty() && indexEntry.coverUrl.isNotEmpty()) cover = indexEntry.coverUrl
            if (name == slug && indexEntry.title.isNotEmpty()) name = indexEntry.title
        }
        statusChipRe.find(flight)?.let { status = it.groupValues[1] }
        coverRe.find(flight)?.let { cover = it.groupValues[1] }
        metaDescRe.find(html)?.let {
            summary = decodeHtmlEntities(it.groupValues[1]).replace(Regex("\\\\n"), "\n")
        }

        val seenCh = mutableSetOf<Float>()
        val chapters = mutableListOf<NChapter>()
        for (chm in chapterRe.findAll(flight)) {
            val num = chm.groupValues[1].toFloatOrNull() ?: continue
            if (!seenCh.add(num)) continue
            chapters.add(
                NChapter(
                    numberStr = chm.groupValues[1],
                    number = num,
                    title = unescapeFlight(chm.groupValues[2]),
                    publishedAt = chm.groupValues[3],
                )
            )
        }
        chapters.sortBy { it.number }
        return NDetails(name, author, genres, status, cover, summary, chapters)
    }

    private fun decodeHtmlEntities(s: String): String {
        val map = mapOf(
            "amp" to "&", "quot" to "\"", "#39" to "'", "apos" to "'", "lt" to "<", "gt" to ">",
        )
        return Regex("&(amp|quot|#39|apos|lt|gt);").replace(s) { m ->
            map[m.groupValues[1]] ?: m.value
        }
    }

    private companion object {
        /** Upper bound on a page this plugin will parse. */
        const val MAX_PAGE_CHARS = 10000000

        /** How far past the search marker the results section can run. */
        const val SEARCH_SECTION_MAX_CHARS = 200000
    }
}

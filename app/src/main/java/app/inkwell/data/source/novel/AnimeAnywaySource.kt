package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import kotlin.math.roundToInt

/**
 * Port of LNReader's Anime Anyway plugin. Every server-rendered page embeds
 * Next.js `__NEXT_DATA__`; the catalogue is the homepage's volume list (one
 * novel per volume) and chapter content is Sanity Portable Text.
 */
class AnimeAnywaySource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "animeanyway"
    override val name = "Anime Anyway"
    override val baseUrl = "https://animeanyway.com"
    override val lang = "en"

    /** Site titles like "Year 3 Vol. 4" don't name the franchise. */
    private val yearVolume = Regex("^Year\\s+\\d+\\s+Vol(?:ume)?\\.?\\s*\\d+", RegexOption.IGNORE_CASE)

    /** Sanity image CDN; project id and dataset are the site's fixed identifiers. */
    private val sanityImageBase = "https://cdn.sanity.io/images/m1xj6lbt/production/"

    // ---- JSON helpers ------------------------------------------------------

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject
    private fun JsonObject?.arr(key: String): JsonArray? = this?.get(key) as? JsonArray
    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content
    private fun JsonObject?.bool(key: String): Boolean? = (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    /** Extracts the Next.js page data every server-rendered page embeds. */
    private suspend fun fetchPageData(path: String): JsonObject? {
        val html = fetch(absPath(path))
        val m = Regex("<script id=\"__NEXT_DATA__\"[^>]*>([\\s\\S]*?)</script>").find(html) ?: return null
        return runCatching { Json.parseToJsonElement(m.groupValues[1]).jsonObject }
            .getOrNull()
            ?.obj("props")
            ?.obj("pageProps")
    }

    private fun displayName(title: String, displayTitle: String?): String {
        val volumeTitle = displayTitle?.trim()
        if (!volumeTitle.isNullOrEmpty() && !volumeTitle.equals(title, ignoreCase = true)) {
            return "$title: $volumeTitle"
        }
        return if (yearVolume.containsMatchIn(title)) "Classroom of the Elite: $title" else title
    }

    /** Supports both the legacy pageProps.vol and current volumeProps.vol. */
    private fun JsonObject?.volume(): JsonObject? = obj("vol") ?: obj("volumeProps")?.obj("vol")

    private fun volumePath(o: JsonObject): String {
        val series = o.str("series")
        val usesRootPath = o.bool("seriesUsesRootPath")
        val volkeyword = o.str("volkeyword").orEmpty()
        return if (!series.isNullOrEmpty() && usesRootPath == false) "$series/$volkeyword" else volkeyword
    }

    // ---- catalogue ---------------------------------------------------------

    private data class CatalogueEntry(val path: String, val name: String, val cover: String?, val releaseDate: String)

    @Volatile
    private var catalogue: List<CatalogueEntry>? = null

    private fun CatalogueEntry.toEntry() = Entry(id, path, name, abs(cover), ContentType.NOVEL)

    private suspend fun getCatalogue(): List<CatalogueEntry> {
        catalogue?.let { return it }
        val home = fetchPageData("")
        val list = home?.arr("allVolumes").orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            CatalogueEntry(
                path = volumePath(o),
                name = displayName(o.str("title").orEmpty(), o.str("displayTitle")),
                cover = o.obj("mainImage")?.obj("asset")?.str("url"),
                releaseDate = o.str("releaseDate").orEmpty(),
            )
        }
        catalogue = list
        return list
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        return PagedResult(getCatalogue().map { it.toEntry() }, false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        // Older catalogue payloads omit release dates; fill only those gaps.
        val ordered = getCatalogue()
            .map { entry ->
                if (entry.releaseDate.isNotEmpty()) entry
                else entry.copy(
                    releaseDate = fetchPageData(entry.path)?.volume()?.str("releaseDate").orEmpty(),
                )
            }
            .sortedByDescending { it.releaseDate }
        return PagedResult(ordered.map { it.toEntry() }, false)
    }

    private fun normalize(value: String) =
        value.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun score(name: String, term: String): Int {
        val a = normalize(name)
        val b = normalize(term)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 1000
        if (a.startsWith(b)) return 900
        if (a.contains(b)) return 800
        val queryTokens = b.split(' ').filter { it.isNotEmpty() }
        val matched = queryTokens.count { a.contains(it) }
        if (matched == queryTokens.size) return 700
        if (matched > 0) return (matched * 500.0 / queryTokens.size).roundToInt()
        return 0
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1 || query.isBlank()) return PagedResult(emptyList(), false)
        val items = getCatalogue()
            .map { it to score(it.name, query) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first.toEntry() }
        return PagedResult(items, false)
    }

    // ---- portable text -----------------------------------------------------

    /** Reconstructs a Sanity CDN URL from a Portable Text image asset ref. */
    private fun resolveImageRef(ref: String?): String? {
        val m = Regex("^image-([a-f0-9]+)-(\\d+x\\d+)-(\\w+)$").find(ref.orEmpty()) ?: return null
        return "$sanityImageBase${m.groupValues[1]}-${m.groupValues[2]}.${m.groupValues[3]}"
    }

    private fun plainText(blocks: JsonArray?): String? {
        if (blocks == null) return null
        val text = blocks.mapNotNull { it as? JsonObject }
            .filter { it.str("_type") == "block" }
            .map { b -> b.arr("children").orEmpty().mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("") }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return text.ifEmpty { null }
    }

    private fun renderPortableText(blocks: JsonArray): String {
        val parts = mutableListOf<String>()
        for (el in blocks) {
            val b = el as? JsonObject ?: continue
            when (b.str("_type")) {
                "horizontalLine" -> parts.add("<hr>")
                "image" -> resolveImageRef(b.obj("asset")?.str("_ref"))
                    ?.let { parts.add("<img src=\"$it\">") }
                "block" -> {
                    val tag = if (b.str("style") == "h2") "h2" else "p"
                    val inner = b.arr("children").orEmpty().mapNotNull { s ->
                        val span = s as? JsonObject ?: return@mapNotNull null
                        var text = span.str("text").orEmpty()
                        val marks = span.arr("marks").orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.content }
                        if ("strong" in marks) text = "<strong>$text</strong>"
                        if ("em" in marks) text = "<em>$text</em>"
                        text
                    }.joinToString("")
                    if (inner.isNotBlank()) parts.add("<$tag>$inner</$tag>")
                }
            }
        }
        return parts.joinToString("\n")
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val vol = fetchPageData(entryId).volume()
        if (vol == null) {
            return EntryDetails(
                entry = Entry(id, entryId, entryId, null, ContentType.NOVEL),
                description = "This novel is not available on Anime Anyway.",
                authors = emptyList(),
                genres = emptyList(),
                status = null,
            )
        }
        val title = displayName(vol.str("title").orEmpty(), vol.str("displayTitle"))
        val chapters = vol.arr("chapters").orEmpty().mapIndexedNotNull { index, el ->
            val ch = el as? JsonObject ?: return@mapIndexedNotNull null
            val keyword = ch.str("chkeyword") ?: return@mapIndexedNotNull null
            Episode(
                id = "$entryId/$keyword",
                name = ch.str("title")?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}",
                number = (index + 1).toFloat(),
                dateMillis = null,
            )
        }
        return EntryDetails(
            entry = Entry(id, entryId, title, abs(vol.obj("mainImage")?.obj("asset")?.str("url")), ContentType.NOVEL),
            description = plainText(vol.arr("synopsis")),
            authors = emptyList(),
            genres = emptyList(),
            status = "Ongoing",
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter text ------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val content = fetchPageData(episodeId)?.obj("chapter")?.arr("content")
            ?: return listOf("This chapter is not available on Anime Anyway.")
        val html = renderPortableText(content)
        if (html.isBlank()) return listOf("This chapter appears to be empty.")
        return paragraphsOf(Jsoup.parseBodyFragment(html).body())
    }
}

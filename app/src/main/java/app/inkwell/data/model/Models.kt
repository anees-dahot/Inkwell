package app.inkwell.data.model

enum class ContentType { MANGA, VIDEO, NOVEL }

data class Entry(
    val sourceId: String,
    val id: String,
    val title: String,
    val coverUrl: String?,
    val type: ContentType,
    /** Newest chapter number shown on listing pages, when the source lists it. */
    val latest: Float? = null,
    /** Flagged mature by the source itself (e.g. MangaDex erotica / pornographic rating). */
    val adult: Boolean = false,
)

private val ADULT_GENRES = setOf("adult", "mature", "smut", "hentai", "erotica", "pornographic", "18+", "r-18", "r18", "sexual violence", "yaoi(bl) 18+")

/** Genre names that mark a title as 18+. */
fun isAdultGenre(g: String): Boolean = g.trim().lowercase() in ADULT_GENRES

/** Sources that only carry 18+ titles. */
val Source.isAdult: Boolean get() = name.contains("18+")

private val CHAPTER_NUM = Regex("""(?:chapter|chap|ch|episode|ep)\.?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
private val ANY_NUM = Regex("""(\d+(?:\.\d+)?)""")

/** "Chapter 123.5", "Ch. 7 - Title", "123" -> 123.5 / 7 / 123; null when there's no number. */
fun chapterNumber(text: String?): Float? {
    val t = text?.trim().orEmpty()
    if (t.isEmpty()) return null
    return (CHAPTER_NUM.find(t) ?: ANY_NUM.find(t))?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it < 100_000 }
}

data class EntryDetails(
    val entry: Entry,
    val description: String?,
    val authors: List<String>,
    val genres: List<String>,
    val status: String?,
    /** Official licensed reading page, if the source knows one. */
    val officialUrl: String? = null,
    /** Other names of the series (English first), used to find it on other sources. */
    val altTitles: List<String> = emptyList(),
)

/** A chapter or episode. */
data class Episode(
    val id: String,
    val name: String,
    val number: Float?,
    val dateMillis: Long?,
    val scanlator: String? = null,
)

data class Page(val index: Int, val imageUrl: String)

data class PagedResult<T>(val items: List<T>, val hasNext: Boolean)

interface Source {
    val id: String
    val name: String
    val type: ContentType
    val lang: String
    suspend fun popular(page: Int): PagedResult<Entry>
    suspend fun latest(page: Int): PagedResult<Entry>
    suspend fun search(query: String, page: Int): PagedResult<Entry>
    suspend fun details(entryId: String): EntryDetails
    suspend fun episodes(entryId: String): List<Episode>
}

interface MangaSource : Source {
    suspend fun pages(episodeId: String): List<Page>
}

/** Returns the YouTube video id for an episode. */
interface VideoSource : Source {
    suspend fun videoId(episodeId: String): String
}

/**
 * A text-novel source. [chapterText] returns the chapter content as a list of
 * paragraphs (HTML stripped). An empty list means "no readable content".
 */
interface NovelSource : Source {
    suspend fun chapterText(episodeId: String): List<String>
}

/** Marker: the source lists titles but can't serve chapters; title pages send readers to other sources. */
interface CatalogOnly

/** Optional: sources that can list every title carrying a genre / tag. */
interface TagBrowsable {
    suspend fun byTag(tag: String, page: Int): PagedResult<Entry>
}

/** "Slice of Life" -> "slice-of-life" (WordPress genre slug). */
fun tagSlug(tag: String): String =
    tag.trim().lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), "-").trim('-')

/** Drops blank and repeated page images (lazy-load + noscript copies, repeated ad slots) and reindexes. */
fun List<Page>.cleanPages(): List<Page> =
    filter { it.imageUrl.isNotBlank() }
        .distinctBy { it.imageUrl.substringBefore('#') }
        .mapIndexed { i, p -> if (p.index == i) p else p.copy(index = i) }

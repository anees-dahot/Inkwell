package app.inkwell.data.model

enum class ContentType { MANGA, VIDEO, NOVEL }

data class Entry(
    val sourceId: String,
    val id: String,
    val title: String,
    val coverUrl: String?,
    val type: ContentType,
)

data class EntryDetails(
    val entry: Entry,
    val description: String?,
    val authors: List<String>,
    val genres: List<String>,
    val status: String?,
    /** Official licensed reading page, if the source knows one. */
    val officialUrl: String? = null,
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

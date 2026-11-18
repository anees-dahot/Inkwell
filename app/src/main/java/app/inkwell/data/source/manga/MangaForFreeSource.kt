package app.inkwell.data.source.manga

import okhttp3.OkHttpClient

/** Adult (18+) manhwa, Madara theme: mangaforfree.com. */
class MangaForFreeSource(client: OkHttpClient) : AdultMadaraSource(client) {
    override val baseUrl = "https://mangaforfree.com"
    override val id = "mangaforfree"
    override val name = "MangaForFree (18+)"
    override val seriesPath = "manga"
}

package app.inkwell.data.source.manga

import okhttp3.OkHttpClient

/** Adult (18+) manhwa, Madara theme: mangadistrict.com. */
class MangaDistrictSource(client: OkHttpClient) : AdultMadaraSource(client) {
    override val baseUrl = "https://mangadistrict.com"
    override val id = "mangadistrict"
    override val name = "MangaDistrict (18+)"
    override val seriesPath = "series"
    override val genreSubString = "publication-genre"
}

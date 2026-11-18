package app.inkwell.data.source.manga

import okhttp3.OkHttpClient

/** Adult (18+) manhwa, Madara theme: manhwaclub.net. */
class ManhwaClubSource(client: OkHttpClient) : AdultMadaraSource(client) {
    override val baseUrl = "https://manhwaclub.net"
    override val id = "manhwaclub"
    override val name = "ManhwaClub (18+)"
    override val seriesPath = "manga"
}

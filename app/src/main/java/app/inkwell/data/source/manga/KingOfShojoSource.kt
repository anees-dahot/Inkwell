package app.inkwell.data.source.manga

import okhttp3.OkHttpClient

/** kingofshojo.com — MangaThemesia theme. */
class KingOfShojoSource(client: OkHttpClient) : MangaThemesiaSource(client) {
    override val baseUrl = "https://kingofshojo.com"
    override val id = "kingofshojo"
    override val name = "King of Shojo"
}

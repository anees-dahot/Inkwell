package app.inkwell.data.source.manga

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

/** Madara-theme site mangaread.org (Mihon "MangaRead" extension equivalent). */
class MangaReadSource(client: OkHttpClient) : MadaraSource(client) {
    override val baseUrl = "https://www.mangaread.org"
    override val id = "mangaread"
    override val name = "MangaRead"

    override val listItemSelector = "div.page-item-detail.manga"
    override val dateFormats = listOf("dd.MM.yyyy", "MMMM d, yyyy")
    override val pageImageSelector = "div.page-break img, .reading-content img.wp-manga-chapter-img"

    // Search for this site uses ?paged=N rather than /page/N/.
    override fun searchUrl(query: String, page: Int): String =
        "$baseUrl/".toHttpUrl().newBuilder()
            .addQueryParameter("s", query.trim())
            .addQueryParameter("post_type", "wp-manga")
            .addQueryParameter("paged", page.toString()).build().toString()
}

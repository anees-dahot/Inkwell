package app.inkwell.data.source

import app.inkwell.data.model.ContentType
import app.inkwell.data.model.MangaSource
import okhttp3.OkHttpClient

/** Base for scraped manga sites (Mihon/Tachiyomi extension ports). */
abstract class ScraperMangaSource(client: OkHttpClient) : ScraperSource(client), MangaSource {
    final override val type: ContentType get() = ContentType.MANGA

    /** Register an image url's host so the image loader sends this site's Referer. */
    protected fun ref(url: String?): String? = ImageReferers.register(url, "$baseUrl/")
}

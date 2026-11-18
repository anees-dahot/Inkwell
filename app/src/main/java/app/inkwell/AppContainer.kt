package app.inkwell

import android.content.Context
import app.inkwell.data.Repository
import app.inkwell.data.Settings
import app.inkwell.data.db.InkDatabase
import app.inkwell.data.source.*
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AppContainer(context: Context) {
    val settings = Settings(context)
    val db = InkDatabase.create(context)

    init { ScraperSource.globalUserAgent = Cloudflare.webViewUserAgent(context) }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .addInterceptor(ExplicitCookies.stash)
        .addNetworkInterceptor(ExplicitCookies.merge)
        .addInterceptor(CloudflareInterceptor(context))
        .addInterceptor(RateLimitInterceptor("api.mangadex.org", 5))
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val youtube = YouTubeDonghuaSource(context, httpClient, db.customChannels())
    val registry = SourceRegistry(
        listOf(
            MangaDexSource(httpClient) { settings.lang.value },
            youtube,
        ) + app.inkwell.data.source.novel.novelSources(httpClient) +
            app.inkwell.data.source.manga.mangaSources(httpClient)
    )
    val repo = Repository(registry, db, settings)
}

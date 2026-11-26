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

    init {
        ScraperSource.globalUserAgent = Cloudflare.webViewUserAgent(context)
        SourceHealth.init(context)
        ImageReferers.init(context)
        Net.init(context)
    }

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .addInterceptor(ExplicitCookies.stash)
        .addNetworkInterceptor(ExplicitCookies.merge)
        .addInterceptor(CloudflareInterceptor(context))
        .addInterceptor(app.inkwell.data.source.manga.MangaPlusImageInterceptor)
        .addInterceptor(RateLimitInterceptor("api.mangadex.org", 5))
        .addNetworkInterceptor(NetMeter)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Shared by Coil, the webtoon tiler and chapter downloads. */
    val imageClient: OkHttpClient = Net.imageClient(httpClient.connectionPool, httpClient.cookieJar)

    val updater = app.inkwell.data.AppUpdater(context.applicationContext, httpClient)

    val registry = SourceRegistry(
        listOf(
            MangaDexSource(httpClient) { settings.lang.value },
        ) + app.inkwell.data.source.novel.novelSources(httpClient) +
            app.inkwell.data.source.manga.mangaSources(httpClient)
    )
    val repo = Repository(registry, db, settings, context.applicationContext, httpClient, imageClient)
    val tracker = app.inkwell.data.ReadingTracker(db, settings, context.applicationContext)
}

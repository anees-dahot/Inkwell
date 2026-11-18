package app.inkwell

import android.app.Application
import android.os.Build
import org.conscrypt.Conscrypt
import java.security.Security
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import okhttp3.OkHttpClient

class InkwellApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // MangaDex is TLS 1.3-only; Android < 10 lacks it natively -> use bundled Conscrypt
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Security.insertProviderAt(Conscrypt.newProvider(), 1)
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                val b = req.newBuilder().header("User-Agent", "Inkwell/0.1 (Android)")
                if (req.header("Referer") == null) {
                    app.inkwell.data.source.ImageReferers.forHost(req.url.host)?.let {
                        b.header("Referer", it).header("User-Agent", app.inkwell.data.source.MangaDexSource.UA)
                    }
                }
                chain.proceed(b.build())
            }.build()
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .crossfade(true)
            .build()
    }
}

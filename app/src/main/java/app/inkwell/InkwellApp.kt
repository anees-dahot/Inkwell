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
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath

class InkwellApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // MangaDex is TLS 1.3-only; Android < 10 lacks it natively -> use bundled Conscrypt
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) Security.insertProviderAt(Conscrypt.newProvider(), 1)
        container = AppContainer(this)
        app.inkwell.work.Notifier.ensureChannels(this)
        app.inkwell.work.Scheduler.syncAll(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = container.imageClient
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { client })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache {
                DiskCache.Builder().directory(context.cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(512L * 1024 * 1024).build()
            }
            .crossfade(true)
            .build()
    }
}

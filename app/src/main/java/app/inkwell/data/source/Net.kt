package app.inkwell.data.source

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Network helpers shared by browsing, the reader and downloads, tuned so everything still
 * works (slowly) on a poor connection instead of failing fast.
 */
object Net {
    private var cm: ConnectivityManager? = null

    fun init(context: Context) {
        cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    }

    /** Estimated downstream bandwidth in kbps, or null when unknown. */
    fun bandwidthKbps(): Int? = runCatching {
        val c = cm ?: return null
        c.getNetworkCapabilities(c.activeNetwork)?.linkDownstreamBandwidthKbps?.takeIf { it > 0 }
    }.getOrNull()

    // Measured throughput (EMA, kbps) of recent responses. The OS estimate is often wrong
    // (throttled plans, congested Wi-Fi), so what we actually observe wins.
    @Volatile private var emaKbps = -1.0
    @Volatile private var lastSample = 0L
    @Volatile private var slowUntil = 0L

    fun record(bytes: Long, ms: Double) {
        if (bytes < 16_000 || ms <= 0) return
        val kbps = bytes * 8 / ms
        val now = System.currentTimeMillis()
        emaKbps = if (emaKbps < 0 || now - lastSample > 120_000) kbps else emaKbps * 0.7 + kbps * 0.3
        lastSample = now
        tune()
    }

    /** Called when most of a batch timed out: assume a slow link for a while. */
    fun markSlow() { slowUntil = System.currentTimeMillis() + 5 * 60_000; tune() }

    private var imageDispatcher: Dispatcher? = null
    /** Fewer parallel image downloads on a slow link so the visible ones finish first. */
    private fun tune() {
        val d = imageDispatcher ?: return
        val want = if (slow) 3 else 12
        if (d.maxRequests != want) { d.maxRequests = want; d.maxRequestsPerHost = if (want == 3) 2 else 4 }
    }

    /** True on a slow link (2G/3G, weak signal, throttled data). */
    val slow: Boolean get() {
        val now = System.currentTimeMillis()
        if (now < slowUntil) return true
        if (emaKbps in 0.0..800.0 && now - lastSample < 120_000) return true
        return (bandwidthKbps() ?: Int.MAX_VALUE) < 1_500
    }

    val online: Boolean get() = runCatching {
        val c = cm ?: return true
        c.getNetworkCapabilities(c.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(true)

    /**
     * Client for page/cover images: browser UA, persisted per-host Referer, generous timeouts,
     * automatic retries and a capped number of parallel downloads so a slow link isn't flooded.
     */
    fun imageClient(pool: ConnectionPool, cookies: okhttp3.CookieJar): OkHttpClient =
        OkHttpClient.Builder()
            .connectionPool(pool)
            .cookieJar(cookies)
            .dispatcher(Dispatcher().apply { maxRequests = 12; maxRequestsPerHost = 4 }.also { imageDispatcher = it })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(ImageHeaders)
            .addInterceptor(RetryInterceptor(3))
            .addInterceptor(app.inkwell.data.source.manga.MangaPlusImageInterceptor)
            .addNetworkInterceptor(NetMeter)
            .build()
}

/** Measures body throughput of real network responses and feeds [Net.record]. */
object NetMeter : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val t0 = System.nanoTime()
        val r = chain.proceed(chain.request())
        val body = r.body ?: return r
        val counting = object : okio.ForwardingSource(body.source()) {
            var bytes = 0L
            var done = false
            fun finish() { if (!done) { done = true; Net.record(bytes, (System.nanoTime() - t0) / 1e6) } }
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0) bytes += n else if (n == -1L) finish()
                return n
            }
            override fun close() { finish(); super.close() }
        }
        return r.newBuilder().body(counting.buffer().asResponseBody(body.contentType(), body.contentLength())).build()
    }
}

/** Adds UA + Referer to image requests; on a hotlink 403 retries once with the image host as Referer. */
object ImageHeaders : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val host = req.url.host
        val b = req.newBuilder()
        if (req.header("User-Agent") == null) {
            b.header("User-Agent", if (host.contains("mangadex")) MangaDexSource.UA else ScraperSource.globalUserAgent)
        }
        val registered = if (req.header("Referer") == null) ImageReferers.forHost(host) else null
        if (registered != null) b.header("Referer", registered)
        b.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        val built = b.build()
        val resp = chain.proceed(built)
        if ((resp.code == 403 || resp.code == 401) && built.header("Referer") == null) {
            resp.close()
            return chain.proceed(built.newBuilder().header("Referer", "${req.url.scheme}://${rootDomain(host)}/").build())
        }
        return resp
    }

    private fun rootDomain(host: String): String {
        val parts = host.split('.')
        return if (parts.size <= 2) host else parts.takeLast(2).joinToString(".")
    }
}

/** Retries transient failures (timeouts, resets, 5xx, 429) with backoff. */
class RetryInterceptor(private val tries: Int) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var last: IOException? = null
        for (attempt in 0 until tries) {
            if (chain.call().isCanceled()) break
            try {
                val r = chain.proceed(chain.request())
                if ((r.code in 500..599 || r.code == 429) && attempt < tries - 1) {
                    r.close(); Thread.sleep(800L * (attempt + 1)); continue
                }
                return r
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                last = e
                if (attempt < tries - 1) runCatching { Thread.sleep(800L * (attempt + 1)) }
            }
        }
        throw last ?: IOException("Canceled")
    }
}

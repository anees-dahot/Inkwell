package app.inkwell.data.source

import okhttp3.Interceptor
import okhttp3.Response

/** Simple token bucket: at most [perSecond] requests/second for [host]. */
class RateLimitInterceptor(private val host: String, perSecond: Int = 5) : Interceptor {
    private val intervalNs = 1_000_000_000L / perSecond
    private var nextSlot = 0L
    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.host == host) {
            val wait: Long
            synchronized(lock) {
                val now = System.nanoTime()
                val slot = maxOf(now, nextSlot)
                nextSlot = slot + intervalNs
                wait = slot - now
            }
            if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
        return chain.proceed(chain.request())
    }
}

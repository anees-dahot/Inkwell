package app.inkwell.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

/** Thrown when a site answers with a Cloudflare challenge the app could not solve silently. */
class CloudflareException(val url: String) :
    IllegalStateException("Cloudflare check blocked this request. Tap Open in WebView, finish the check, then retry.")

object Cloudflare {
    private val markers = listOf("Just a moment", "cf-chl", "_cf_chl_opt", "challenge-platform")

    fun isChallenge(code: Int, serverHeader: String?, mitigated: String?, body: String?): Boolean {
        if (code != 403 && code != 503) return false
        if (mitigated.equals("challenge", true)) return true
        val cfServer = serverHeader?.contains("cloudflare", true) == true
        val cfBody = body != null && markers.any { body.contains(it, true) }
        return cfBody || (cfServer && body.isNullOrBlank())
    }

    /** WebView default UA minus the "; wv" marker (some sites reject it); same value must be used everywhere. */
    fun webViewUserAgent(context: Context): String =
        runCatching { WebSettings.getDefaultUserAgent(context).replace("; wv", "") }
            .getOrDefault(MangaDexSource.UA)
}

/** OkHttp CookieJar sharing cookies with android.webkit.CookieManager (so cf_clearance from a WebView is reused). */
class WebViewCookieJar : CookieJar {
    private val cm get() = runCatching { CookieManager.getInstance() }.getOrNull()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = cm ?: return
        cookies.forEach { m.setCookie(url.toString(), it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = cm?.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }
}

/**
 * Mihon-style: on a Cloudflare challenge, load the URL in a hidden WebView (up to ~15s) until the
 * challenge clears, then retry the request once. Never blocks the main thread.
 */
class CloudflareInterceptor(context: Context) : Interceptor {
    private val appContext = context.applicationContext
    private val failedAt = ConcurrentHashMap<String, Long>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val resp = chain.proceed(req)
        if (resp.code != 403 && resp.code != 503) return resp
        val snippet = runCatching { resp.peekBody(64 * 1024).string() }.getOrNull()
        if (!Cloudflare.isChallenge(resp.code, resp.header("Server"), resp.header("cf-mitigated"), snippet)) return resp
        if (Looper.myLooper() == Looper.getMainLooper()) return resp
        val host = req.url.host
        if (System.currentTimeMillis() - (failedAt[host] ?: 0L) < 60_000L) return resp
        resp.close()
        val ok = solve(req.url)
        if (!ok) failedAt[host] = System.currentTimeMillis() else failedAt.remove(host)
        return chain.proceed(req)
    }

    private fun clearance(url: HttpUrl): String? =
        runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()
            ?.split(";")?.map { it.trim() }?.firstOrNull { it.startsWith("cf_clearance=") }

    @SuppressLint("SetJavaScriptEnabled")
    private fun solve(url: HttpUrl): Boolean {
        val handler = Handler(Looper.getMainLooper())
        val before = clearance(url)
        val cleared = java.util.concurrent.atomic.AtomicBoolean(false)
        var webView: WebView? = null
        handler.post {
            runCatching {
                val w = WebView(appContext)
                webView = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.userAgentString = ScraperSource.globalUserAgent
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, u: String?) {
                        val t = view.title.orEmpty()
                        if (!t.contains("Just a moment", true) && !t.contains("Attention Required", true) && t.isNotBlank()) cleared.set(true)
                    }
                }
                w.loadUrl(url.toString())
            }
        }
        val deadline = System.currentTimeMillis() + 15_000L
        var ok = false
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(500)
            val now = clearance(url)
            if (cleared.get() || (now != null && now != before)) { ok = true; break }
        }
        handler.post {
            runCatching { webView?.stopLoading(); webView?.destroy() }
            runCatching { CookieManager.getInstance().flush() }
        }
        if (ok) Thread.sleep(300)
        return ok
    }
}

/**
 * OkHttp's BridgeInterceptor replaces any explicit `Cookie` header with the jar's cookies.
 * Sources that set their own cookies (session/consent) would lose them, so stash the
 * explicit header before the bridge ([stash]) and merge it back after ([merge]).
 */
object ExplicitCookies {
    private const val HOLD = "X-Inkwell-Cookie"

    val stash = Interceptor { chain ->
        val req = chain.request()
        val c = req.header("Cookie")
        chain.proceed(if (c == null) req else req.newBuilder().removeHeader("Cookie").header(HOLD, c).build())
    }

    val merge = Interceptor { chain ->
        val req = chain.request()
        val held = req.header(HOLD) ?: return@Interceptor chain.proceed(req)
        val jar = req.header("Cookie")
        val merged = if (jar.isNullOrBlank()) held else {
            val explicitNames = held.split(';').map { it.substringBefore('=').trim() }.toSet()
            (listOf(held) + jar.split(';').map { it.trim() }
                .filter { it.isNotEmpty() && it.substringBefore('=').trim() !in explicitNames }).joinToString("; ")
        }
        chain.proceed(req.newBuilder().removeHeader(HOLD).header("Cookie", merged).build())
    }
}

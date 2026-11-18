package app.inkwell.data.source

import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.NovelSource
import app.inkwell.data.model.PagedResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Shared plumbing for HTML/JSON scraping sources — the Kotlin counterpart of
 * LNReader's `fetchApi` + `cheerio` helpers. One instance per site.
 */
abstract class ScraperSource(protected val client: OkHttpClient) {
    abstract val baseUrl: String
    abstract val id: String
    abstract val name: String

    /** Extra headers sent with every request (Referer, Cookie, ...). */
    protected open val headers: Map<String, String> = emptyMap()

    /** UA for this site; some sites reject unknown UAs. */
    protected open val userAgent: String get() = globalUserAgent

    protected val json = Json { ignoreUnknownKeys = true; isLenient = true }

    // ---- requests ----------------------------------------------------------

    protected open fun request(url: String): Request {
        val b = Request.Builder().url(url).header("User-Agent", userAgent)
        headers.forEach { (k, v) -> b.header(k, v) }
        return b.build()
    }

    protected suspend fun Call.bodyString(): String = withContext(Dispatchers.IO) {
        execute().use { r ->
            if (!r.isSuccessful) {
                if (r.code == 403 || r.code == 503) {
                    val snippet = runCatching { r.body?.string() }.getOrNull()
                    if (Cloudflare.isChallenge(r.code, r.header("Server"), r.header("cf-mitigated"), snippet)) {
                        throw CloudflareException(r.request.url.toString())
                    }
                }
                error("$name HTTP ${r.code}")
            }
            r.body?.string().orEmpty()
        }
    }

    protected suspend fun fetch(url: String): String =
        client.newCall(request(url)).bodyString()

    protected suspend fun doc(url: String): Document = withContext(Dispatchers.IO) {
        Jsoup.parse(fetch(url), url)
    }

    protected suspend fun doc(url: okhttp3.HttpUrl): Document = doc(url.toString())

    protected suspend fun json(url: String): JsonElement = withContext(Dispatchers.IO) {
        json.parseToJsonElement(fetch(url))
    }

    protected suspend fun json(url: okhttp3.HttpUrl): JsonElement = json(url.toString())

    protected suspend fun jsonPost(url: String, body: String, contentType: String = "application/json"): JsonElement =
        withContext(Dispatchers.IO) {
            val req = request(url).newBuilder()
                .post(body.toRequestBody(contentType.toMediaType()))
                .build()
            json.parseToJsonElement(client.newCall(req).bodyString())
        }

    protected suspend fun formPost(url: String, form: Map<String, String>): Document =
        withContext(Dispatchers.IO) {
            val fb = FormBody.Builder()
            form.forEach { (k, v) -> fb.add(k, v) }
            val req = request(url).newBuilder().post(fb.build()).build()
            Jsoup.parse(client.newCall(req).bodyString(), url)
        }

    // ---- helpers -----------------------------------------------------------

    protected fun abs(href: String?): String? {
        if (href.isNullOrBlank()) return null
        return href.toHttpUrlOrNull()?.toString()
            ?: runCatching { baseUrl.toHttpUrlOrNull()!!.resolve(href).toString() }.getOrNull()
    }

    /** Site-relative path ("novel/x/c-1") -> absolute url, LNReader "path" style. */
    protected fun absPath(path: String): String =
        if (path.startsWith("http")) path else baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    protected fun parseDate(raw: String?, vararg formats: String): Long? {
        val s = raw?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        for (f in formats) runCatching {
            return java.time.LocalDateTime.parse(s, java.time.format.DateTimeFormatter.ofPattern(f))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
        runCatching { return java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() }
        runCatching { return java.time.Instant.parse(s).toEpochMilli() }
        // "x ago" style
        val ago = Regex("""(\d+)\s*(second|minute|hour|day|week|month|year)""", RegexOption.IGNORE_CASE).findAll(s).toList()
        if (ago.isNotEmpty()) {
            var t = java.time.Instant.now()
            val unit = ago.last().groupValues[2].lowercase()
            val n = ago.last().groupValues[1].toLong()
            t = when {
                unit.startsWith("second") -> t.minusSeconds(n)
                unit.startsWith("minute") -> t.minus(java.time.Duration.ofMinutes(n))
                unit.startsWith("hour") -> t.minus(java.time.Duration.ofHours(n))
                unit.startsWith("day") -> t.minus(java.time.Duration.ofDays(n))
                unit.startsWith("week") -> t.minus(java.time.Duration.ofDays(n * 7))
                unit.startsWith("month") -> t.minus(java.time.Duration.ofDays(n * 30))
                else -> t.minus(java.time.Duration.ofDays(n * 365))
            }
            return t.toEpochMilli()
        }
        return null
    }

    companion object {
        val UA = MangaDexSource.UA

        /** UA sent by scraper sources; set at startup to the WebView UA so cf_clearance stays valid. */
        @Volatile var globalUserAgent: String = MangaDexSource.UA
    }
}

/**
 * Base for text-novel sites. Subclasses implement the five operations; text
 * helpers below keep chapter extraction consistent across ports.
 */
abstract class ScraperNovelSource(client: OkHttpClient) : ScraperSource(client), NovelSource {
    final override val type: ContentType get() = ContentType.NOVEL
}

/** Extract readable paragraphs from a chapter element (drops ads/scripts). */
fun paragraphsOf(el: Element?): List<String> {
    el ?: return emptyList()
    el.select("script, style, ins, iframe, noscript, .adsbygoogle").remove()
    val ps = el.select("p")
    val raw = if (ps.size >= 2) ps.map { it.text() } else el.wholeText().split('\n')
    return raw.map { it.replace('\u00a0', ' ').trim() }
        .filter { it.isNotEmpty() && !it.startsWith("(function") && !it.startsWith("window.") && !it.startsWith("var ") && !it.startsWith("const ") }
}

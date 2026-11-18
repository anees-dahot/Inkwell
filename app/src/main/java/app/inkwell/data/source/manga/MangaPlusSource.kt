package app.inkwell.data.source.manga

import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ImageReferers
import app.inkwell.data.source.ScraperMangaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.UUID

/**
 * Official Shueisha MANGA Plus (English). Port of Keiyoushi's MangaPlus extension.
 * The API only answers protobuf (format=json is 403), so a tiny wire-format reader is used.
 * Page images are XOR-encrypted: the page url carries "#<hexkey>:<viewToken>" and
 * [MangaPlusImageInterceptor] (added to the image/http clients) decrypts the response.
 */
class MangaPlusSource(client: OkHttpClient) : ScraperMangaSource(client) {
    override val baseUrl = "https://mangaplus.shueisha.co.jp"
    override val id = "mangaplus"
    override val name = "MANGA Plus"
    override val lang = "en"

    private val api = "https://jumpg-webapi.tokyo-cdn.com/api"
    private val session = UUID.randomUUID().toString()

    override val headers = mapOf(
        "Origin" to baseUrl,
        "Referer" to "$baseUrl/",
    )

    override fun request(url: String): Request =
        super.request(url).newBuilder().header("SESSION-TOKEN", session).build()

    // ---- minimal protobuf reader ------------------------------------------

    private class Msg(private val b: ByteArray) {
        private val fields = ArrayList<Triple<Int, Long, ByteArray?>>() // field, varint, bytes

        init {
            var i = 0
            fun varint(): Long {
                var r = 0L; var s = 0
                while (true) {
                    val c = b[i++].toInt() and 0xff
                    r = r or ((c and 0x7f).toLong() shl s); s += 7
                    if (c < 0x80) return r
                }
            }
            while (i < b.size) {
                val k = varint().toInt(); val f = k ushr 3
                when (k and 7) {
                    0 -> fields.add(Triple(f, varint(), null))
                    1 -> { fields.add(Triple(f, 0, b.copyOfRange(i, i + 8))); i += 8 }
                    2 -> { val l = varint().toInt(); fields.add(Triple(f, 0, b.copyOfRange(i, i + l))); i += l }
                    5 -> { fields.add(Triple(f, 0, b.copyOfRange(i, i + 4))); i += 4 }
                    else -> error("bad wire type")
                }
            }
        }

        fun msg(f: Int): Msg? = fields.firstOrNull { it.first == f && it.third != null }?.third?.let { Msg(it) }
        fun msgs(f: Int): List<Msg> = fields.filter { it.first == f && it.third != null }.map { Msg(it.third!!) }
        fun str(f: Int): String? = fields.firstOrNull { it.first == f && it.third != null }?.third?.toString(Charsets.UTF_8)
        fun int(f: Int): Long? = fields.firstOrNull { it.first == f && it.third == null }?.second
    }

    private data class Title(val id: Int, val name: String, val author: String?, val cover: String?, val lang: Int)

    private fun title(m: Msg) = Title(
        (m.int(1) ?: 0).toInt(), m.str(2).orEmpty(), m.str(3), m.str(4), (m.int(7) ?: 0).toInt(),
    )

    private suspend fun call(path: String): Msg = withContext(Dispatchers.IO) {
        val bytes = client.newCall(request("$api/$path")).execute().use { r ->
            check(r.isSuccessful) { "$name HTTP ${r.code}" }
            r.body!!.bytes()
        }
        val root = Msg(bytes)
        root.msg(1) ?: error(root.msg(2)?.msg(2)?.str(2)?.takeIf { it.isNotEmpty() } ?: "$name: request failed")
    }

    private fun List<Title>.en() = filter { it.id != 0 && it.lang == 0 }.distinctBy { it.id }

    private fun entry(t: Title) = Entry(id, t.id.toString(), t.name, cover(t.cover), type)
    private fun cover(url: String?) = ImageReferers.register(url, "$baseUrl/")
    private fun page(items: List<Title>) = PagedResult(items.en().map(::entry), false)

    // ---- browse ------------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val s = call("title_list/rankingV2?lang=eng&type=hottest&clang=eng")
        val titles = s.msg(37)?.msgs(3).orEmpty().flatMap { it.msgs(2).map(::title) }
        return page(titles)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val view = call("web/web_homeV4?lang=eng&clang=eng").msg(38)
        val updated = view?.msgs(2).orEmpty().flatMap { it.msgs(2) } + listOfNotNull(view?.msg(7)?.msg(2))
        val titles = updated.sortedByDescending { it.int(6) ?: 0 }
            .flatMap { u -> u.msgs(3).mapNotNull { it.msg(1)?.let(::title) } }
        return page(titles)
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val q = query.trim()
        if (q.isEmpty()) return popular(page)
        val titles = call("title_list/allV2").msg(25)?.msgs(1).orEmpty().flatMap { it.msgs(2).map(::title) }
        return page(titles.filter { it.name.contains(q, true) || it.author.orEmpty().contains(q, true) })
    }

    // ---- detail ------------------------------------------------------------

    private suspend fun detail(entryId: String): Msg =
        call("title_detailV3?title_id=$entryId&clang=eng").msg(8) ?: error("$name: title not available")

    override suspend fun details(entryId: String): EntryDetails {
        val d = detail(entryId)
        val t = d.msg(1)?.let(::title)?.takeIf { it.lang == 0 } ?: error("$name: title not available in English")
        val genres = d.msgs(31).mapNotNull { it.str(1)?.takeIf(String::isNotEmpty) }
        val nonApp = d.str(8).orEmpty()
        val period = d.str(7).orEmpty()
        val status = when {
            d.msgs(31).any { it.str(2) == "one-shot" } ||
                Regex("completed?", RegexOption.IGNORE_CASE).containsMatchIn(nonApp) ||
                period.contains("latest 0 chapters") -> "Completed"
            nonApp.contains("on a hiatus", true) -> "Hiatus"
            else -> "Ongoing"
        }
        return EntryDetails(
            entry = entry(t),
            description = listOf(d.str(3).orEmpty(), period).filter { it.isNotEmpty() }.joinToString("\n\n").ifEmpty { null },
            authors = t.author?.split(" / ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
            genres = genres,
            status = status,
            officialUrl = "$baseUrl/titles/${t.id}",
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val d = detail(entryId)
        return d.msgs(28).flatMap { it.msgs(2) + it.msgs(4) }
            .filter { it.str(4) != null } // no subtitle = expired / locked
            .map { c ->
                val num = c.str(3).orEmpty()
                Episode(
                    id = c.int(2).toString(),
                    name = "$num - ${c.str(4)}",
                    number = num.substringAfter("#").toFloatOrNull(),
                    dateMillis = (c.int(6) ?: 0L).takeIf { it > 0 }?.times(1000),
                    scanlator = "MANGA Plus",
                )
            }
    }

    override suspend fun pages(episodeId: String): List<Page> {
        val v = call("manga_viewer_v3?chapter_id=$episodeId&split=yes&img_quality=super_high&clang=eng").msg(10)
            ?: error("$name: chapter unavailable")
        val token = v.str(19).orEmpty()
        val out = v.msgs(1).mapNotNull { it.msg(1) }.mapNotNull { p ->
            val url = p.str(1) ?: return@mapNotNull null
            val key = p.str(5)
            ImageReferers.register(url, "$baseUrl/")
            if (key.isNullOrEmpty()) url else "$url#$key:$token"
        }
        if (out.isEmpty()) error("$name: chapter expired or unavailable")
        return out.mapIndexed { i, u -> Page(i, u) }
    }
}

/** Decrypts MANGA Plus page images. Url fragment = "<hexkey>:<viewToken>"; no-op for other requests. */
object MangaPlusImageInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val frag = req.url.fragment
        if (frag == null || !req.url.host.endsWith("tokyo-cdn.com") || ':' !in frag) return chain.proceed(req)
        val (hex, token) = frag.split(':', limit = 2)
        val key = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val r = chain.proceed(
            req.newBuilder()
                .header("Plus-Vw-Token", token)
                .header("Origin", "https://mangaplus.shueisha.co.jp")
                .header("Referer", "https://mangaplus.shueisha.co.jp/")
                .build(),
        )
        if (!r.isSuccessful || key.isEmpty()) return r
        val body = r.body ?: return r
        val data = body.bytes()
        for (i in data.indices) data[i] = (data[i].toInt() xor key[i % key.size].toInt()).toByte()
        return r.newBuilder().body(data.toResponseBody(body.contentType())).build()
    }
}

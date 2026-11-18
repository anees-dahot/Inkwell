package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import java.math.BigInteger

/** Port of LNReader's mvlempyr plugin (WordPress REST on chap.heliosarchive.online + HTML). */
class MvlempyrSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "mvlempyr.com"
    override val name = "MVLEMPYR"
    override val baseUrl = "https://www.mvlempyr.io"
    override val lang = "en"

    private val chapSite = "https://chap.heliosarchive.online/"
    override val headers = mapOf("Cache-Control" to "no-store")

    private class Item(val entry: Entry, val reviewCount: Double, val created: Long)

    private var cache: List<Item>? = null

    private fun s(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull
    private fun d(e: JsonElement?): Double = (e as? JsonPrimitive)?.doubleOrNull ?: s(e)?.toDoubleOrNull() ?: 0.0

    private suspend fun all(): List<Item> {
        cache?.let { return it }
        val base = chapSite + "wp-json/wp/v2/mvl-novels"
        suspend fun page(p: Int, per: Int): JsonArray = runCatching {
            val txt = fetch("$base?per_page=$per&page=$p")
            if (txt.isBlank()) JsonArray(emptyList()) else json.parseToJsonElement(txt).jsonArray
        }.getOrDefault(JsonArray(emptyList()))
        val raw = page(1, 10000) + page(11, 1000) + page(12, 1000) + page(13, 1000)
        val items = raw.mapNotNull {
            val o = it as? JsonObject ?: return@mapNotNull null
            val slug = s(o["slug"]) ?: return@mapNotNull null
            Item(
                Entry(id, "novel/$slug", s(o["name"]).orEmpty(), "https://assets.mvlempyr.app/images/600/${s(o["novel-code"])}.webp", ContentType.NOVEL),
                d(o["total-reviews"]),
                parseDate(s(o["createdOn"])?.substringBefore('.')?.removeSuffix("Z"), "yyyy-MM-dd'T'HH:mm:ss") ?: 0L,
            )
        }
        cache = items
        return items
    }

    private fun paginate(list: List<Entry>, page: Int): PagedResult<Entry> {
        val from = (page - 1) * 20
        val slice = list.drop(from).take(20)
        return PagedResult(slice, from + 20 < list.size)
    }

    override suspend fun popular(page: Int) = paginate(all().sortedByDescending { it.reviewCount }.map { it.entry }, page)
    override suspend fun latest(page: Int) = paginate(all().sortedByDescending { it.created }.map { it.entry }, page)
    override suspend fun search(query: String, page: Int) =
        paginate(all().map { it.entry }.filter { it.title.contains(query, ignoreCase = true) }, page)

    private fun convertNovelId(e: BigInteger): BigInteger {
        val t = BigInteger.valueOf(1999999997L)
        var u = BigInteger.ONE
        var c = BigInteger.valueOf(7).mod(t)
        var dd = e
        while (dd.signum() > 0) {
            if (dd.testBit(0)) u = u.multiply(c).mod(t)
            c = c.multiply(c).mod(t)
            dd = dd.shiftRight(1)
        }
        return u
    }

    private fun checkCaptcha(title: String) {
        if (title == "Attention Required! | Cloudflare" || title == "Just a moment...")
            error("Captcha error, please open in webview")
    }

    override suspend fun details(entryId: String): EntryDetails {
        val doc = doc(absPath(entryId))
        checkCaptcha(doc.title())
        val author = doc.select("div.additionalinfo.tm10 > div.textwrapper:nth-child(1)").firstNotNullOfOrNull { w ->
            val ch = w.children()
            if (ch.size == 2 && ch[0].text().trim() == "Author:") ch[1].text().trim().takeIf { it.isNotEmpty() } else null
        }
        return EntryDetails(
            entry = Entry(
                id, entryId,
                doc.selectFirst("h1.novel-title")?.text()?.takeIf { it.isNotBlank() } ?: "Untitled",
                abs(doc.selectFirst("img.novel-image")?.attr("src")),
                ContentType.NOVEL,
            ),
            description = doc.selectFirst("div.synopsis.w-richtext")?.text()?.trim()?.takeIf { it.isNotEmpty() },
            authors = listOfNotNull(author),
            genres = doc.select(".genre-tags").map { it.text().trim() }.filter { it.isNotEmpty() },
            status = doc.selectFirst(".novelstatustextlarge")?.text()?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val doc = doc(absPath(entryId))
        checkCaptcha(doc.title())
        val code = doc.selectFirst("#novel-code")?.text()?.trim()?.toLongOrNull() ?: return emptyList()
        val newId = convertNovelId(BigInteger.valueOf(code))
        val posts = ArrayList<JsonElement>()
        var page = 1
        var pages = 1
        while (page <= pages) {
            val url = "${chapSite}wp-json/wp/v2/posts?tags=$newId&per_page=500&page=$page"
            val (body, total) = withContext(Dispatchers.IO) {
                client.newCall(request(url)).execute().use { r ->
                    check(r.isSuccessful) { "$name HTTP ${r.code}" }
                    r.body?.string().orEmpty() to (r.header("X-Wp-Totalpages")?.toIntOrNull() ?: 1)
                }
            }
            pages = total
            posts.addAll(json.parseToJsonElement(body).jsonArray)
            page++
        }
        return posts.mapNotNull {
            val acf = (it as? JsonObject)?.get("acf") as? JsonObject ?: return@mapNotNull null
            val num = s(acf["chapter_number"])
            Episode(
                id = "chapter/${s(acf["novel_code"])}-$num",
                name = s(acf["ch_name"]).orEmpty(),
                number = num?.toFloatOrNull(),
                dateMillis = parseDate(s((it as JsonObject)["date"]), "yyyy-MM-dd'T'HH:mm:ss"),
            )
        }.reversed()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        checkCaptcha(d.title())
        return paragraphsOf(d.selectFirst("#chapter > span"))
    }
}

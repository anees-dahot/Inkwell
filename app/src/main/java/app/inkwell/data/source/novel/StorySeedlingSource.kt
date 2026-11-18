package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import kotlinx.serialization.json.contentOrNull
import app.inkwell.data.source.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup

/**
 * Port of LNReader's StorySeedling plugin. The site is AJAX-driven: listings
 * and chapter lists are multipart POSTs to `ajax`, and chapter text lives on a
 * nonce-protected `…/content` endpoint behind a light character-shift
 * obfuscation.
 */
class StorySeedlingSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "storyseedling"
    override val name = "StorySeedling"
    override val baseUrl = "https://storyseedling.com"
    override val lang = "en"

    private var nonce: String? = null

    // ---- requests ----------------------------------------------------------

    private suspend fun postMultipart(url: String, fields: Map<String, String>, referer: String? = null): String =
        withContext(Dispatchers.IO) {
            val mb = MultipartBody.Builder().setType(MultipartBody.FORM)
            fields.forEach { (k, v) -> mb.addFormDataPart(k, v) }
            val rb = request(url).newBuilder().post(mb.build())
            referer?.let { rb.header("Referer", it) }
            client.newCall(rb.build()).execute().use { r ->
                check(r.isSuccessful) { "$name HTTP ${r.code}" }
                r.body?.string().orEmpty()
            }
        }

    // ---- listing -----------------------------------------------------------

    private suspend fun getNovels(pageNo: Int, searchTerm: String = ""): PagedResult<Entry> {
        val browse = doc(absPath("browse"))
        val postValue = browse.selectFirst("div[ax-load][x-data]")?.attr("x-data")
            ?.replace("browse('", "")?.replace("')", "")?.trim()
            ?: return PagedResult(emptyList(), false)
        val root = runCatching {
            Json.parseToJsonElement(
                postMultipart(
                    absPath("ajax"),
                    mapOf(
                        "search" to searchTerm,
                        "orderBy" to "recent",
                        "curpage" to pageNo.toString(),
                        "post" to postValue,
                        "action" to "fetch_browse",
                    ),
                ),
            ).jsonObject
        }.getOrNull()
        val items = root?.get("data")?.jsonObject?.get("posts")?.jsonArray?.mapNotNull { el ->
            val o = el.jsonObject
            val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val permalink = o["permalink"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Entry(
                id,
                permalink.removePrefix("$baseUrl/").trimStart('/'),
                title,
                abs(o["thumbnail"]?.jsonPrimitive?.contentOrNull),
                ContentType.NOVEL,
            )
        } ?: emptyList()
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun popular(page: Int) = getNovels(page)
    override suspend fun latest(page: Int) = getNovels(page) // browse sorts by "recent"

    override suspend fun search(query: String, page: Int) = getNovels(page, query)

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val title = d.selectFirst("h1")?.text()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: "Untitled"
        val cover = d.selectFirst("img[x-ref=\"art\"]")?.attr("src")

        val genres = d.select("section.relative[x-data]")
            .firstOrNull { it.attr("x-data").contains("chapters") }
            ?.select("> div > div > div.flex.flex-wrap > a")
            ?.eachText()?.map { it.trim() } ?: emptyList()

        val author = d.selectFirst("div.mb-1 a")?.text()?.trim()
        val rawStatus = d.selectFirst("div.gap-2 span.text-sm")?.text()?.trim()?.lowercase()
        val status = when (rawStatus) {
            "ongoing" -> "Ongoing"
            "hiatus" -> "On Hiatus"
            "dropped", "cancelled" -> "Cancelled"
            "completed" -> "Completed"
            else -> null
        }

        val summaryDiv = d.select("div.mb-4.order-2")
            .firstOrNull { !it.classNames().contains("lg:grid-in-buttons") }
        val pTags = summaryDiv?.select("p").orEmpty()
        val summary = if (pTags.isNotEmpty()) {
            pTags.joinToString("\n\n") { it.text().trim() }
        } else {
            summaryDiv?.text()?.trim().orEmpty()
        }

        val chapters = mutableListOf<Episode>()
        val xdata = d.selectFirst(".bg-accent div[ax-load][x-data]")?.attr("x-data")
        // expected format: toc('000000', 'xxxxxxxxxx') (6 numbers, 10 hex)
        val parts = xdata?.split("'").orEmpty()
        if (parts.size > 3) {
            val dataNovelId = parts[1]
            val dataNovelN = parts[3]
            val novelId = dataNovelId.ifBlank {
                Regex("\\d+").find(entryId)?.value
            }
            if (!novelId.isNullOrEmpty()) {
                val tocRoot = runCatching {
                    Json.parseToJsonElement(
                        postMultipart(
                            absPath("ajax"),
                            mapOf(
                                "post" to dataNovelN,
                                "id" to novelId,
                                "action" to "series_toc",
                            ),
                            referer = absPath(entryId),
                        ),
                    ).jsonObject
                }.getOrNull()
                tocRoot?.get("data")?.jsonArray?.forEach { el ->
                    val o = el.jsonObject
                    val url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val slugNumber = o["slug"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
                    chapters.add(
                        Episode(
                            id = url.removePrefix("$baseUrl/").trimStart('/'),
                            name = o["title"]?.jsonPrimitive?.contentOrNull ?: "Chapter $slugNumber",
                            number = slugNumber,
                            dateMillis = parseDate(o["date"]?.jsonPrimitive?.contentOrNull),
                        ),
                    )
                }
            }
        }

        return EntryDetails(
            entry = Entry(id, entryId, title, abs(cover), ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = genres,
            status = status,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter text ------------------------------------------------------

    private suspend fun updateNonce(chapterPath: String) {
        val d = doc(absPath(chapterPath))
        val xdata = d.selectFirst("div.mb-4:has(h1.text-xl) > div")?.attr("x-data").orEmpty()
        nonce = Regex("loadChapter\\('.+?', '(.+?)'\\)").find(xdata)?.groupValues?.get(1)
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val updatedNonce = nonce == null
        if (nonce == null) updateNonce(episodeId)
        val currentNonce = nonce ?: return emptyList()

        val body = withContext(Dispatchers.IO) {
            val req = request(absPath("$episodeId/content")).newBuilder()
                .header("Referer", absPath(episodeId) + "/")
                .header("x-nonce", currentNonce)
                .post("""{"captcha_response":""}""".toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                check(r.isSuccessful) { "$name HTTP ${r.code}" }
                r.body?.string().orEmpty()
            }
        }

        val result = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        if (result != null && result["success"]?.jsonPrimitive?.booleanOrNull == false) {
            val message = result["message"]?.jsonPrimitive?.contentOrNull
            if (message == "Invalid security.") {
                if (updatedNonce) return emptyList()
                nonce = null
                return chapterText(episodeId)
            }
            if (result["captcha"]?.jsonPrimitive?.booleanOrNull == true) return emptyList()
        }

        // Strip obfuscation markers and decode the character-shift cipher.
        val decoded = body.replace(Regex("cls[a-f0-9]+"), "")
            .map { ch ->
                val offset = if (ch.code > 12123) 12027 else 12033
                val decodedCode = ch.code - offset
                if (decodedCode in 32..126) decodedCode.toChar() else ch
            }
            .joinToString("")
        val d = Jsoup.parse(decoded)
        d.select("span").forEach { span ->
            val t = span.text()
            if (t.lowercase().contains("storyseedling") || t.lowercase().contains("story seedling")) {
                span.text("")
            }
        }
        return paragraphsOf(d.body())
    }
}

package app.inkwell.data.source.novel

import app.inkwell.data.source.paragraphsOf
import kotlinx.serialization.json.put

import kotlinx.serialization.json.booleanOrNull

import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.data.model.PagedResult
import app.inkwell.data.source.ScraperNovelSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient

/**
 * Cherry Mist Cafe (cherrymist.cafe). A JSON API under `/api` drives the whole
 * plugin: `/series` (paged), `/series/<slug>`, `/chapters?series_id=`,
 * `/chapters/<id>` and `/chapters/<id>/reveal` (the reveal endpoint unlocks
 * token-gated chapter bodies anonymously; the proof is an HMAC-SHA256 over
 * the reveal ticket's `jti`).
 */
class CherryMistCafeSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "cherrymistcafe"
    override val name = "Cherry Mist Cafe"
    override val baseUrl = "https://cherrymist.cafe"
    override val lang = "en"

    override val headers = mapOf(
        "Referer" to "https://cherrymist.cafe/",
        "X-CM-Reader" to "1",
    )

    private val jsonParser = Json { ignoreUnknownKeys = true; isLenient = true }
    private val pageSize = 24
    private val chapterIds = HashMap<String, String>()

    private fun JsonElement?.str(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonObject?.str(key: String): String? = this?.get(key).str()

    /** JS truthiness for number|boolean|JSON flag fields. */
    private fun truthy(el: JsonElement?): Boolean {
        val p = el as? JsonPrimitive ?: return false
        if (p is JsonNull) return false
        p.booleanOrNull?.let { return it }
        return p.content.toDoubleOrNull()?.let { it != 0.0 } ?: p.content.isNotBlank()
    }

    private fun normalizePath(path: String): String =
        path.replace(Regex("^https?://[^/]+", RegexOption.IGNORE_CASE), "").trim('/')

    private fun enc(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Array or { rows: [...] }. */
    private fun seriesRows(root: JsonElement): List<JsonObject> = when (root) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> (root["rows"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        else -> emptyList()
    }

    private fun cover(o: JsonObject): String? =
        o.str("cover_image_url") ?: o.str("cover_thumb_url")?.let { abs(it) }

    private fun entryOf(o: JsonObject): Entry? {
        val title = o.str("title") ?: return null
        val slug = o.str("slug") ?: o.str("id") ?: return null
        return Entry(id, "story/$slug", title, cover(o), ContentType.NOVEL)
    }

    private suspend fun fetchSeriesPage(page: Int, extraQuery: String): PagedResult<Entry> {
        val root = json(absPath("api/series?page=$page&limit=$pageSize$extraQuery"))
        val items = seriesRows(root).mapNotNull(::entryOf)
        return PagedResult(items, items.isNotEmpty())
    }

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> =
        fetchSeriesPage(page, "&sort=popular")

    /** The server's default ordering is "latest update". */
    override suspend fun latest(page: Int): PagedResult<Entry> =
        fetchSeriesPage(page, "")

    override suspend fun search(query: String, page: Int): PagedResult<Entry> =
        fetchSeriesPage(page, "&q=${enc(query.trim())}")

    // ---- details -----------------------------------------------------------

    private fun mapStatus(raw: String?): String? = when (raw?.lowercase()?.trim()) {
        "ongoing" -> "Ongoing"
        "completed", "oneshot" -> "Completed"
        "hiatus" -> "On Hiatus"
        "dropped", "cancelled", "canceled" -> "Dropped"
        else -> null
    }

    private fun cleanSummary(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val cleaned = raw.replace(Regex("<!--[\\s\\S]*?-->"), "")
        val body = org.jsoup.Jsoup.parseBodyFragment(cleaned).body()
        body.select("p").after("\n\n")
        body.select("br").after("\n")
        return body.text()
            .replace("\r\n", "\n")
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    override suspend fun details(entryId: String): EntryDetails {
        val slug = normalizePath(entryId).replace(Regex("^story/", RegexOption.IGNORE_CASE), "")
        val series = json(absPath("api/series/${enc(slug)}")).jsonObject
        val seriesSlug = series.str("slug") ?: series.str("id") ?: slug

        val genres = buildList {
            addAll((series["genres"] as? JsonArray).orEmpty().mapNotNull { it.str() })
            addAll((series["tags"] as? JsonArray).orEmpty().mapNotNull { it.str() })
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        val author = series.str("original_author")
            ?: series.str("author_name")
            ?: (series["translator"] as? JsonObject)?.str("name")
            ?: series.str("translator_name")

        return EntryDetails(
            entry = Entry(id, "story/$seriesSlug", series.str("title") ?: "Untitled", cover(series), ContentType.NOVEL),
            description = cleanSummary(
                series.str("synopsis") ?: series.str("description") ?: series.str("short_synopsis"),
            ),
            authors = listOfNotNull(author?.takeIf { it.isNotBlank() }),
            genres = genres,
            status = mapStatus(series.str("story_status") ?: series.str("status")),
        )
    }

    private suspend fun fetchChapters(entryId: String): List<Episode> {
        val slug = normalizePath(entryId).replace(Regex("^story/", RegexOption.IGNORE_CASE), "")
        val series = json(absPath("api/series/${enc(slug)}")).jsonObject
        val seriesId = series.str("id").orEmpty()
        val seriesSlug = series.str("slug") ?: series.str("id") ?: slug
        val seriesPasswordLocked = truthy(series["chapters_locked"])

        val raw = json(absPath("api/chapters?series_id=${enc(seriesId)}")) as? JsonArray
        val chapters = mutableListOf<Episode>()
        raw.orEmpty().forEach { el ->
            val ch = el as? JsonObject ?: return@forEach
            if (ch.str("status") != "published") return@forEach
            val priceType = ch.str("price_type")
            if (priceType != null && priceType != "free") return@forEach
            if (truthy(ch["has_access_password"])) return@forEach
            if (truthy(ch["is_locked"]) && seriesPasswordLocked) return@forEach

            val chSlug = ch.str("slug")?.trim()?.takeIf { it.isNotEmpty() }
            val number = ch.str("chapter_number")?.toFloatOrNull()
                ?.takeIf { it >= 0f && it.isFinite() }
                ?: (chapters.size + 1).toFloat()
            val path = chSlug ?: "story/$seriesSlug/chapter/${fmtNum(number)}"

            chapterIds[path] = ch.str("id").orEmpty()
            if (chSlug != null) chapterIds[chSlug] = ch.str("id").orEmpty()

            chapters += Episode(
                id = path,
                name = ch.str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: "Chapter ${fmtNum(number)}",
                number = number,
                dateMillis = parseDate(ch.str("published_at") ?: ch.str("created_at")),
            )
        }
        return chapters
    }

    /** 12 -> "12", 12.5 -> "12.5". */
    private fun fmtNum(n: Float): String =
        if (n % 1f == 0f) n.toLong().toString() else n.toString()

    override suspend fun episodes(entryId: String): List<Episode> = fetchChapters(entryId)

    // ---- chapter -----------------------------------------------------------

    private suspend fun resolveChapterId(chapterPath: String): String {
        val cleaned = normalizePath(chapterPath)
        chapterIds[cleaned]?.let { return it }

        val storyMatch = Regex("^story/([^/]+)/chapter/([^/]+)$", RegexOption.IGNORE_CASE).find(cleaned)
        if (storyMatch != null) {
            val (seriesSlug, chapterKey) = storyMatch.destructured
            val series = json(absPath("api/series/${enc(seriesSlug)}")).jsonObject
            val chapters = json(absPath("api/chapters?series_id=${enc(series.str("id").orEmpty())}"))
            val found = (chapters as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .firstOrNull {
                    it.str("chapter_number") == chapterKey ||
                        it.str("slug") == chapterKey ||
                        it.str("id") == chapterKey
                }
            if (found != null) {
                found.str("id")?.takeIf { it.isNotEmpty() }?.let { id ->
                    chapterIds[cleaned] = id
                    return id
                }
            }
        }

        val slug = cleaned.replace(Regex("^chapter/", RegexOption.IGNORE_CASE), "")
        chapterIds[slug]?.let { return it }
        val bySlug = json(absPath("api/chapters/by-slug/${enc(slug)}")).jsonObject
        val id = bySlug.str("id").orEmpty()
        chapterIds[slug] = id
        return id
    }

    /** Anonymous proof for a reveal ticket: HMAC-SHA256(key, "chapter-reveal.v2.<id>.<jti>"). */
    private fun ticketJti(ticket: String): String? = runCatching {
        val parts = ticket.split('.')
        if (parts.size < 2) return null
        val payload = jsonParser.parseToJsonElement(
            String(java.util.Base64.getUrlDecoder().decode(parts[1])),
        ).jsonObject
        payload["jti"].str()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun hmacSha256Hex(key: String, msg: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Reverse text hidden behind [data-fcnc-rev] watermarks; drop empty nodes. */
    private fun cleanParagraphs(raw: String?): List<String> {
        if (raw.isNullOrEmpty()) return emptyList()
        val dewatermarked = raw
            .replace(Regex("[\\uFE00-\\uFE0F\\u200B-\\u200F\\u202A-\\u202E\\u2060\\uFEFF\\u00AD]"), "")
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
            .replace('\u00A0', ' ')
        val body = org.jsoup.Jsoup.parseBodyFragment(dewatermarked).body()
        body.select("script, style, iframe, ruby").remove()
        body.select("[data-fcnc-rev=1]").forEach { el ->
            val text = el.text().trim()
            if (text.isNotEmpty()) el.text(text.reversed())
        }
        body.select("span").forEach { span ->
            if (span.text().trim().isEmpty() && span.selectFirst("img") == null) span.remove()
        }
        body.select("[class]").forEach { node ->
            val cls = node.attr("class").split(Regex("\\s+"))
                .filter { it.isNotEmpty() && !Regex("^tx-[a-z2-7]+$", RegexOption.IGNORE_CASE).matches(it) }
                .joinToString(" ")
            if (cls.isNotEmpty()) node.attr("class", cls) else node.removeAttr("class")
        }
        body.select("p").forEach { p ->
            if (p.text().trim().isEmpty() && p.selectFirst("img, hr") == null) p.remove()
        }
        return paragraphsOf(body)
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val chapterId = resolveChapterId(episodeId)
        val chapter = json(absPath("api/chapters/${enc(chapterId)}")).jsonObject

        var content = chapter.str("content").orEmpty()
        var foreword = chapter.str("foreword").orEmpty()
        var afterword = chapter.str("afterword").orEmpty()

        val ticket = chapter.str("revealTicket")
        val key = chapter.str("revealKey")
        if ((truthy(chapter["bodyWithheld"]) || content.isBlank()) && !ticket.isNullOrEmpty() && !key.isNullOrEmpty()) {
            val payload = buildJsonObject {
                put("ticket", ticket)
                put("frames", 25)
                put("elapsedMs", 650)
                put("visible", true)
                put("focused", true)
                ticketJti(ticket)?.let { jti ->
                    put("proof", hmacSha256Hex(key, "chapter-reveal.v2.$chapterId.$jti"))
                    put("proofScheme", "v2")
                }
            }.toString()
            val revealed = jsonPost(absPath("api/chapters/${enc(chapterId)}/reveal"), payload).jsonObject
            revealed.str("content")?.takeIf { it.isNotEmpty() }?.let { content = it }
            revealed.str("foreword")?.takeIf { it.isNotEmpty() }?.let { foreword = it }
            revealed.str("afterword")?.takeIf { it.isNotEmpty() }?.let { afterword = it }
        }

        return cleanParagraphs(foreword) + cleanParagraphs(content) + cleanParagraphs(afterword)
    }
}

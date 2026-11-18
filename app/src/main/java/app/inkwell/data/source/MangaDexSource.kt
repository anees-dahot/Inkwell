package app.inkwell.data.source

import app.inkwell.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.OffsetDateTime

class MangaDexSource(
    private val client: OkHttpClient,
    private val langProvider: () -> String,
) : MangaSource {
    override val id = "mangadex"
    override val name = "MangaDex"
    override val type = ContentType.MANGA
    override val lang get() = langProvider()

    private val json = Json { ignoreUnknownKeys = true }
    private val base = "https://api.mangadex.org"
    private val pageSize = 20

    private suspend fun get(url: HttpUrl): JsonObject = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", UA).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("MangaDex HTTP ${r.code}")
            json.parseToJsonElement(r.body!!.string()).jsonObject
        }
    }

    private fun listUrl(order: String?, query: String?, page: Int): HttpUrl =
        "$base/manga".toHttpUrl().newBuilder().apply {
            addQueryParameter("limit", pageSize.toString())
            addQueryParameter("offset", ((page - 1) * pageSize).toString())
            addQueryParameter("includes[]", "cover_art")
            addQueryParameter("contentRating[]", "safe")
            addQueryParameter("contentRating[]", "suggestive")
            addQueryParameter("contentRating[]", "erotica")
            addQueryParameter("contentRating[]", "pornographic")
            addQueryParameter("hasAvailableChapters", "true")
            addQueryParameter("availableTranslatedLanguage[]", lang)
            if (order != null) addQueryParameter(order, "desc")
            if (!query.isNullOrBlank()) {
                addQueryParameter("title", query)
                addQueryParameter("order[relevance]", "desc")
            }
        }.build()

    private suspend fun list(url: HttpUrl, page: Int): PagedResult<Entry> {
        val root = get(url)
        val items = root["data"]?.jsonArray?.map { toEntry(it.jsonObject) } ?: emptyList()
        val total = root["total"]?.jsonPrimitive?.intOrNull ?: 0
        return PagedResult(items, page * pageSize < total)
    }

    override suspend fun popular(page: Int) = list(listUrl("order[followedCount]", null, page), page)
    override suspend fun latest(page: Int) = list(listUrl("order[latestUploadedChapter]", null, page), page)
    override suspend fun search(query: String, page: Int) = list(listUrl(null, query, page), page)

    private fun pickTitle(m: JsonObject?): String {
        if (m == null) return "Untitled"
        return m["en"]?.jsonPrimitive?.contentOrNull
            ?: m.values.firstOrNull()?.jsonPrimitive?.contentOrNull ?: "Untitled"
    }

    private fun coverOf(obj: JsonObject, size: Int = 256): String? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val rel = obj["relationships"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "cover_art" } ?: return null
        val file = rel["attributes"]?.jsonObject?.get("fileName")?.jsonPrimitive?.contentOrNull ?: return null
        return "https://uploads.mangadex.org/covers/$id/$file.$size.jpg"
    }

    private fun toEntry(obj: JsonObject, size: Int = 256): Entry {
        val attrs = obj["attributes"]!!.jsonObject
        return Entry(
            sourceId = id,
            id = obj["id"]!!.jsonPrimitive.content,
            title = pickTitle(attrs["title"]?.jsonObject),
            coverUrl = coverOf(obj, size),
            type = ContentType.MANGA,
        )
    }

    override suspend fun details(entryId: String): EntryDetails {
        val url = "$base/manga/$entryId".toHttpUrl().newBuilder()
            .addQueryParameter("includes[]", "cover_art")
            .addQueryParameter("includes[]", "author")
            .addQueryParameter("includes[]", "artist").build()
        val obj = get(url)["data"]!!.jsonObject
        val attrs = obj["attributes"]!!.jsonObject
        val authors = obj["relationships"]?.jsonArray?.map { it.jsonObject }
            ?.filter { it["type"]?.jsonPrimitive?.contentOrNull in setOf("author", "artist") }
            ?.mapNotNull { it["attributes"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull }
            ?.distinct() ?: emptyList()
        val tags = attrs["tags"]?.jsonArray?.mapNotNull {
            it.jsonObject["attributes"]?.jsonObject?.get("name")?.jsonObject?.get("en")?.jsonPrimitive?.contentOrNull
        } ?: emptyList()
        val rawDesc = attrs["description"]?.jsonObject?.let { (it[lang] ?: it["en"])?.jsonPrimitive?.contentOrNull }
        val desc = rawDesc?.let(::cleanMarkdown)
        val descOfficial = rawDesc?.let { Regex("""Official English[^\n]*?\]\((https?://[^)\s]+)\)""").find(it)?.groupValues?.get(1) }
        val origin = attrs["originalLanguage"]?.jsonPrimitive?.contentOrNull
        val genres = if (origin == "ko" && "Long Strip" !in tags) tags + "Long Strip" else tags
        return EntryDetails(
            entry = toEntry(obj, 512),
            description = desc,
            authors = authors,
            genres = genres,
            status = attrs["status"]?.jsonPrimitive?.contentOrNull?.replaceFirstChar { it.uppercase() },
            officialUrl = attrs["links"]?.let { it as? JsonObject }?.get("engtl")?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("http") } ?: descOfficial,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val out = mutableListOf<Episode>()
        var offset = 0
        val limit = 500
        while (true) {
            val url = "$base/manga/$entryId/feed".toHttpUrl().newBuilder()
                .addQueryParameter("translatedLanguage[]", lang)
                .addQueryParameter("order[chapter]", "desc")
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("includes[]", "scanlation_group")
                .addQueryParameter("contentRating[]", "safe")
                .addQueryParameter("contentRating[]", "suggestive")
                .addQueryParameter("contentRating[]", "erotica")
                .addQueryParameter("contentRating[]", "pornographic")
                .build()
            val root = get(url)
            val data = root["data"]?.jsonArray ?: break
            for (el in data) {
                val o = el.jsonObject
                val a = o["attributes"]!!.jsonObject
                if (a["externalUrl"]?.takeIf { it !is JsonNull } != null) continue
                if ((a["pages"]?.jsonPrimitive?.intOrNull ?: 1) == 0) continue
                val ch = a["chapter"]?.jsonPrimitive?.contentOrNull
                val title = a["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val name = buildString {
                    append(if (ch != null) "Ch. $ch" else "Oneshot")
                    if (title != null) append(" - ").append(title)
                }
                val group = o["relationships"]?.jsonArray?.map { it.jsonObject }
                    ?.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "scanlation_group" }
                    ?.get("attributes")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                val date = a["publishAt"]?.jsonPrimitive?.contentOrNull?.let {
                    runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull()
                }
                out += Episode(o["id"]!!.jsonPrimitive.content, name, ch?.toFloatOrNull(), date, group)
            }
            val total = root["total"]?.jsonPrimitive?.intOrNull ?: 0
            offset += limit
            if (offset >= total || data.isEmpty()) break
        }
        return out
    }

    override suspend fun pages(episodeId: String): List<Page> {
        val root = get("$base/at-home/server/$episodeId".toHttpUrl())
        val baseUrl = root["baseUrl"]!!.jsonPrimitive.content
        val ch = root["chapter"]!!.jsonObject
        val hash = ch["hash"]!!.jsonPrimitive.content
        var files = ch["data"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        var dir = "data"
        if (files.isEmpty()) {
            files = ch["dataSaver"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
            dir = "data-saver"
        }
        return files.mapIndexed { i, f -> Page(i, "$baseUrl/$dir/$hash/$f") }
    }

    companion object { const val UA = "Inkwell/0.1 (Android)" }
}

/** MangaDex descriptions are Markdown with a trailing links section; render as plain text. */
internal fun cleanMarkdown(src: String): String = src
    .substringBefore("\n---")                                   // drop "Links" footer
    .replace(Regex("""\[([^\]]*)]\([^)]*\)"""), "$1")          // [text](url) -> text
    .replace(Regex("""\*\*|__|(?<!\w)[*_](?!\s)|(?<!\s)[*_](?!\w)"""), "")  // bold/italic markers
    .replace(Regex("""^#+\s*""", RegexOption.MULTILINE), "")  // headings
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()

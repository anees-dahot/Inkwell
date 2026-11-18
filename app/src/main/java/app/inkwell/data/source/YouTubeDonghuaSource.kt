package app.inkwell.data.source

import android.content.Context
import app.inkwell.data.db.CustomChannel
import app.inkwell.data.db.CustomChannelDao
import app.inkwell.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.OffsetDateTime

data class YtFeed(val title: String, val author: String?, val items: List<YtItem>)
data class YtItem(val videoId: String, val title: String, val thumb: String?, val published: Long?)

/**
 * Official YouTube uploads via public RSS. Entry id = "pl:<playlistId>" or "ch:<channelId>".
 */
class YouTubeDonghuaSource(
    private val context: Context,
    private val client: OkHttpClient,
    private val dao: CustomChannelDao,
) : VideoSource {
    override val id = "youtube"
    override val name = "Donghua (YouTube)"
    override val type = ContentType.VIDEO
    override val lang = "multi"

    private val feedCache = HashMap<String, Pair<Long, YtFeed>>()

    private val seeds: List<Entry> by lazy {
        runCatching {
            val txt = context.assets.open("donghua_seed.json").bufferedReader().use { it.readText() }
            Json.parseToJsonElement(txt).jsonArray.map {
                val o = it.jsonObject
                val key = o["playlistId"]?.jsonPrimitive?.contentOrNull?.let { p -> "pl:$p" }
                    ?: "ch:" + o["channelId"]!!.jsonPrimitive.content
                Entry(id, key, o["title"]!!.jsonPrimitive.content, o["cover"]?.jsonPrimitive?.contentOrNull, ContentType.VIDEO)
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun all(): List<Entry> =
        seeds + dao.all().map { Entry(id, it.id, it.title, it.coverUrl, ContentType.VIDEO) }

    override suspend fun popular(page: Int) = PagedResult(all(), false)
    override suspend fun latest(page: Int) = PagedResult(all().reversed(), false)
    override suspend fun search(query: String, page: Int) =
        PagedResult(all().filter { it.title.contains(query, ignoreCase = true) }, false)

    fun feedUrl(entryId: String): String = when {
        entryId.startsWith("pl:") -> "https://www.youtube.com/feeds/videos.xml?playlist_id=${entryId.removePrefix("pl:")}"
        else -> "https://www.youtube.com/feeds/videos.xml?channel_id=${entryId.removePrefix("ch:")}"
    }

    fun openUrl(entryId: String): String = when {
        entryId.startsWith("pl:") -> "https://www.youtube.com/playlist?list=${entryId.removePrefix("pl:")}"
        else -> "https://www.youtube.com/channel/${entryId.removePrefix("ch:")}"
    }

    suspend fun feed(entryId: String, force: Boolean = false): YtFeed {
        feedCache[entryId]?.let { (t, f) -> if (!force && System.currentTimeMillis() - t < 5 * 60_000) return f }
        val body = http(feedUrl(entryId))
        val f = parseFeed(body)
        feedCache[entryId] = System.currentTimeMillis() to f
        return f
    }

    private suspend fun http(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) Inkwell/0.1")
            .header("Accept-Language", "en").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("YouTube HTTP ${r.code}")
            r.body!!.string()
        }
    }

    private fun parseFeed(xml: String): YtFeed {
        val p = XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(StringReader(xml))
        var feedTitle = ""
        var feedAuthor: String? = null
        val items = mutableListOf<YtItem>()
        var inEntry = false
        var inAuthor = false
        var vid = ""; var title = ""; var thumb: String? = null; var pub: Long? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> { inEntry = true; vid = ""; title = ""; thumb = null; pub = null }
                    "author" -> inAuthor = true
                    "name" -> if (inAuthor && !inEntry) feedAuthor = p.nextText()
                    "title" -> { val t = p.nextText(); if (inEntry) title = t else if (feedTitle.isEmpty()) feedTitle = t }
                    "videoId" -> if (inEntry) vid = p.nextText()
                    "thumbnail" -> if (inEntry) thumb = p.getAttributeValue(null, "url")
                    "published" -> if (inEntry) pub = runCatching { OffsetDateTime.parse(p.nextText()).toInstant().toEpochMilli() }.getOrNull()
                }
                XmlPullParser.END_TAG -> when (p.name) {
                    "entry" -> { inEntry = false; if (vid.isNotEmpty()) items += YtItem(vid, title, thumb, pub) }
                    "author" -> inAuthor = false
                }
            }
            ev = p.next()
        }
        return YtFeed(feedTitle, feedAuthor, items)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val f = feed(entryId)
        val known = all().firstOrNull { it.id == entryId }
        val entry = known ?: Entry(id, entryId, f.title, f.items.firstOrNull()?.thumb, ContentType.VIDEO)
        return EntryDetails(
            entry = entry.copy(coverUrl = entry.coverUrl ?: f.items.firstOrNull()?.thumb),
            description = "Official uploads" + (f.author?.let { " from $it" } ?: "") + ".",
            authors = listOfNotNull(f.author),
            genres = listOf("Donghua"),
            status = null,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> =
        feed(entryId).items.map {
            Episode(it.videoId, it.title, EP_RE.find(it.title)?.groupValues?.get(1)?.toFloatOrNull(), it.published)
        }

    override suspend fun videoId(episodeId: String) = episodeId

    /** Add a custom channel/playlist by pasted URL. Returns the created entry. */
    suspend fun addCustom(input: String): Entry {
        val url = input.trim()
        val key: String = PLAYLIST_RE.find(url)?.let { "pl:" + it.groupValues[1] }
            ?: CHANNEL_RE.find(url)?.let { "ch:" + it.groupValues[1] }
            ?: if (url.matches(Regex("UC[\\w-]{22}"))) "ch:$url" else {
                val page = http(if (url.startsWith("http")) url else "https://www.youtube.com/$url")
                val cid = Regex("\"channelId\":\"(UC[\\w-]{22})\"").find(page)?.groupValues?.get(1)
                    ?: Regex("<link rel=\"canonical\" href=\"[^\"]*/channel/(UC[\\w-]{22})\"").find(page)?.groupValues?.get(1)
                    ?: error("Could not find a channel at that URL")
                "ch:$cid"
            }
        val f = feed(key, force = true) // validates (throws on 404)
        val cover = f.items.firstOrNull()?.thumb
        val title = f.title.ifBlank { f.author ?: key }
        dao.upsert(CustomChannel(key, title, cover))
        return Entry(id, key, title, cover, ContentType.VIDEO)
    }

    suspend fun removeCustom(entryId: String) = dao.delete(entryId)
    suspend fun isCustom(entryId: String) = dao.all().any { it.id == entryId }

    companion object {
        private val EP_RE = Regex("(?:EP|Ep|Episode|第)\\s*(\\d+(?:\\.\\d+)?)")
        private val PLAYLIST_RE = Regex("[?&]list=([\\w-]+)")
        private val CHANNEL_RE = Regex("/channel/(UC[\\w-]{22})")
    }
}

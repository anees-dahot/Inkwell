package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class MaehwaSupSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "maehwasup"
    override val name = "Maehwasup"
    override val baseUrl = "https://maehwasup.com"
    override val lang = "en"

    // The site is one long-running novel: the home page is the novel page and
    // the chapters are its dated posts.
    private suspend fun novelItem(): Entry? {
        val d = doc(baseUrl)
        val novelName = d.selectFirst(".wp-block-site-title")?.text()?.trim().orEmpty()
        if (novelName.isEmpty()) return null
        return Entry(id, "", novelName, null, ContentType.NOVEL)
    }

    private suspend fun index(page: Int) =
        json("https://public-api.wordpress.com/rest/v1.1/sites/maehwasup.com/posts/" +
            "?number=100&page=$page&order=ASC&order_by=date&fields=URL,title,date").jsonObject

    // ---- listing -----------------------------------------------------------

    override suspend fun popular(page: Int): PagedResult<Entry> {
        if (page != 1) return PagedResult(emptyList(), false)
        val item = novelItem() ?: return PagedResult(emptyList(), false)
        return PagedResult(listOf(item), false)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page != 1) return PagedResult(emptyList(), false)
        val term = query.trim().lowercase()
        val items = popular(1).items.filter { it.title.lowercase().contains(term) }
        return PagedResult(items, false)
    }

    // ---- details -----------------------------------------------------------

    private val cachedChapters = HashMap<String, List<Episode>>()

    override suspend fun details(entryId: String): EntryDetails {
        val item = novelItem()
        val first = index(1)
        val found = first["found"]?.jsonPrimitive?.longOrNull ?: 0L
        val pages = ((found + 99) / 100).toInt()

        val chapters = mutableListOf<Episode>()
        val seen = HashSet<String>()
        var received = 0
        for (page in 1..pages) {
            val data = if (page == 1) first else index(page)
            val posts = data["posts"]?.jsonArray ?: break
            received += posts.size
            for (post in posts) {
                val o = post.jsonObject
                val rawTitle = o["title"]?.jsonPrimitive?.content ?: continue
                val chapterName = Jsoup.parse(rawTitle).text().trim()
                if (!Regex("chapter|side story|spinoff|prologue|epilogue", RegexOption.IGNORE_CASE)
                    .containsMatchIn(chapterName)
                ) continue
                val url = o["URL"]?.jsonPrimitive?.content?.let { it.toHttpUrlOrNull() } ?: continue
                if (!url.host.equals(baseUrl.removePrefix("https://"), ignoreCase = true)) continue
                val path = url.encodedPath.trimStart('/')
                if (!seen.add(path)) continue
                val number = Regex("^Chapter\\s+(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
                    .find(chapterName)?.groupValues?.get(1)?.toFloatOrNull()
                chapters += Episode(
                    id = path,
                    name = chapterName,
                    number = number,
                    dateMillis = parseDate(o["date"]?.jsonPrimitive?.content),
                )
            }
        }

        return EntryDetails(
            entry = (item ?: Entry(id, entryId.trimStart('/'), "Maehwasup", null, ContentType.NOVEL))
                .let { it.copy(id = entryId.trimStart('/')) },
            description = null,
            authors = emptyList(),
            genres = emptyList(),
            status = null,
        ).also { cachedChapters[entryId] = chapters }
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId] ?: emptyList()
    }

    // ---- chapter -----------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val body = d.selectFirst(".entry-content.wp-block-post-content") ?: return emptyList()
        body.select("script, style, iframe, .wordads-tag, .sharedaddy, form").remove()
        body.select("p").forEach { p ->
            if (Regex("^(Please,?\\s+subscribe/donate|ROTMHS Glossary)", RegexOption.IGNORE_CASE)
                .containsMatchIn(p.text().trim())
            ) p.remove()
        }
        return paragraphsOf(body)
    }
}

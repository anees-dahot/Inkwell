package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import java.net.URI
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.jsoup.nodes.Element

/** Port of LNReader's craneanime plugin (hand-built WordPress site). */
class CraneAnimeSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "craneanime"
    override val name = "CraneAnime Translation"
    override val baseUrl = "https://translation.craneanime.xyz"
    override val lang = "en"

    private val notChapter =
        Regex("""^/(?:[\w-]*gallery|early-access|support-us|library|home|about-us|contact-us|patreon-flow)/?$""", RegexOption.IGNORE_CASE)

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun pathOf(href: String): String? {
        val u = runCatching { URI(baseUrl).resolve(href) }.getOrNull() ?: return null
        return if (u.host == URI(baseUrl).host) u.path else null
    }

    private fun imageSrc(img: Element?): String? {
        img ?: return null
        val lazy = img.attr("data-src")
        val src = img.attr("src")
        val v = lazy.ifEmpty { if (src.isNotEmpty() && !src.startsWith("data:")) src else "" }
        return if (v.isEmpty()) null else abs(v)
    }

    private suspend fun storiesWithChapters(): Set<String>? = runCatching {
        val arr = json("$baseUrl/wp-json/wp/v2/categories?per_page=100&_fields=slug,count")
            .let { it as kotlinx.serialization.json.JsonArray }
        arr.mapNotNull {
            val o = it.jsonObject
            val c = o["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            if (c > 0) o["slug"]?.jsonPrimitive?.content else null
        }.toSet()
    }.getOrNull()

    private suspend fun libraryCovers(): Map<String, String> {
        val m = HashMap<String, String>()
        runCatching {
            doc("$baseUrl/library/").select(".entry-content figure a[href]").forEach { a ->
                val p = pathOf(a.attr("href"))
                val c = imageSrc(a.selectFirst("img"))
                if (p != null && c != null) m[p] = c
            }
        }
        return m
    }

    private suspend fun listNovels(): List<Entry> {
        val withChapters = storiesWithChapters()
        val covers = libraryCovers()
        val home = doc("$baseUrl/")
        val seen = HashSet<String>()
        val out = ArrayList<Entry>()
        for (a in home.select("#primary-menu li.menu-item-has-children > ul.sub-menu a[href]")) {
            val path = pathOf(a.attr("href")) ?: continue
            val title = clean(a.text())
            if (title.isEmpty() || !seen.add(path)) continue
            val slug = path.trim('/').removeSuffix("-toc")
            if (withChapters != null && slug !in withChapters) { seen.remove(path); continue }
            out.add(Entry(id, path, title, covers[path], ContentType.NOVEL))
        }
        return out
    }

    override suspend fun popular(page: Int) =
        if (page > 1) PagedResult(emptyList(), false) else PagedResult(listNovels(), false)

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val t = query.lowercase().trim()
        return PagedResult(listNovels().filter { it.title.lowercase().contains(t) }, false)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val content = d.selectFirst("article .entry-content") ?: error("CraneAnime: novel page not found.")
        val title = clean(d.selectFirst("h1.entry-title")?.text().orEmpty()).ifEmpty { entryId }
        var author: String? = null
        var artist: String? = null
        val summary = ArrayList<String>()
        var reachedLinks = false
        for (p in content.select("p")) {
            val text = clean(p.text())
            val a = Regex("^Author\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)
            val ar = Regex("^Artist\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)
            when {
                a != null -> author = a.groupValues[1]
                ar != null -> artist = ar.groupValues[1]
                p.select("a[href]").isNotEmpty() -> reachedLinks = true
                !reachedLinks && text.isNotEmpty() &&
                    !Regex("^(?:English|Japanese|Englisth) Title\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                    summary.add(text)
            }
        }
        return EntryDetails(
            entry = Entry(id, entryId, title, imageSrc(content.selectFirst("img")), ContentType.NOVEL),
            description = summary.joinToString("\n\n").take(2500).ifEmpty { null },
            authors = listOfNotNull(author, artist),
            genres = emptyList(),
            status = null,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val d = doc(absPath(entryId))
        val content = d.selectFirst("article .entry-content") ?: return emptyList()
        val own = pathOf(absPath(entryId))
        val seen = HashSet<String>()
        val out = ArrayList<Episode>()
        for (a in content.select("a[href]")) {
            val path = pathOf(a.attr("href"))
            val name = clean(a.text())
            if (path == null || name.isEmpty() || path == "/" || path == own ||
                notChapter.matches(path) || !seen.add(path)
            ) continue
            out.add(Episode(path, name, null, null))
        }
        return out
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.selectFirst("article .entry-content") ?: return emptyList()
        if (content.select(".patreon-campaign-banner").isNotEmpty()) return emptyList()
        content.select(".wpulike, .wp-post-navigation").remove()
        for (p in content.select("p")) {
            val t = clean(p.text())
            if (Regex("^(?:TOC|Table of Contents)$", RegexOption.IGNORE_CASE).matches(t) ||
                Regex("\\[Early Access\\]", RegexOption.IGNORE_CASE).containsMatchIn(t)
            ) p.remove()
        }
        return paragraphsOf(content)
    }
}

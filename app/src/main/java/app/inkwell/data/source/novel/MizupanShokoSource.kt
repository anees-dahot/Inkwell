package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document

/** Port of LNReader's mizupanshoko plugin (WordPress.com blog, HTML scraping). */
class MizupanShokoSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "mizupanshoko"
    override val name = "Mizupan's Archives"
    override val baseUrl = "https://mizupanshoko.wordpress.com"
    override val lang = "en"

    private val chapterPath = Regex("""^/(\d{4})/(\d{2})/(\d{2})/[^/]+/$""")
    private val sectionHeading = Regex("""^(chapters|part\s*\d+)$""", RegexOption.IGNORE_CASE)
    private val navWords = Regex("""\[?\s*(previous|next|toc)\s*\]?""", RegexOption.IGNORE_CASE)
    private var catalogue: List<Entry>? = null

    private fun toPath(href: String): String {
        val raw = href.trim()
        val same = Regex("""^https?://mizupanshoko\.wordpress\.com(/.*)?$""", RegexOption.IGNORE_CASE).matchEntire(raw)
        if (Regex("""^https?://""", RegexOption.IGNORE_CASE).containsMatchIn(raw) && same == null) return raw
        val clean = (if (same != null) same.groupValues[1].ifEmpty { "/" } else raw).split('?', '#')[0]
        val path = if (clean.startsWith("/")) clean else "/$clean"
        return if (path.endsWith("/")) path else "$path/"
    }

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

    private fun content(d: Document) = d.selectFirst(".entry-content")

    private suspend fun novelPage(path: String): Document {
        val d = doc(baseUrl + path)
        d.select(".jp-relatedposts, .sharedaddy, script, style").remove()
        return d
    }

    private fun chapterLinks(d: Document) = content(d)?.select("a[href]").orEmpty()
        .filter { chapterPath.matches(toPath(it.attr("href"))) }

    private suspend fun catalogue(): List<Entry> {
        catalogue?.let { return it }
        val home = doc("$baseUrl/")
        val paths = home.select("li.menu-item > a").map { toPath(it.attr("href")) }
            .filter { it.startsWith("/") && it != "/" }.distinct()
        val out = ArrayList<Entry>()
        for (p in paths) {
            val d = runCatching { novelPage(p) }.getOrNull() ?: continue
            if (chapterLinks(d).isEmpty()) continue
            out.add(entryOf(p, d))
        }
        catalogue = out
        return out
    }

    private fun entryOf(path: String, d: Document): Entry {
        val og = d.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val cover = if (og.isNotEmpty() && !og.contains("/i/blank.jpg")) og else null
        return Entry(id, path, norm(d.selectFirst("h1.entry-title")?.text().orEmpty()), cover, ContentType.NOVEL)
    }

    override suspend fun popular(page: Int) =
        if (page > 1) PagedResult(emptyList(), false) else PagedResult(catalogue(), false)

    override suspend fun latest(page: Int) = popular(page)

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val all = catalogue()
        val byPath = all.associateBy { it.id }
        val d = runCatching { doc("$baseUrl/?s=" + java.net.URLEncoder.encode(query, "UTF-8")) }.getOrNull()
            ?: return PagedResult(emptyList(), false)
        val found = LinkedHashMap<String, Entry>()
        for (a in d.select("article")) {
            val classes = a.className()
            val paths = if (classes.contains("type-page")) {
                listOf(toPath(a.selectFirst(".entry-title a")?.attr("href").orEmpty()))
            } else {
                Regex("""\bcategory-[\w-]+""").findAll(classes).map { "/" + it.value.drop(9) + "/" }.toList()
            }
            for (p in paths) byPath[p]?.let { found.putIfAbsent(p, it) }
        }
        return PagedResult(found.values.toList(), false)
    }

    override suspend fun details(entryId: String): EntryDetails {
        val path = toPath(entryId)
        val d = novelPage(path)
        val lines = ArrayList<String>()
        for (el in content(d)?.select("p, h4").orEmpty()) {
            val text = norm(el.text())
            val hasLink = el.select("a[href]").any { chapterPath.matches(toPath(it.attr("href"))) }
            if (sectionHeading.matches(text) || hasLink) break
            if (text.isNotEmpty()) lines.add(text)
        }
        return EntryDetails(
            entry = entryOf(path, d),
            description = lines.joinToString("\n").takeIf { it.isNotEmpty() },
            authors = emptyList(),
            genres = emptyList(),
            status = null,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        val d = novelPage(toPath(entryId))
        val seen = HashSet<String>()
        val out = ArrayList<Episode>()
        for (a in chapterLinks(d)) {
            val p = toPath(a.attr("href"))
            if (!seen.add(p)) continue
            val m = chapterPath.matchEntire(p)!!
            val nm = norm(a.text())
            val num = Regex("""\b(?:Ch\.?|Chapter)\s*(\d+)""", RegexOption.IGNORE_CASE).find(nm)?.groupValues?.get(1)?.toFloatOrNull()
            out.add(
                Episode(
                    id = p,
                    name = nm,
                    number = num ?: (out.size + 1).toFloat(),
                    dateMillis = parseDate("${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}T00:00:00", "yyyy-MM-dd'T'HH:mm:ss"),
                )
            )
        }
        return out
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(baseUrl + toPath(episodeId))
        val c = content(d) ?: return emptyList()
        c.select("script, style, .sharedaddy, .jp-relatedposts").remove()
        for (p in c.select("p")) {
            val t = p.text()
            if (Regex("""\b(previous|next|toc)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) &&
                t.replace(navWords, "").trim().isEmpty()) p.remove()
        }
        return paragraphsOf(c)
    }
}

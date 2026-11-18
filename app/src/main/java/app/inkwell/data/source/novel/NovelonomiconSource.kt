package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Port of LNReader's novelonomicon plugin. The whole catalogue lives on one
 page (/novels/), a novel page lists every chapter, and locked chapters are
 read from the embedded `aguqChapterList` script.
 */
class NovelonomiconSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelonomicon"
    override val name = "Novelonomicon"
    override val baseUrl = "https://novelonomicon.com"
    override val lang = "en"

    private data class CatalogueEntry(
        val name: String,
        val path: String,
        val cover: String?,
        val status: String,
        val added: Long,
        val updated: Long,
    )

    private var catalogue: List<CatalogueEntry>? = null
    private val cachedChapters = HashMap<String, List<Episode>>()

    private fun toPath(url: String): String =
        url.replace("\\/", "/").removePrefix("$baseUrl/").trim('/')

    /** Lazy-loaded images keep the real URL in data-src; missing covers are a site placeholder. */
    private fun imageUrl(img: Element?): String? {
        val url = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src").orEmpty()
        return if (url.startsWith("http") && !url.contains("missingcover")) abs(url) else null
    }

    private suspend fun getCatalogue(): List<CatalogueEntry> {
        catalogue?.let { return it }
        val d = doc(absPath("novels/"))
        val novels = d.select(".ag-hub-card[data-ag-hub-type=\"novel\"]").mapNotNull { card ->
            val link = card.attr("data-ag-hub-link").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val novelName = card.selectFirst(".ag-hub-card-title")?.text()?.trim()
                .takeUnless { it.isNullOrEmpty() } ?: return@mapNotNull null
            val statusClass = card.selectFirst(".ag-novel-home-status")?.attr("class").orEmpty()
            CatalogueEntry(
                name = novelName,
                path = toPath(link),
                cover = imageUrl(card.selectFirst(".ag-hub-card-media img")),
                status = Regex("^.*ag-novel-status-(\\S+).*$").replace(statusClass) { it.groupValues[1] },
                added = card.attr("data-ag-hub-added").toLongOrNull() ?: 0L,
                updated = card.attr("data-ag-hub-updated").toLongOrNull() ?: 0L,
            )
        }
        check(novels.isNotEmpty()) { "$name: could not parse the novel catalogue" }
        catalogue = novels
        return novels
    }

    private suspend fun browse(pageNo: Int, sort: String, status: String): PagedResult<Entry> {
        // The whole catalogue is served on one page.
        if (pageNo > 1) return PagedResult(emptyList(), false)
        val novels = getCatalogue().filter { status == "all" || it.status == status }
        val sorted = when (sort) {
            "name" -> novels.sortedBy { it.name.lowercase() }
            "new" -> novels.sortedByDescending { it.added }
            else -> novels.sortedByDescending { it.updated }
        }
        return PagedResult(sorted.map { Entry(id, it.path, it.name, it.cover, ContentType.NOVEL) }, false)
    }

    // Default filters: sort "updated", status "all".
    override suspend fun popular(page: Int) = browse(page, "updated", "all")
    override suspend fun latest(page: Int) = browse(page, "updated", "all")

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        if (page > 1) return PagedResult(emptyList(), false)
        val term = query.trim().lowercase()
        val items = getCatalogue()
            .filter { it.name.lowercase().contains(term) }
            .map { Entry(id, it.path, it.name, it.cover, ContentType.NOVEL) }
        return PagedResult(items, false)
    }

    /** Decodes base64 without relying on platform decoders, like the TS plugin. */
    private fun decodeBase64(data: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (char in data.trimEnd('=')) {
            val value = alphabet.indexOf(char)
            if (value < 0) continue
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                sb.append(((buffer shr bits) and 0xff).toChar())
            }
        }
        return sb.toString()
    }

    /**
     * Chapter URLs still locked for non-members, from the `aguqChapterList`
     * script (base64 data: URL or inline text depending on the site optimiser).
     */
    private fun getLockedPaths(d: Document): Set<String> = runCatching {
        val script = d.selectFirst("#aguq-chapter-list-js-extra") ?: return@runCatching emptySet<String>()
        val src = script.attr("src")
        val base64 = Regex("^data:[^,]*;base64,(.*)$").find(src)?.groupValues?.get(1)
        val source = base64?.let { decodeBase64(it) } ?: script.html()
        val pattern = Regex("\"href\":\"([^\"]+)\",\"unlockAt\":(\\d+)")
        val now = System.currentTimeMillis()
        pattern.findAll(source).mapNotNull { m ->
            val unlockAt = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            if (unlockAt > now) toPath(m.groupValues[1]) else null
        }.toSet()
    }.getOrDefault(emptySet())

    override suspend fun details(entryId: String): EntryDetails {
        val d = doc(absPath(entryId))
        val title = d.selectFirst(".ag-novel-home-title")?.text()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: "Untitled"
        val cover = imageUrl(d.selectFirst(".ag-novel-home-cover img"))
        val genres = d.select(".ag-novel-home-info .ag-novel-home-tag").eachText()
            .map { it.trim() }.filter { it.isNotEmpty() }
        val summary = d.select(".ag-novel-home-synopsis-body > p")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

        var author: String? = null
        var status: String? = null
        d.select(".ag-novel-home-meta > div").forEach { meta ->
            val label = meta.selectFirst("dt")?.text()?.trim()?.lowercase()
            val value = meta.selectFirst("dd")?.text()?.trim()
            when (label) {
                "author" -> author = value?.takeUnless { it.isEmpty() }
                "status" -> status = when (value?.lowercase()) {
                    "ongoing" -> "Ongoing"
                    "completed" -> "Completed"
                    "hiatus" -> "On Hiatus"
                    "dropped" -> "Cancelled"
                    else -> null
                }
            }
        }

        val locked = getLockedPaths(d)
        val chapters = d.select(".ag-novel-chapter-card").mapNotNull { card ->
            val href = card.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = toPath(href)
            if (path in locked) return@mapNotNull null
            val timestamp = card.selectFirst("[data-ag-timestamp]")?.attr("data-ag-timestamp")?.toLongOrNull() ?: 0L
            val number = card.attr("data-number").toFloatOrNull()
            val cardName = card.selectFirst(".ag-novel-chapter-card-num")?.text()?.trim()
                .takeUnless { it.isNullOrEmpty() }
                ?: card.attr("title").takeUnless { it.isBlank() }
                ?: path
            Episode(
                id = path,
                name = cardName,
                number = number,
                dateMillis = if (timestamp > 0) timestamp * 1000 else null,
            )
        }.also { cachedChapters[entryId] = it }

        return EntryDetails(
            entry = Entry(id, entryId, title, cover, ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = listOfNotNull(author),
            genres = genres,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    override suspend fun chapterText(episodeId: String): List<String> {
        val d = doc(absPath(episodeId))
        val content = d.selectFirst(".ag-content-body") ?: return emptyList()
        if (content.selectFirst(".aguq-paywall") != null) return emptyList()
        // Ad slots and scripts sit between the paragraphs.
        content.select(".ag-ad-zone, ins, script, style, iframe, noscript").remove()
        content.select("img").forEach { img ->
            img.attr("data-src").takeIf { it.isNotBlank() }?.let { img.attr("src", it) }
        }
        return paragraphsOf(content)
    }
}

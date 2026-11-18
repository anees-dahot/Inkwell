package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Port of LNReader's novelupdates plugin. Novel Updates sits behind
 * Cloudflare; blocked responses (HTTP 403/503, or an interstitial defining
 * window._cf_chl_opt) are surfaced as errors pointing at a WebView.
 *
 * Chapter bodies come from third-party hosts; this port implements the
 * plugin's generic WordPress/Blogspot platform detection plus its body
 * fallback (the ~30 per-site custom handlers are not ported).
 */
class NovelUpdatesSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "novelupdates"
    override val name = "Novel Updates"
    override val baseUrl = "https://www.novelupdates.com"
    override val lang = "en"

    private val cachedChapters = HashMap<String, List<Episode>>()

    private suspend fun fetchWithUrl(url: String): Pair<String, String> =
        client.newCall(request(url)).execute().use { r ->
            check(r.isSuccessful) { "$name HTTP ${r.code}" }
            val body = r.body?.string().orEmpty()
            check(!body.contains("_cf_chl_opt")) {
                "$name: Cloudflare protection detected; open the source in a WebView first."
            }
            body to r.request.url.toString()
        }

    private suspend fun fetchDoc(url: String): Document {
        val (body, finalUrl) = fetchWithUrl(url)
        return Jsoup.parse(body, finalUrl)
    }

    private fun parseNovelCards(d: Document): List<Entry> =
        d.select("div.search_main_box_nu").mapNotNull { box ->
            val novelUrl = box.selectFirst(".search_title > a")?.attr("href")
                ?: return@mapNotNull null
            Entry(
                sourceId = id,
                id = novelUrl.removePrefix("$baseUrl/").removePrefix(baseUrl).trim('/'),
                title = box.selectFirst(".search_title > a")?.text()?.trim() ?: "Untitled",
                coverUrl = abs(box.selectFirst("img")?.attr("src")),
                type = ContentType.NOVEL,
            )
        }

    // Default filters: sort "popmonth".
    override suspend fun popular(page: Int): PagedResult<Entry> {
        val items = parseNovelCards(fetchDoc(absPath("series-ranking/") + "?rank=popmonth&pg=$page"))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        val items = parseNovelCards(fetchDoc(absPath("series-finder/") + "?sf=1&sort=sdate&order=desc&pg=$page"))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        // Split by '*' and search with the longest fragment, like the TS plugin.
        val term = query.split('*').maxByOrNull { it.length } ?: query
        val url = absPath("series-finder/").toHttpUrl().newBuilder()
            .addQueryParameter("sf", "1")
            .addQueryParameter("sh", term.replace(Regex("[‘’]"), "'"))
            .addQueryParameter("sort", "srank")
            .addQueryParameter("order", "asc")
            .addQueryParameter("pg", page.toString())
            .build()
        val items = parseNovelCards(fetchDoc(url.toString()))
        return PagedResult(items, items.isNotEmpty())
    }

    override suspend fun details(entryId: String): EntryDetails {
        val d = fetchDoc(absPath(entryId))

        val title = d.selectFirst(".seriestitlenu")?.text()?.trim()
            .takeUnless { it.isNullOrEmpty() } ?: "Untitled"
        val cover = abs(d.selectFirst(".wpb_wrapper img")?.attr("src"))
        val authors = d.select("#authtag").eachText().map { it.trim() }.filter { it.isNotEmpty() }
        val genres = d.select("#seriesgenre > a").eachText().map { it.trim() }.filter { it.isNotEmpty() }
        val status = if (d.selectFirst("#editstatus")?.text()?.contains("Ongoing") == true) "Ongoing" else "Completed"
        val type = d.selectFirst("#showtype")?.text()?.trim().orEmpty()
        val summary = d.selectFirst("#editdescription")?.text()?.trim().orEmpty()
            .let { if (type.isNotEmpty()) "$it\n\nType: $type" else it }

        // Series pages no longer reliably expose input#mypostid; fall back to
        // the WordPress body class / sid links so chapter AJAX still works.
        val novelId = d.selectFirst("input#mypostid")?.attr("value")?.takeIf { it.isNotBlank() }
            ?: Regex("\\bpostid-(\\d+)\\b").find(d.body().attr("class"))?.groupValues?.get(1)
            ?: d.selectFirst("""a[href*="sid="]""")?.attr("href")
                ?.let { Regex("[?&]sid=(\\d+)").find(it)?.groupValues?.get(1) }
            ?: throw IllegalStateException("$name: novel id not found on series page.")

        val chaptersDoc = formPost(
            "$baseUrl/wp-admin/admin-ajax.php",
            mapOf(
                "action" to "nd_getchapters",
                "mygrr" to "0",
                "mypostid" to novelId,
            ),
        )
        check(!chaptersDoc.html().contains("_cf_chl_opt")) {
            "$name: Cloudflare protection on the chapter list; open in a WebView first."
        }
        check(chaptersDoc.text().trim() != "0") {
            "$name: refused the chapter list request; open in a WebView first."
        }

        val chapters = chaptersDoc.select("li.sp_li_chp").mapNotNull { li ->
            val chapterName = li.text()
                .replaceFirst("v", "volume ")
                .replaceFirst("c", " chapter ")
                .replaceFirst("part", "part ")
                .replaceFirst("ss", "SS")
                .replace(Regex("\\b\\w")) { it.value.uppercase() }
                .trim()
            val chapterHref = li.selectFirst("a")?.nextElementSibling()?.attr("href")
                ?: return@mapNotNull null
            val absolute = if (chapterHref.startsWith("http")) chapterHref else "https:$chapterHref"
            val path = absolute.removePrefix("$baseUrl/").removePrefix(baseUrl)
            Episode(id = path, name = chapterName, number = null, dateMillis = null)
        }.reversed()
        cachedChapters[entryId] = chapters

        return EntryDetails(
            entry = Entry(id, entryId, title, cover, ContentType.NOVEL),
            description = summary.ifBlank { null },
            authors = authors,
            genres = genres,
            status = status,
        )
    }

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    // ---- chapter body ------------------------------------------------------

    private fun matches(d: Document, selector: String, attr: String?, regex: Regex): Boolean =
        d.select(selector).any { el ->
            val value = attr?.let { el.attr(it) } ?: (el.html() + el.text())
            value.lowercase().let { regex.containsMatchIn(it) }
        }

    override suspend fun chapterText(episodeId: String): List<String> {
        val url = absPath(episodeId)
        val (body, finalUrl) = fetchWithUrl(url)
        val d = Jsoup.parse(body, finalUrl)

        // Handle CAPTCHA cases
        val blockedTitles = listOf(
            "bot verification", "just a moment...", "redirecting...",
            "un instant...", "you are being redirected...",
        )
        check(d.title().trim().lowercase() !in blockedTitles) {
            "$name: captcha detected on the chapter host; open in a WebView."
        }

        val isWordPress = listOf(
            matches(d, "meta[name=\"generator\"]", "content", Regex("wordpress|site kit")),
            matches(d, "link, script, img", "src", Regex("/wp-content/|/wp-includes/")),
            matches(d, "link", "href", Regex("/wp-content/|/wp-includes/")),
            matches(d, "link[rel=\"https://api.w.org/\"]", "href", Regex(".*")),
            matches(d, "link[rel=\"EditURI\"]", "href", Regex("xmlrpc\\.php")),
            matches(d, "body", "class", Regex("wp-admin|wp-custom-logo|logged-in")),
            matches(d, "script", null, Regex("wp-embed|wp-emoji|wp-block")),
        ).any { it }

        val isBlogspot = listOf(
            matches(d, "meta[name=\"generator\"]", "content", Regex("blogger")),
            matches(d, "meta[name=\"google-adsense-platform-domain\"]", "content", Regex("blogspot")),
            matches(d, "link[rel=\"alternate\"]", "href", Regex("blogger\\.com/feeds|blogspot\\.com/feeds")),
            matches(d, "link", "href", Regex("www\\.blogger\\.com/static|www\\.blogger\\.com/dyn-css")),
            matches(d, "script", null, Regex("_WidgetManager\\._Init|_WidgetManager\\._RegisterWidget")),
        ).any { it }

        var chapterText: String?
        var chapterTitle: String? = null
        if (isWordPress || isBlogspot) {
            val bloat = if (isWordPress) {
                listOf(
                    ".ad", ".author-avatar", ".chapter-warning", ".entry-meta", ".ezoic-ad",
                    ".mb-center", ".modern-footnotes-footnote__note", ".patreon-widget",
                    ".post-cats", ".pre-bar", ".sharedaddy", ".sidebar", ".swg-button-v2-light",
                    ".wp-block-buttons", ".wp-dark-mode-switcher", ".wp-next-post-navi",
                    "#hpk", "#jp-post-flair", "#textbox",
                )
            } else {
                listOf(".button-container", ".ChapterNav", ".ch-bottom", ".separator")
            }
            d.select(bloat.joinToString(", ")).remove()

            val titleSelectors = if (isWordPress) {
                listOf(".entry-title", ".chapter__title", ".title-content", ".wp-block-post-title",
                    ".title_story", "#chapter-heading", ".chapter-title", "head title",
                    "h1:first-of-type", "h2:first-of-type", ".active")
            } else {
                listOf(".entry-title", ".post-title", "head title")
            }
            chapterTitle = titleSelectors.firstNotNullOfOrNull { sel ->
                d.select(sel).first()?.text()?.trim()?.takeIf { it.isNotEmpty() }
            }
            val subtitle = d.selectFirst(".cat-series")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: d.selectFirst("h1.leading-none ~ span")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: d.selectFirst(".breadcrumb .active")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            if (subtitle != null) chapterTitle = subtitle

            val contentSelectors = if (isWordPress) {
                listOf(".chapter__content", ".entry-content", ".text_story", ".post-content",
                    ".contenta", ".single_post", ".main-content", ".reader-content", "#content",
                    "#the-content", "article.post", ".chp_raw")
            } else {
                listOf(".content-post", ".entry-content", ".post-body")
            }
            val content = contentSelectors.firstNotNullOfOrNull { sel ->
                d.select(sel).first()?.let { el -> if (el.text().trim().length > 50) el.html() else null }
            }
            chapterText = if (chapterTitle != null && content != null) {
                "<h2>$chapterTitle</h2><hr><br>$content"
            } else {
                content
            }
        } else {
            chapterText = null
        }

        // Fallback content extraction
        if (chapterText == null) {
            d.select("nav, header, footer, .hidden").remove()
            chapterText = d.body().html()
        }

        // Convert relative URLs to absolute
        val origin = finalUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        chapterText = chapterText.replace("href=\"/", "href=\"$origin/")

        val cleaned = Jsoup.parse(chapterText)
        cleaned.select("noscript").remove()
        cleaned.select("img").forEach { img ->
            img.attr("data-lazy-src")?.takeIf { it.isNotEmpty() }?.let { img.attr("src", it) }
            img.attr("data-lazy-srcset")?.takeIf { it.isNotEmpty() }?.let { img.attr("srcset", it) }
            img.removeClass("lazyloaded")
        }
        return paragraphsOf(cleaned.body())
    }
}

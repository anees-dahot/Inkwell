package app.inkwell.data.source.novel

import app.inkwell.data.model.*
import app.inkwell.data.source.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.Normalizer

/**
 * Port of LNReader's Baka-Tsuki plugin. The catalogue is the wiki's own
 * "Light novel (English)" category, fetched through the MediaWiki API
 * (`project/api.php`); chapter lists come from the parsed project page and
 * chapter text from the API's rendered HTML.
 */
class BakaTsukiSource(client: OkHttpClient) : ScraperNovelSource(client) {
    override val id = "bakatsuki"
    override val name = "Baka-Tsuki"
    override val baseUrl = "https://www.baka-tsuki.org/project"
    override val lang = "en"

    private val apiUrl get() = "$baseUrl/api.php"
    private val pageSize = 40

    /** The wiki's English light novel listing, titles verbatim. */
    private val catalogueCategory = "Light novel (English)"

    /** Read for status only, not for catalogue membership. */
    private val statusCategories = mapOf(
        "Active Projects" to "Ongoing",
        "Completed Project" to "Completed",
        "Stalled Projects" to "On Hiatus",
        "Inactive Projects" to "Inactive",
        "Hosted Projects" to "Ongoing",
    )

    /** Translation siblings ("Absolute Duo - Français", "… (Swedish)"). */
    private val nonEnglishPattern = Regex(
        "(?:^|[\\s(\\[~\\-_])(?:fran[cç]ais|espa[nñ]ol|spanish|french|german|deutsch|italian|italiano|" +
            "polski|polish|portugu[eê]s|portuguese|brazilian|russian|swedish|svenska|indonesia|indonesian|" +
            "vietnamese|chinese|thai|turkish|t[uü]rk[cç]e|arabic|korean|japanese|dutch|nederlands|hungarian|" +
            "magyar|romanian|greek|hebrew|persian|farsi|czech|bulgarian|serbian|croatian|finnish|danish|" +
            "norwegian|ukrainian|latvian|lithuanian|estonian|slovak|slovenian|catalan|filipino|tagalog|malay|" +
            "hindi|bengali|tamil|urdu)(?:$|[\\s)\\]~\\-_:])" +
            "|[\\s\\-~_](?:FR|PL|ES|DE|IT|PT|RU|VN|CN|TH|TR|AR|KR|JP|NL|HU|RO|GR|CZ|BG|RS|HR|FI|DK|NO|UA|SE|ID)$",
        RegexOption.IGNORE_CASE,
    )

    /** Sub-pages of a project (chapters, illustrations, staff pages). */
    private val subPagePattern = Regex(
        "(?:^|[\\s(\\[~\\-_])(?:Volume|Vol\\.?|Chapter|Part|Book|Full[_ ]Text|Illustrations?|Prologue|Epilogue|" +
            "Afterword|Side[_ ]Stor(?:y|ies)|Short[_ ]Stor(?:y|ies)|Names[_ ]and[_ ]Terminology|Registration|" +
            "Registry|Updates?|Staff|Guidelines|Format|Historique|Enregistrement)",
        RegexOption.IGNORE_CASE,
    )

    /** Project scaffolding sharing the chapter prefix but not readable content. */
    private val nonChapterPattern = Regex(
        "^(?:Registration|Registry|Archives?|Staff|Guidelines?|Updates?|Format|Names[\\s_]and[\\s_]Terminology|" +
            "Terminology|Translation|Translators?|Editors?|Discussion|Sandbox|Talk|Status|References?|Credits?|" +
            "To-?do|Feedback|Preview|Announcements?)",
        RegexOption.IGNORE_CASE,
    )

    private val genrePattern = Regex("^Category:Genre\\s*-\\s*(.+)$", RegexOption.IGNORE_CASE)

    /** Publisher imprints and structural categories, never an author. */
    private val nonAuthorCategoryPattern = Regex(
        "^(?:Light novel|Web novel|Original light novel|Visual novel|Audio novel|Genre|Hosted|Active|Completed|" +
            "Inactive|Stalled|Teaser|Licensed|Pages? |Candidates|Articles|Project|Series|Novel|Manga)|" +
            "(?:Bunko|Books|Publishing|Shoten|Shuppan|Kadokawa|Shueisha|Kodansha|Shogakukan|ASCII|Media Factory|" +
            "Enterbrain|Hobby Japan|Overlap|SoftBank|Fujimi|Dengeki|Gagaga|Sneaker|Ichijinsha|Earth Star|" +
            "Micro Magazine|TO Books|Alphapolis|Famitsu|Fantasia|Dash|Kobunsha|Takeshobo|Houbunsha|Media Works)",
        RegexOption.IGNORE_CASE,
    )

    /** Catalogue entries with no sub-pages when last surveyed (suspicion only). */
    private val possiblyEmpty = setOf(
        "Allison", "Ark", "Clotaku Club!", "CtG—Zero Kara Sodateru Dennou Shoujo", "Etsusa Bridge",
        "Famima!", "Hikaru ga Chikyuu ni Itakoro......", "Hitotsu no Tairiki no Monogatari",
        "I'm a High School Boy and a Bestselling Light Novel author, strangled by my female classmate who is my junior and a voice actress",
        "Kamisu Reina Series", "Kill No More", "Lillia to Treize", "Maru-MA", "Meg to Seron",
        "Mushi to Medama", "Ojamajo Doremi 16",
        "Onii-chan Dakedo Ai Sae Areba Kankei Nai yo ne—",
        "Ore ga Ojou-Sama Gakkou ni ‘Shomin Sample’ Toshite Usarareta Ken",
        "Ore no Kanojo to Osananajimi ga Shuraba Sugiru ~Brazilian Portuguese~",
        "Ore no Nounai Sentakushi ga, Gakuen Love Come o Zenryoku de Jama Shiteiru",
        "Puppetmaster", "Remembrances for a certain pilot", "Sayonara Piano Sonata",
        "Sekai Ichi no Imouto-sama", "Sword of the Emperor",
        "Tabi ni Deyou, Horobiyuku Sekai no Hate Made", "Tsuki Tsuki!", "Tsukumodo Antique Shop",
        "Una Simple Revisión en Español", "Vamp!",
        "We Don't Open Anywhere -There are no facts, only interpretations.-", "White Album 2 Omake",
    )

    private val contentCache = HashMap<String, Boolean>()
    private val statusByTitle = HashMap<String, String>()
    private val catalogueSet = HashSet<String>()

    @Volatile
    private var catalogueCache: List<String>? = null

    private val cachedChapters = HashMap<String, List<Episode>>()

    // ---- API plumbing ------------------------------------------------------

    private suspend fun query(params: Map<String, String>): JsonObject {
        val url = apiUrl.toHttpUrl().newBuilder()
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        val root = json(url).jsonObject
        (root["error"] as? JsonObject)?.let { err ->
            val info = err["info"]?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }
            throw IllegalStateException("Baka-Tsuki API error: $info")
        }
        return root
    }

    /** Page titles use underscores in URLs but spaces in the API. */
    private fun toTitle(path: String) = path.replace('_', ' ').trim()
    private fun toPath(title: String) = title.replace(' ', '_')

    private fun jsonObjArray(root: JsonObject, vararg keys: String): JsonArray? {
        var cur: Any? = root
        for (k in keys) cur = (cur as? JsonObject)?.get(k) ?: return null
        return cur as? JsonArray
    }

    private suspend fun categoryMembers(category: String): List<String> {
        val titles = mutableListOf<String>()
        var cmcontinue: String? = null
        do {
            val params = mutableMapOf(
                "action" to "query",
                "list" to "categorymembers",
                "cmtitle" to "Category:$category",
                "cmnamespace" to "0",
                "cmtype" to "page",
                "cmlimit" to "500",
            )
            cmcontinue?.let { params["cmcontinue"] = it }
            val json = query(params)
            jsonObjArray(json, "query", "categorymembers")?.forEach { el ->
                (el as? JsonObject)?.get("title")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }?.let { titles.add(it) }
            }
            cmcontinue = (json["continue"] as? JsonObject)?.get("cmcontinue")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }
        } while (cmcontinue != null)
        return titles
    }

    // ---- catalogue ---------------------------------------------------------

    private suspend fun getCatalogue(): List<String> {
        catalogueCache?.let { return it }
        val titles = LinkedHashSet<String>()
        for (title in categoryMembers(catalogueCategory)) {
            if (!subPagePattern.containsMatchIn(title)) titles.add(title)
        }
        for ((category, status) in statusCategories) {
            for (title in categoryMembers(category)) {
                statusByTitle.putIfAbsent(title, status)
            }
        }
        val sorted = titles.sorted()
        catalogueSet.clear()
        catalogueSet.addAll(sorted)
        catalogueCache = sorted
        return sorted
    }

    /** Attaches cover thumbnails to a slice of titles; 50 per request. */
    private suspend fun withCovers(titles: List<String>): List<Entry> {
        val novels = titles.map { Entry(id, toPath(it), it, null, ContentType.NOVEL) }.toMutableList()
        var i = 0
        while (i < titles.size) {
            val batch = titles.subList(i, minOf(i + 50, titles.size))
            runCatching {
                val json = query(
                    mapOf(
                        "action" to "query",
                        "titles" to batch.joinToString("|"),
                        "prop" to "pageimages",
                        "piprop" to "thumbnail",
                        "pithumbsize" to "300",
                        "pilimit" to "50",
                    ),
                )
                val covers = HashMap<String, String>()
                jsonObjArray(json, "query", "pages")?.forEach { el ->
                    val page = el as? JsonObject ?: return@forEach
                    val title = page["title"]?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull } ?: return@forEach
                    val thumb = (page["thumbnail"] as? JsonObject)?.get("source")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }
                    if (thumb != null) covers[title] = thumb
                }
                for (idx in novels.indices) {
                    covers[novels[idx].title]?.let { novels[idx] = novels[idx].copy(coverUrl = abs(it)) }
                }
            }
            i += 50
        }
        return novels
    }

    // ---- browse ------------------------------------------------------------

    /** "Latest" = recently edited pages, rolled up to their parent project. */
    private suspend fun latestTitles(): List<String> {
        val catalogue = getCatalogue()
        val json = query(
            mapOf(
                "action" to "query",
                "list" to "recentchanges",
                "rcnamespace" to "0",
                "rclimit" to "500",
                "rctype" to "edit|new",
                "rcprop" to "title",
            ),
        )
        val seen = mutableSetOf<String>()
        val ordered = mutableListOf<String>()
        jsonObjArray(json, "query", "recentchanges")?.forEach { el ->
            val title = (el as? JsonObject)?.get("title")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull } ?: return@forEach
            val parent = title.substringBefore(':').trim()
            val project = when {
                catalogueSet.contains(title) -> title
                catalogueSet.contains(parent) -> parent
                else -> null
            } ?: return@forEach
            if (seen.add(project)) ordered.add(project)
        }
        // Pad with the catalogue so the list never dead-ends on a quiet wiki.
        for (title in catalogue) if (seen.add(title)) ordered.add(title)
        return ordered
    }

    override suspend fun popular(page: Int): PagedResult<Entry> {
        val titles = getCatalogue()
        val start = (page - 1) * pageSize
        if (start >= titles.size) return PagedResult(emptyList(), false)
        val slice = titles.subList(start, minOf(start + pageSize, titles.size))
        return PagedResult(withCovers(slice), start + pageSize < titles.size)
    }

    override suspend fun latest(page: Int): PagedResult<Entry> {
        val titles = latestTitles()
        val start = (page - 1) * pageSize
        if (start >= titles.size) return PagedResult(emptyList(), false)
        val slice = titles.subList(start, minOf(start + pageSize, titles.size))
        return PagedResult(withCovers(slice), start + pageSize < titles.size)
    }

    // ---- search ------------------------------------------------------------

    private fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        return decomposed.replace(Regex("[̀-ͯ]"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    /** Title-first relevance, so partial and out-of-order queries behave well. */
    private fun score(title: String, term: String): Int {
        val a = normalize(title)
        val b = normalize(term)
        if (a.isEmpty() || b.isEmpty()) return 0
        if (a == b) return 1000
        if (a.startsWith(b)) return 900 - minOf(a.length - b.length, 99)
        if (a.contains(b)) return 800 - minOf(a.length - b.length, 99)
        val queryTokens = b.split(' ').filter { it.isNotEmpty() }
        val titleTokens = a.split(' ').filter { it.isNotEmpty() }
        val matched = queryTokens.count { token -> titleTokens.any { it.startsWith(token) } }
        if (matched == queryTokens.size) return 700 - minOf(a.length, 99)
        if (matched > 0) return (matched * 500.0 / queryTokens.size).toInt()
        return 0
    }

    override suspend fun search(query: String, page: Int): PagedResult<Entry> {
        val term = query.trim()
        if (term.isEmpty()) return PagedResult(emptyList(), false)

        // Deeper pages fall through to the wiki's full-text search.
        if (page > 1) {
            val json = query(
                mapOf(
                    "action" to "query",
                    "list" to "search",
                    "srsearch" to term,
                    "srnamespace" to "0",
                    "srlimit" to pageSize.toString(),
                    "sroffset" to ((page - 1) * pageSize).toString(),
                ),
            )
            val titles = jsonObjArray(json, "query", "search").orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("title")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull } }
                .filter { isNovelTitle(it) }
            return PagedResult(withCovers(dropEmptyProjects(titles)), titles.size >= pageSize)
        }

        val catalogue = runCatching { getCatalogue() }.getOrDefault(emptyList())
        val scored = LinkedHashMap<String, Int>()
        for (title in catalogue) {
            val s = score(title, term)
            if (s > 0) scored[title] = s
        }

        // Network passes add anything the catalogue missed, at a lower base score.
        val prefix = runCatching {
            query(
                mapOf(
                    "action" to "query", "list" to "prefixsearch", "pssearch" to term,
                    "psnamespace" to "0", "pslimit" to "50",
                ),
            )
        }.getOrNull()
        val fullText = runCatching {
            query(
                mapOf(
                    "action" to "query", "list" to "search", "srsearch" to term,
                    "srnamespace" to "0", "srlimit" to "50",
                ),
            )
        }.getOrNull()

        val remote = buildList {
            jsonObjArray(prefix ?: JsonObject(emptyMap()), "query", "prefixsearch")?.forEach { el ->
                (el as? JsonObject)?.get("title")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }?.let { add(it) }
            }
            jsonObjArray(fullText ?: JsonObject(emptyMap()), "query", "search")?.forEach { el ->
                (el as? JsonObject)?.get("title")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }?.let { add(it) }
            }
        }
        for (title in remote) {
            if (!isNovelTitle(title) || scored.containsKey(title)) continue
            val s = score(title, term)
            if (s > 0) scored[title] = s - 50
        }

        val ranked = scored.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(pageSize)
            .map { it.key }
        return PagedResult(withCovers(dropEmptyProjects(ranked)), false)
    }

    private fun isNovelTitle(title: String): Boolean {
        if (catalogueSet.contains(title)) return true
        // Outside the catalogue, any colon is treated as a sub-page marker.
        if (title.contains(':')) return false
        // Translation siblings are parenthesised ("Fate/Zero (Biełaruś)").
        if (Regex("\\([^)]+\\)\\s*$").containsMatchIn(title)) return false
        return !subPagePattern.containsMatchIn(title) && !nonEnglishPattern.containsMatchIn(title)
    }

    /** One cheap existence probe, only for titles already under suspicion. */
    private suspend fun hasReadableContent(title: String): Boolean {
        val prefix = "${title.substringBefore(':').trim()}:"
        return try {
            val json = query(
                mapOf(
                    "action" to "query",
                    "list" to "allpages",
                    "apprefix" to prefix,
                    "apnamespace" to "0",
                    "aplimit" to "1",
                ),
            )
            val hasContent = jsonObjArray(json, "query", "allpages").orEmpty().isNotEmpty()
            contentCache[title] = hasContent
            hasContent
        } catch (_: Exception) {
            true // Never hide a novel because a probe failed.
        }
    }

    private suspend fun dropEmptyProjects(titles: List<String>): List<String> {
        val probeBudget = 12
        var probes = 0
        val verdicts = titles.map { title ->
            if (catalogueSet.contains(title) && !possiblyEmpty.contains(title)) {
                true
            } else {
                contentCache[title] ?: (probes < probeBudget && run {
                    probes++
                    hasReadableContent(title)
                })
            }
        }
        return titles.filterIndexed { index, _ -> verdicts[index] }
    }

    // ---- details -----------------------------------------------------------

    override suspend fun episodes(entryId: String): List<Episode> {
        cachedChapters[entryId]?.let { return it }
        details(entryId)
        return cachedChapters[entryId].orEmpty()
    }

    override suspend fun details(entryId: String): EntryDetails {
        val requestedTitle = toTitle(entryId)

        val metaJson = query(
            mapOf(
                "action" to "query",
                "titles" to requestedTitle,
                "prop" to "extracts|pageimages|categories",
                "explaintext" to "1",
                "exsectionformat" to "raw",
                "piprop" to "thumbnail",
                "pithumbsize" to "400",
                "cllimit" to "max",
                "clshow" to "!hidden",
                "redirects" to "1",
            ),
        )
        val page = jsonObjArray(metaJson, "query", "pages")?.firstOrNull() as? JsonObject
        if (page == null || page.get("missing") != null) {
            return EntryDetails(
                entry = Entry(id, entryId, requestedTitle, null, ContentType.NOVEL),
                description = "This project is not available on Baka-Tsuki.",
                authors = emptyList(),
                genres = emptyList(),
                status = null,
            )
        }

        val pageTitle = page["title"]?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull } ?: requestedTitle
        val cover = (page["thumbnail"] as? JsonObject)?.get("source")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }
        val categories = jsonObjArray(page, "categories").orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("title")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull } }

        val genres = categories.mapNotNull { genrePattern.find(it)?.groupValues?.get(1)?.trim() }
        val author = resolveAuthor(categories)
        val chapters = parseChapterList(pageTitle)

        return EntryDetails(
            entry = Entry(id, entryId, pageTitle, abs(cover), ContentType.NOVEL),
            description = extractSummary(page["extract"]?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }),
            authors = listOfNotNull(author),
            genres = genres,
            status = resolveStatus(categories, pageTitle),
        ).also { cachedChapters[entryId] = chapters }
    }

    /**
     * Project pages open with wiki housekeeping; prefer an explicitly titled
     * synopsis section, else the first paragraph that isn't boilerplate.
     */
    private fun extractSummary(extract: String?): String? {
        if (extract.isNullOrBlank()) return null
        val boilerplate = Regex(
            "^(?:only available to registered users|register here|please read|this project|the project|" +
                "as of |note:|warning:|attention|status:|translation|editing|recruit)" +
                "|available in the following languages|this project has been" +
                "|has been (?:restored|licensed|removed|discontinued|taken down)|do you (?:follow|want)",
            RegexOption.IGNORE_CASE,
        )
        val sections = extract.split(Regex("^\\s*=+\\s*(.+?)\\s*=+\\s*$", RegexOption.MULTILINE))
        // split() yields [lead, heading, body, heading, body, ...]
        var i = 1
        while (i < sections.size) {
            if (Regex("synopsis|summary|story|plot|description|about", RegexOption.IGNORE_CASE)
                .containsMatchIn(sections[i])
            ) {
                cleanParagraphs(sections.getOrNull(i + 1), boilerplate)?.let { return it }
            }
            i += 2
        }
        return cleanParagraphs(sections.firstOrNull(), boilerplate)
    }

    private fun cleanParagraphs(text: String?, boilerplate: Regex): String? {
        if (text == null) return null
        val kept = text.split(Regex("\\n+"))
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length > 40 && !boilerplate.containsMatchIn(it) }
        if (kept.isEmpty()) return null
        val summary = kept.joinToString("\n\n")
        return if (summary.length > 1500) summary.take(1497) + "..." else summary
    }

    private fun resolveStatus(categories: List<String>, title: String): String {
        for (category in categories) {
            statusCategories[category.removePrefix("Category:")]?.let { return it }
        }
        return statusByTitle[title] ?: "Unknown"
    }

    /** An omitted author is correct; a guessed one is not. */
    private fun resolveAuthor(categories: List<String>): String? {
        for (category in categories) {
            val name = category.removePrefix("Category:").trim()
            if (genrePattern.containsMatchIn(category)) continue
            if (nonAuthorCategoryPattern.containsMatchIn(name)) continue
            if (Regex("\\d").containsMatchIn(name)) continue
            if (name.split(Regex("\\s+")).size < 2) continue
            return name
        }
        return null
    }

    // ---- chapter list ------------------------------------------------------

    private data class Candidate(val title: String, val name: String, val volume: String, val isFullText: Boolean)

    private suspend fun parseChapterList(novelTitle: String): List<Episode> {
        val json = runCatching {
            query(
                mapOf(
                    "action" to "parse",
                    "page" to novelTitle,
                    "prop" to "text",
                    "disableeditsection" to "1",
                    "disabletoc" to "1",
                    "redirects" to "1",
                ),
            )
        }.getOrNull()
        val html = (json?.get("parse") as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull } ?: return emptyList()

        val d = Jsoup.parseBodyFragment(html)
        val fullTitle = novelTitle.replace('_', ' ')
        val shortTitle = fullTitle.substringBefore(':').trim()
        val prefixes = buildList {
            add("$fullTitle:")
            if (shortTitle.isNotEmpty() && shortTitle != fullTitle) add("$shortTitle:")
        }

        val seen = mutableSetOf<String>()
        val candidates = mutableListOf<Candidate>()
        val volumeNumber = Regex("(?:Volume|Vol\\.?)[\\s_]*(\\d+)", RegexOption.IGNORE_CASE)
        val fullTextWord = Regex("Full[\\s_]*Text", RegexOption.IGNORE_CASE)

        // Document order preserves reading order (the allpages list doesn't).
        for (anchor in d.select("a[href]")) {
            val classes = anchor.attr("class").split(Regex("\\s+"))
            if ("new" in classes || "external" in classes) continue
            val href = anchor.attr("href")
            if (Regex("^(?:https?:)?//").containsMatchIn(href)) continue
            if (Regex("\\.(?:pdf|epub|mobi|zip|rar|7z|docx?)$", RegexOption.IGNORE_CASE).containsMatchIn(href)) continue
            if (Regex("[?&]action=edit").containsMatchIn(href)) continue

            val rawTitle = anchor.attr("title").trim()
                .takeUnless { it.isEmpty() } ?: continue
            if (Regex("\\(page does not exist\\)$", RegexOption.IGNORE_CASE).containsMatchIn(rawTitle)) continue

            val title = rawTitle.replace('_', ' ')
            val prefix = prefixes.firstOrNull { title.startsWith(it) } ?: continue
            if (Regex("^(?:File|Image|Category|Template|Help|User|Talk|Special):", RegexOption.IGNORE_CASE)
                .containsMatchIn(title)
            ) continue

            val label = anchor.text().replace(Regex("\\s+"), " ").trim()
            val suffix = title.removePrefix(prefix).trim()
            if (nonChapterPattern.containsMatchIn(suffix)) continue
            val path = toPath(title)
            if (!seen.add(path)) continue

            candidates.add(
                Candidate(
                    title = title,
                    name = label.ifBlank { suffix },
                    volume = volumeNumber.find(suffix)?.groupValues?.get(1).orEmpty(),
                    isFullText = fullTextWord.containsMatchIn(suffix) ||
                        Regex("^Full[\\s_]*Text$", RegexOption.IGNORE_CASE).matches(label),
                ),
            )
        }

        // A volume's "Full Text" page duplicates its chapters; keep it only
        // where there is nothing else to read for that volume.
        val chaptersPerVolume = HashMap<String, Int>()
        for (candidate in candidates) {
            if (!candidate.isFullText) {
                chaptersPerVolume.merge(candidate.volume, 1, Int::plus)
            }
        }
        val kept = candidates.filter { !it.isFullText || (chaptersPerVolume[it.volume] ?: 0) == 0 }

        // Volumes ascending, ungrouped last; document order within each group.
        val chapters = kept
            .withIndex()
            .sortedWith(
                compareBy(
                    { (index, candidate) ->
                        if (candidate.volume.isEmpty()) Double.POSITIVE_INFINITY
                        else candidate.volume.toDoubleOrNull() ?: Double.POSITIVE_INFINITY
                    },
                    { (index, _) -> index },
                ),
            )
            .mapIndexed { index, (_, candidate) ->
                Episode(
                    id = toPath(candidate.title),
                    name = candidate.name,
                    number = (index + 1).toFloat(),
                    dateMillis = null,
                )
            }
            .toMutableList()

        attachReleaseTimes(chapters)
        return chapters
    }

    /** Last-edit timestamps, batched 50 per request and capped at 4 batches. */
    private suspend fun attachReleaseTimes(chapters: MutableList<Episode>) {
        val byTitle = HashMap<String, Int>()
        chapters.forEachIndexed { index, chapter -> byTitle[toTitle(chapter.id)] = index }
        val titles = byTitle.keys.take(4 * 50)
        var i = 0
        while (i < titles.size) {
            val batch = titles.subList(i, minOf(i + 50, titles.size))
            runCatching {
                val json = query(
                    mapOf(
                        "action" to "query",
                        "titles" to batch.joinToString("|"),
                        "prop" to "revisions",
                        "rvprop" to "timestamp",
                    ),
                )
                jsonObjArray(json, "query", "pages")?.forEach { el ->
                    val page = el as? JsonObject ?: return@forEach
                    val pageTitle = page["title"]?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull } ?: return@forEach
                    val timestamp = jsonObjArray(page, "revisions")?.firstOrNull()
                        ?.let { rev -> (rev as? JsonObject)?.get("timestamp")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull } }
                    val index = byTitle[pageTitle]
                    if (timestamp != null && index != null && timestamp.length >= 10) {
                        chapters[index] = chapters[index].copy(
                            dateMillis = parseDate(timestamp.take(10), "yyyy-MM-dd"),
                        )
                    }
                }
            }
            i += 50
        }
    }

    // ---- chapter text ------------------------------------------------------

    override suspend fun chapterText(episodeId: String): List<String> {
        // A deleted or renamed page is an API error, not an empty body.
        val json = runCatching {
            query(
                mapOf(
                    "action" to "parse",
                    "page" to toTitle(episodeId),
                    "prop" to "text",
                    "disableeditsection" to "1",
                    "disabletoc" to "1",
                    "redirects" to "1",
                ),
            )
        }.getOrNull()
        val html = (json?.get("parse") as? JsonObject)?.get("text")?.let { (it as? JsonPrimitive)?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.contentOrNull }
            ?: return listOf("This chapter is not available on Baka-Tsuki.")

        val d = Jsoup.parseBodyFragment(html)
        d.select(
            ".mw-editsection, #toc, .toc, .navbox, .printfooter, .catlinks, " +
                ".mw-jump-link, .noprint, #siteSub, .mw-empty-elt, #contentSub, " +
                ".mw-references-wrap ~ .navbox, script, style",
        ).remove()

        // Chapter navigation tables appear top and bottom; match on content.
        for (table in d.select("table")) {
            if (Regex("Back to|Return to|Forward to|Main Page", RegexOption.IGNORE_CASE)
                .containsMatchIn(table.text())
            ) {
                table.remove()
            }
        }

        val origin = baseUrl.toHttpUrl().newBuilder().build().toString().trimEnd('/')
        fun absolute(value: String): String = when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> origin + value
            else -> value
        }
        for (img in d.select("img[src]")) {
            img.attr("src", absolute(img.attr("src")))
            img.removeAttr("srcset")
        }
        for (a in d.select("a[href]")) a.attr("href", absolute(a.attr("href")))

        return paragraphsOf(d.body())
    }
}

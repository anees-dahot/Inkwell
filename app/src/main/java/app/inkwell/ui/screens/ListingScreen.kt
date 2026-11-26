package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkwell.AppContainer
import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.PagedResult
import app.inkwell.data.model.Source
import app.inkwell.data.model.TagBrowsable
import app.inkwell.data.source.Net
import app.inkwell.data.source.ResultCache
import app.inkwell.data.source.ScraperSource
import app.inkwell.data.source.SourceHealth
import app.inkwell.data.source.fanOut
import app.inkwell.data.source.interleave
import app.inkwell.data.source.titleKey
import app.inkwell.ui.components.*
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A grid of titles for one tag (genre) or one source. Opened by tapping a genre chip or the
 * source name on a title page.
 *
 * Tag: "This source" lists the tag on the title's own source (when the site supports genre pages);
 * "All sources" merges every source that does. Sources without genre pages fall back to a search
 * for the tag name. Source: the source's Popular / Latest feed.
 */
class ListingViewModel(
    private val c: AppContainer,
    val type: ContentType,
    val sourceId: String?,
    val tag: String?,
) : ViewModel() {
    private val origin: Source? = sourceId?.let { c.registry.find(it) }
    val originSupportsTag = origin is TagBrowsable
    /** 0 = this source, 1 = all sources (tag mode) · 0 = popular, 1 = latest (source mode). */
    var tab by mutableIntStateOf(if (tag != null && !originSupportsTag) 1 else 0); private set
    var items by mutableStateOf(emptyList<Entry>()); private set
    var also by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var hasNext by mutableStateOf(false); private set
    var progress by mutableStateOf(0 to 0); private set
    private var page = 1
    private var gen = 0
    private var job: Job? = null
    private var moreSources: Set<String>? = null
    private var force = false

    init { reload() }

    fun selectTab(t: Int) { if (t != tab) { tab = t; reload() } }
    fun refresh() { force = true; reload() }
    fun reload() { page = 1; items = emptyList(); also = emptyMap(); hasNext = false; moreSources = null; error = null; load() }
    fun loadMore() { if (!loading && hasNext && error == null) load() }

    val allTagSources: List<Source>
        get() = c.registry.sources.filter { it.type == type && it is TagBrowsable && !SourceHealth.benched(it.id) }

    private fun targets(): List<Source> = when {
        tag == null -> listOfNotNull(origin)
        tab == 0 -> listOfNotNull(origin)
        else -> allTagSources.ifEmpty {
            // no source of this type has genre pages: search the tag name everywhere instead
            c.registry.sources.filter { it.type == type && !SourceHealth.benched(it.id) }
        }
    }

    private suspend fun fetch(src: Source, p: Int): PagedResult<Entry> {
        val key = src.id + "|" + when {
            tag == null -> if (tab == 0) "p" else "l"
            src is TagBrowsable -> "t:$tag"
            else -> "s:$tag"
        } + "|" + p
        return ResultCache.cached(key, force) {
            when {
                tag == null -> if (tab == 0) src.popular(p) else src.latest(p)
                src is TagBrowsable -> src.byTag(tag, p)
                else -> src.search(tag, p)
            }
        }
    }

    private fun load() {
        job?.cancel()
        val my = ++gen
        job = viewModelScope.launch {
            loading = true; error = null
            try {
                val srcs = targets().filter { moreSources == null || it.id in moreSources!! }
                if (srcs.size == 1) {
                    val r = fetch(srcs[0], page)
                    if (my == gen) {
                        val seen = items.map { it.id }.toHashSet()
                        items = items + r.items.filter { seen.add(it.id) }
                        hasNext = r.hasNext && r.items.isNotEmpty(); page++
                    }
                } else loadMany(my, srcs)
                force = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (my == gen) error = e.message ?: "Something went wrong" }
            finally { if (my == gen) loading = false }
        }
    }

    private suspend fun loadMany(my: Int, srcs: List<Source>, retried: Boolean = false) {
        val order = srcs.sortedBy { SourceHealth.avgMs(it.id) }.map { it.id }
        val seen = HashMap<String, Entry>().apply { items.forEach { put(titleKey(it.title).ifEmpty { it.sourceId + it.id }, it) } }
        val al = HashMap<String, MutableList<String>>().apply { also.forEach { (k, v) -> put(k, v.toMutableList()) } }
        val pending = LinkedHashMap<String, List<Entry>>()
        val next = HashSet<String>()
        var failed = 0
        fun flush() {
            if (my != gen || pending.isEmpty()) return
            val add = interleave(order, pending, seen, al)
            pending.clear()
            if (add.isNotEmpty()) items = items + add
            also = al.mapValues { it.value.toList() }
        }
        progress = 0 to srcs.size
        coroutineScope {
            val ticker = launch { while (true) { delay(if (items.isEmpty()) 350 else 700); flush() } }
            fanOut(srcs, op = { fetch(it, page) }) { src, r ->
                if (my != gen) return@fanOut
                if (r == null) failed++ else { pending[src.id] = r.items; if (r.hasNext && r.items.isNotEmpty()) next += src.id }
                progress = (progress.first + 1) to srcs.size
            }
            ticker.cancel()
        }
        flush()
        if (my != gen) return
        if (items.isEmpty() && failed == srcs.size && srcs.isNotEmpty() && !retried && Net.online && Net.slow) return loadMany(my, srcs, true)
        moreSources = next
        hasNext = next.isNotEmpty(); page++
        if (items.isEmpty() && failed == srcs.size && srcs.isNotEmpty()) {
            error = if (Net.online) "No source answered. Your connection may be slow — try again." else "You're offline. Check your connection."
        }
    }

    fun sourceName(id: String) = c.registry.find(id)?.name?.substringBefore(" (") ?: id
    fun label(e: Entry): String {
        val more = also[e.sourceId + "/" + e.id]?.size ?: 0
        return sourceName(e.sourceId) + if (more > 0) " +$more" else ""
    }
    val usesSearchFallback get() = tag != null && tab == 1 && allTagSources.isEmpty()
}

@Composable
fun ListingScreen(
    type: ContentType, sourceId: String?, tag: String?,
    onBack: () -> Unit, onOpen: (Entry) -> Unit,
) {
    val c = LocalContainer.current
    val ink = LocalInk.current
    val vm = inkViewModel("listing/$type/$sourceId/$tag") { ListingViewModel(it, type, sourceId, tag) }
    val hazeState = remember { HazeState() }
    val grid = rememberLazyGridState()
    val collapsed by grid.rememberCollapsed()
    val title = tag ?: sourceId?.let { vm.sourceName(it) } ?: "Browse"
    val nearEnd by remember {
        derivedStateOf {
            val info = grid.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, vm.items.size, vm.loading) { if (nearEnd) vm.loadMore() }

    Box(Modifier.fillMaxSize().background(ink.bg)) {
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = topContentPadding(),
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LargeTitle(title, Modifier)
                    if (tag != null) {
                        val here = sourceId?.let { vm.sourceName(it) }
                        if (here != null && vm.originSupportsTag) {
                            SegmentedControl(listOf(here, "All sources"), vm.tab, vm::selectTab)
                        }
                        val note = when {
                            vm.usesSearchFallback -> "These sources have no genre pages, so this is a search for “$tag”."
                            here != null && !vm.originSupportsTag -> "$here can't list titles by genre. Showing every source that can."
                            vm.tab == 1 -> "${vm.allTagSources.size} sources with genre pages"
                            else -> null
                        }
                        if (note != null) Text(note, style = InkType.caption, color = ink.secondary, modifier = Modifier.padding(horizontal = 4.dp))
                    } else {
                        SegmentedControl(listOf("Popular", "Latest"), vm.tab, vm::selectTab)
                    }
                    val (done, total) = vm.progress
                    if (vm.loading && total > 1) FeedProgress(done, total, Modifier.padding(horizontal = 4.dp))
                }
            }
            val cfUrl = sourceId?.let { (c.registry.find(it) as? ScraperSource)?.baseUrl }
            if (vm.error != null && vm.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(vm.error!!, vm::refresh, webViewUrl = if (vm.tab == 0) cfUrl else null) }
            } else if (vm.loading && vm.items.isEmpty()) {
                items(12) { SkeletonGridItem() }
            } else if (!vm.loading && vm.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        Icons.Outlined.SearchOff, "Nothing found.",
                        actionLabel = if (tag != null && vm.tab == 0) "Try all sources" else null,
                        onAction = if (tag != null && vm.tab == 0) ({ vm.selectTab(1) }) else null,
                    )
                }
            }
            items(vm.items, key = { it.sourceId + "/" + it.id }) { e ->
                EntryGridItem(
                    e, Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(220), placementSpec = null, fadeOutSpec = null),
                    subtitle = if (tag != null && vm.tab == 1) vm.label(e) else null,
                ) { onOpen(e) }
            }
            if (vm.loading && vm.items.isNotEmpty()) items(3) { SkeletonGridItem() }
            if (vm.error != null && vm.items.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(vm.error!!, vm::refresh) }
            }
        }
        GlassTopBar(
            title, collapsed, hazeState, Modifier.align(Alignment.TopCenter),
            leading = { BarIconButton(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", onClick = onBack) },
        )
    }
}

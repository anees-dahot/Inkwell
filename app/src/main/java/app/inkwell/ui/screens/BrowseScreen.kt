package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.inkwell.data.model.Source
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkwell.AppContainer
import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.source.ScraperSource
import app.inkwell.data.source.fanOut
import app.inkwell.data.source.ResultCache
import app.inkwell.data.source.SourceHealth
import app.inkwell.data.source.interleave
import app.inkwell.data.source.titleKey
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BrowseViewModel(private val c: AppContainer) : ViewModel() {
    /** Null = all sources of [type] combined. */
    var sourceId by mutableStateOf<String?>(null); private set
    var type by mutableStateOf(initialType(c)); private set
    var recents by mutableStateOf(c.settings.recentSources(type.name)); private set
    var tab by mutableIntStateOf(0); private set
    var query by mutableStateOf(""); private set
    var items by mutableStateOf(emptyList<Entry>()); private set
    /** "sourceId/entryId" -> other source ids carrying the same title. */
    var also by mutableStateOf<Map<String, List<String>>>(emptyMap()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var hasNext by mutableStateOf(false); private set
    var progress by mutableStateOf(0 to 0); private set
    var skipped by mutableIntStateOf(0); private set
    private var force = false

    private var page = 1
    private var gen = 0
    private var job: Job? = null
    private var searchJob: Job? = null
    private var moreSources: Set<String>? = null

    init { reload() }

    fun sourcesFor(t: ContentType) = c.registry.sources.filter { it.type == t }
    fun selectType(t: ContentType) {
        if (t == type) return
        type = t; sourceId = null; query = ""; tab = 0
        c.settings.defaultBrowseType = t.name
        recents = c.settings.recentSources(t.name); reload()
    }
    fun selectSource(id: String?) {
        if (id == sourceId) return
        sourceId = id
        if (id != null) { c.settings.rememberSource(type.name, id); recents = c.settings.recentSources(type.name) }
        reload()
    }
    fun selectTab(t: Int) { if (t != tab) { tab = t; reload() } }
    fun updateQuery(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(500); reload() }
    }

    fun refresh() { force = true; reload() }
    fun retrySkipped() { SourceHealth.resetAll(); refresh() }
    fun reload() { page = 1; items = emptyList(); also = emptyMap(); hasNext = false; moreSources = null; load() }
    fun loadMore() { if (!loading && hasNext && error == null) load() }

    private suspend fun fetch(src: app.inkwell.data.model.Source, p: Int): app.inkwell.data.model.PagedResult<Entry> {
        val q = query.trim()
        val key = src.id + "|" + (if (q.isNotEmpty()) "s:$q" else if (tab == 0) "p" else "l") + "|" + p
        return ResultCache.cached(key, force) {
            when {
                q.isNotEmpty() -> src.search(q, p)
                tab == 0 -> src.popular(p)
                else -> src.latest(p)
            }
        }
    }

    /** Pinned, then recently used, then fastest; benched (repeatedly failing) sources dropped. */
    private fun ordered(): List<app.inkwell.data.model.Source> {
        val pinned = c.repo.prefs.pinned.value
        val all = sourcesFor(type)
        val live = all.filter { it.id in pinned || !SourceHealth.benched(it.id) }
        skipped = all.size - live.size
        return live.sortedWith(compareBy(
            { if (it.id in pinned) 0 else if (it.id in recents) 1 else 2 },
            { recents.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } },
            { SourceHealth.avgMs(it.id) },
        ))
    }

    private fun load() {
        job?.cancel()
        val my = ++gen
        job = viewModelScope.launch {
            loading = true; error = null
            try {
                val single = sourceId
                if (single != null) {
                    val r = fetch(c.registry.get(single), page)
                    if (my == gen) {
                        val seen = items.map { it.id }.toHashSet()
                        items = items + r.items.filter { seen.add(it.id) }
                        hasNext = r.hasNext; page++
                    }
                } else loadAll(my)
                force = false
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (my == gen) error = e.message ?: "Something went wrong" }
            finally { if (my == gen) loading = false }
        }
    }

    private suspend fun loadAll(my: Int) {
        val srcs = ordered().filter { moreSources == null || it.id in moreSources!! }
        val order = srcs.map { it.id }
        val before = items
        val baseSeen = HashMap<String, Entry>().apply { before.forEach { put(titleKey(it.title).ifEmpty { it.sourceId + it.id }, it) } }
        val baseAlso = also.mapValues { it.value.toMutableList() }
        val batches = HashMap<String, List<Entry>>()
        val next = HashSet<String>()
        var failed = 0
        progress = 0 to srcs.size
        fanOut(srcs, parallel = 10, timeoutMs = 12_000, op = { fetch(it, page) }) { src, r ->
            if (my != gen) return@fanOut
            if (r == null) failed++ else { batches[src.id] = r.items; if (r.hasNext) next += src.id }
            val seen = HashMap(baseSeen)
            val al = HashMap<String, MutableList<String>>().apply { baseAlso.forEach { (k, v) -> put(k, v.toMutableList()) } }
            items = before + interleave(order, batches, seen, al)
            also = al
            progress = (progress.first + 1) to srcs.size
        }
        if (my != gen) return
        moreSources = next
        hasNext = next.isNotEmpty(); page++
        if (items.isEmpty() && failed == srcs.size && srcs.isNotEmpty()) error = "All sources failed. Check your connection."
    }

    fun sourceName(id: String) = c.registry.find(id)?.name?.substringBefore(" (") ?: id
    fun label(e: Entry): String {
        val more = also[e.sourceId + "/" + e.id]?.size ?: 0
        return sourceName(e.sourceId) + if (more > 0) " +$more" else ""
    }
}

private fun initialType(c: AppContainer): ContentType =
    runCatching { ContentType.valueOf(c.settings.defaultBrowseType ?: "MANGA") }.getOrDefault(ContentType.MANGA)
        .takeIf { it != ContentType.VIDEO } ?: ContentType.MANGA

@Composable
fun BrowseScreen(hazeState: HazeState, onOpen: (Entry) -> Unit) {
    val c = LocalContainer.current
    val ink = LocalInk.current
    val vm = inkViewModel("browse") { BrowseViewModel(it) }
    val grid = rememberLazyGridState()
    val collapsed by grid.rememberCollapsed()
    OnTabReselect(2) { grid.animateScrollToItem(0) }

    val nearEnd by remember {
        derivedStateOf {
            val info = grid.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, vm.items.size) { if (nearEnd) vm.loadMore() }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = topContentPadding(),
                bottom = LocalBottomInset.current.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LargeTitle("Browse", Modifier)
                    val typeSources = vm.sourcesFor(vm.type)
                    val current = vm.sourceId?.let { id -> typeSources.firstOrNull { it.id == id } }
                    SearchField(vm.query, vm::updateQuery, if (current == null) "Search all ${typeSources.size} sources" else "Search ${current.name.substringBefore(" (")}")
                    SegmentedControl(listOf("Manga", "Novels"), if (vm.type == ContentType.NOVEL) 1 else 0, { t -> vm.selectType(if (t == 1) ContentType.NOVEL else ContentType.MANGA) })
                    if (typeSources.size > 1) {
                        SourceSelector(current, vm.recents.mapNotNull { id -> typeSources.firstOrNull { it.id == id } }, vm.sourceId, onPick = vm::selectSource, all = typeSources)
                    }
                    if (vm.query.isBlank()) {
                        SegmentedControl(listOf("Popular", "Latest"), vm.tab, vm::selectTab)
                    }
                    val (done, total) = vm.progress
                    if (vm.sourceId == null && vm.loading && total > 0) {
                        Text("Loading $done of $total sources…", style = InkType.caption, color = ink.secondary)
                    }
                    if (vm.sourceId == null && vm.skipped > 0 && !vm.loading) {
                        Text(
                            "${vm.skipped} unresponsive sources skipped · Retry", style = InkType.caption, color = ink.accent,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).pressable { vm.retrySkipped() }.padding(vertical = 4.dp),
                        )
                    }
                }
            }
            val cfUrl = vm.sourceId?.let { (c.registry.find(it) as? ScraperSource)?.baseUrl }
            if (vm.error != null && vm.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(vm.error!!, vm::refresh, webViewUrl = cfUrl) }
            } else if (vm.loading && vm.items.isEmpty()) {
                items(12) { SkeletonGridItem() }
            } else if (!vm.loading && vm.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Outlined.SearchOff, "Nothing found.") }
            }
            items(vm.items, key = { it.sourceId + "/" + it.id }) { e ->
                EntryGridItem(e, subtitle = vm.label(e)) { onOpen(e) }
            }
            if (vm.loading && vm.items.isNotEmpty()) {
                items(3) { SkeletonGridItem() }
            }
            if (vm.error != null && vm.items.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(vm.error!!, vm::refresh, webViewUrl = cfUrl) }
            }
        }
        GlassTopBar("Browse", collapsed, hazeState, Modifier.align(Alignment.TopCenter))
    }

}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    val ink = LocalInk.current
    Row(
        Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(10.dp)).background(ink.secondary.copy(alpha = 0.14f)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = ink.secondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, color = ink.secondary, style = InkType.body)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = InkType.body.copy(color = ink.label),
                cursorBrush = SolidColor(ink.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SourceSelector(
    current: Source?, recent: List<Source>, currentId: String?,
    all: List<Source>, onPick: (String?) -> Unit,
) {
    val ink = LocalInk.current
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ink.secondary.copy(alpha = 0.14f))
                .pressable { open = true }.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Source", style = InkType.callout, color = ink.secondary)
            Spacer(Modifier.weight(1f))
            Text(current?.name?.substringBefore(" (") ?: "All sources (${all.size})", style = InkType.callout.copy(fontWeight = FontWeight.SemiBold), color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f, fill = false))
            if (current != null) {
                Spacer(Modifier.width(6.dp))
                Text(current.lang.uppercase(), style = InkType.caption, color = ink.secondary)
            }
            Icon(Icons.Outlined.UnfoldMore, null, tint = ink.secondary, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
        if (recent.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "__all") {
                    val selected = currentId == null
                    Text(
                        "All", style = InkType.callout,
                        color = if (selected) Color.White else ink.label, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (selected) ink.accent else ink.secondary.copy(alpha = 0.14f))
                            .pressable { onPick(null) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
                items(recent, key = { it.id }) { s ->
                    val selected = s.id == currentId
                    Text(
                        s.name.substringBefore(" ("), style = InkType.callout,
                        color = if (selected) Color.White else ink.label, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp))
                            .background(if (selected) ink.accent else ink.secondary.copy(alpha = 0.14f))
                            .pressable { onPick(s.id) }.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
    if (open) {
        var q by remember { mutableStateOf("") }
        val grouped = remember(q, all) {
            all.filter { q.isBlank() || it.name.contains(q.trim(), true) }
                .sortedBy { it.name.lowercase() }
                .groupBy { it.name.firstOrNull()?.uppercaseChar()?.takeIf { c -> c.isLetter() } ?: '#' }
        }
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ink.bg,
        ) {
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().imePadding()) {
                Text("Choose source", style = InkType.headline, color = ink.label, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                Box(Modifier.padding(horizontal = 16.dp)) { SearchField(q, { q = it }, "Search ${all.size} sources") }
                Spacer(Modifier.height(6.dp))
                LazyColumn(Modifier.weight(1f)) {
                    if (q.isBlank()) item(key = "__all") {
                        Row(
                            Modifier.fillMaxWidth().pressable { onPick(null); open = false }.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("All sources", style = InkType.body.copy(fontWeight = FontWeight.SemiBold), color = ink.label, modifier = Modifier.weight(1f))
                            if (currentId == null) Icon(Icons.Outlined.Check, null, tint = ink.accent, modifier = Modifier.padding(start = 10.dp).size(18.dp))
                        }
                    }
                    grouped.forEach { (letter, list) ->
                        stickyHeader(key = "h$letter") {
                            Text(letter.toString(), style = InkType.caption.copy(fontWeight = FontWeight.SemiBold), color = ink.secondary,
                                modifier = Modifier.fillMaxWidth().background(ink.bg).padding(horizontal = 16.dp, vertical = 4.dp))
                        }
                        items(list, key = { it.id }) { s ->
                            Row(
                                Modifier.fillMaxWidth().pressable { onPick(s.id); open = false }.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(s.name.substringBefore(" ("), style = InkType.body, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Text(s.lang.uppercase(), style = InkType.caption, color = ink.secondary)
                                if (s.id == currentId) Icon(Icons.Outlined.Check, null, tint = ink.accent, modifier = Modifier.padding(start = 10.dp).size(18.dp))
                            }
                        }
                    }
                    if (grouped.isEmpty()) item { Text("No sources match.", style = InkType.callout, color = ink.secondary, modifier = Modifier.padding(16.dp)) }
                }
            }
        }
    }
}

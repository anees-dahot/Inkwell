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
    var sourceId by mutableStateOf(initialSource(c)); private set
    var type by mutableStateOf(c.registry.find(sourceId)?.type ?: c.registry.sources.first().type); private set
    var recents by mutableStateOf(c.settings.recentSources(c.registry.find(sourceId)?.type?.name ?: "MANGA")); private set
    var tab by mutableIntStateOf(0); private set
    var query by mutableStateOf(""); private set
    var items by mutableStateOf(emptyList<Entry>()); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var hasNext by mutableStateOf(false); private set
    var addError by mutableStateOf<String?>(null); private set
    var adding by mutableStateOf(false); private set

    private var page = 1
    private var gen = 0
    private var job: Job? = null
    private var searchJob: Job? = null

    init { reload() }

    fun sourcesFor(t: ContentType) = c.registry.sources.filter { it.type == t }
    fun selectType(t: ContentType) {
        if (t == type) return
        type = t
        val list = sourcesFor(t)
        val first = list.firstOrNull { it.id == c.settings.lastSource(t.name) } ?: list.firstOrNull() ?: return
        sourceId = first.id; query = ""; tab = 0; recents = c.settings.recentSources(t.name); reload()
    }
    fun selectSource(id: String) {
        if (id == sourceId) return
        sourceId = id; query = ""
        c.settings.rememberSource(type.name, id); recents = c.settings.recentSources(type.name)
        reload()
    }
    fun selectTab(t: Int) { if (t != tab) { tab = t; reload() } }
    fun updateQuery(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch { delay(400); reload() }
    }

    fun reload() { page = 1; items = emptyList(); hasNext = false; load() }
    fun loadMore() { if (!loading && hasNext && error == null) load() }

    private fun load() {
        job?.cancel()
        val my = ++gen
        job = viewModelScope.launch {
            loading = true; error = null
            try {
                val src = c.registry.get(sourceId)
                val r = when {
                    query.isNotBlank() -> src.search(query.trim(), page)
                    tab == 0 -> src.popular(page)
                    else -> src.latest(page)
                }
                if (my == gen) {
                    val seen = items.map { it.id }.toHashSet()
                    items = items + r.items.filter { seen.add(it.id) }
                    hasNext = r.hasNext; page++
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (my == gen) error = e.message ?: "Something went wrong" }
            finally { if (my == gen) loading = false }
        }
    }

    fun addChannel(url: String, done: () -> Unit) {
        viewModelScope.launch {
            adding = true; addError = null
            try { c.youtube.addCustom(url); done(); reload() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { addError = e.message ?: "Could not add" }
            finally { adding = false }
        }
    }
    fun clearAddError() { addError = null }

    fun removeIfCustom(entry: Entry) {
        if (entry.sourceId != c.youtube.id) return
        viewModelScope.launch { if (c.youtube.isCustom(entry.id)) { c.youtube.removeCustom(entry.id); reload() } }
    }
}

private fun initialSource(c: AppContainer): String {
    val first = c.registry.sources.firstOrNull { it.type.name == c.settings.defaultBrowseType } ?: c.registry.sources.first()
    return c.settings.lastSource(first.type.name)?.takeIf { c.registry.find(it) != null } ?: first.id
}

@Composable
fun BrowseScreen(hazeState: HazeState, onOpen: (Entry) -> Unit, onSearchAll: (ContentType, String) -> Unit = { _, _ -> }) {
    val c = LocalContainer.current
    val ink = LocalInk.current
    val vm = inkViewModel("browse") { BrowseViewModel(it) }
    val grid = rememberLazyGridState()
    val collapsed by grid.rememberCollapsed()
    val haptic = LocalHapticFeedback.current
    OnTabReselect(2) { grid.animateScrollToItem(0) }
    val sources = c.registry.sources
    var showAdd by remember { mutableStateOf(false) }

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
                    SearchField(vm.query, vm::updateQuery, if (vm.sourceId == c.youtube.id) "Filter titles" else "Search ${sources.first { it.id == vm.sourceId }.name}")
                    if (vm.query.isNotBlank()) {
                        FilledPill("Search all sources for \"${vm.query.trim()}\"", Modifier.fillMaxWidth(), filled = false) { onSearchAll(vm.type, vm.query.trim()) }
                    }
                    SegmentedControl(listOf("Manga", "Novels", "Donghua"), when (vm.type) { ContentType.MANGA -> 0; ContentType.NOVEL -> 1; else -> 2 }, { t -> vm.selectType(listOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO)[t]) })
                    val typeSources = vm.sourcesFor(vm.type)
                    if (typeSources.size > 1) {
                        SourceSelector(typeSources.first { it.id == vm.sourceId }.let { it }, vm.recents.mapNotNull { id -> typeSources.firstOrNull { it.id == id } }, vm.sourceId, onPick = vm::selectSource, all = typeSources)
                    }
                    if (vm.query.isBlank() && vm.sourceId != c.youtube.id) {
                        SegmentedControl(listOf("Popular", "Latest"), vm.tab, vm::selectTab)
                    }
                    if (vm.sourceId == c.youtube.id) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            FilledPill("Add channel or playlist", filled = false) { showAdd = true }
                        }
                    }
                }
            }
            if (vm.error != null && vm.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(vm.error!!, vm::reload, webViewUrl = (c.registry.find(vm.sourceId) as? ScraperSource)?.baseUrl) }
            } else if (vm.loading && vm.items.isEmpty()) {
                items(12) { SkeletonGridItem() }
            } else if (!vm.loading && vm.items.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyState(Icons.Outlined.SearchOff, "Nothing found.") }
            }
            items(vm.items, key = { it.sourceId + "/" + it.id }) { e ->
                EntryGridItem(e, onLongClick = {
                    if (e.type == ContentType.VIDEO) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.removeIfCustom(e) }
                }) { onOpen(e) }
            }
            if (vm.loading && vm.items.isNotEmpty()) {
                items(3) { SkeletonGridItem() }
            }
            if (vm.error != null && vm.items.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(vm.error!!, vm::loadMoreRetry, webViewUrl = (c.registry.find(vm.sourceId) as? ScraperSource)?.baseUrl) }
            }
        }
        GlassTopBar("Browse", collapsed, hazeState, Modifier.align(Alignment.TopCenter))
    }

    if (showAdd) AddChannelDialog(vm, onDismiss = { showAdd = false; vm.clearAddError() })
}

private fun BrowseViewModel.loadMoreRetry() = reload()

@Composable
private fun AddChannelDialog(vm: BrowseViewModel, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add YouTube source") },
        text = {
            Column {
                Text("Paste a playlist, channel or @handle URL. Long-press a custom entry in the grid to remove it.", style = InkType.callout)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("youtube.com/@handle") })
                vm.addError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = InkType.caption) }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !vm.adding, onClick = { vm.addChannel(text, onDismiss) }) {
                Text(if (vm.adding) "Adding..." else "Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
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
    current: Source, recent: List<Source>, currentId: String,
    all: List<Source>, onPick: (String) -> Unit,
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
            Text(current.name.substringBefore(" ("), style = InkType.callout.copy(fontWeight = FontWeight.SemiBold), color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(2f, fill = false))
            Spacer(Modifier.width(6.dp))
            Text(current.lang.uppercase(), style = InkType.caption, color = ink.secondary)
            Icon(Icons.Outlined.UnfoldMore, null, tint = ink.secondary, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
        if (recent.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyRow
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
    var sourceId by mutableStateOf(c.registry.sources.first().id); private set
    var type by mutableStateOf(c.registry.sources.first().type); private set
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
        val first = sourcesFor(t).firstOrNull() ?: return
        sourceId = first.id; query = ""; tab = 0; reload()
    }
    fun selectSource(id: String) { if (id != sourceId) { sourceId = id; query = ""; reload() } }
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

@Composable
fun BrowseScreen(hazeState: HazeState, onOpen: (Entry) -> Unit) {
    val c = LocalContainer.current
    val ink = LocalInk.current
    val vm = inkViewModel("browse") { BrowseViewModel(it) }
    val grid = rememberLazyGridState()
    val collapsed by grid.rememberCollapsed()
    val haptic = LocalHapticFeedback.current
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
                    SegmentedControl(listOf("Manga", "Novels", "Donghua"), when (vm.type) { ContentType.MANGA -> 0; ContentType.NOVEL -> 1; else -> 2 }, { t -> vm.selectType(listOf(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO)[t]) })
                    val typeSources = vm.sourcesFor(vm.type)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(typeSources.size) { i ->
                            val s = typeSources[i]
                            val selected = s.id == vm.sourceId
                            Text(
                                s.name.substringBefore(" ("),
                                style = InkType.callout,
                                color = if (selected) androidx.compose.ui.graphics.Color.White else ink.label,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (selected) ink.accent else ink.secondary.copy(alpha = 0.14f))
                                    .pressable { vm.selectSource(s.id) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                            )
                        }
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
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(vm.error!!, vm::reload) }
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
                item(span = { GridItemSpan(maxLineSpan) }) { ErrorState(vm.error!!, vm::loadMoreRetry) }
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

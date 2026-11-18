package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.inkwell.AppContainer
import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.Source
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.CoverRadius
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class SearchState { LOADING, DONE, ERROR }
data class SourceResult(val source: Source, val state: SearchState, val items: List<Entry> = emptyList())

class GlobalSearchViewModel(private val c: AppContainer, val type: ContentType, initial: String) : ViewModel() {
    var query by mutableStateOf(initial); private set
    var results by mutableStateOf<List<SourceResult>>(emptyList()); private set
    var finished by mutableIntStateOf(0); private set
    private var job: Job? = null
    val total get() = results.size

    init { if (initial.isNotBlank()) run(initial) }

    fun update(q: String) {
        query = q
        job?.cancel()
        job = viewModelScope.launch { delay(500); run(q) }
    }

    private fun run(q: String) {
        job?.cancel()
        val sources = c.registry.sources.filter { it.type == type }
        if (q.isBlank()) { results = emptyList(); finished = 0; return }
        results = sources.map { SourceResult(it, SearchState.LOADING) }
        finished = 0
        job = viewModelScope.launch {
            val sem = Semaphore(8)
            coroutineScope {
                sources.forEach { src ->
                    launch {
                        sem.withPermit {
                            val r = try {
                                val items = withTimeout(15_000) { src.search(q.trim(), 1).items }
                                SourceResult(src, SearchState.DONE, items)
                            } catch (e: CancellationException) { if (e is TimeoutCancellationException) SourceResult(src, SearchState.ERROR) else throw e }
                            catch (e: Exception) { SourceResult(src, SearchState.ERROR) }
                            results = results.map { if (it.source.id == src.id) r else it }
                            finished++
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GlobalSearchScreen(typeName: String, query: String, onBack: () -> Unit, onOpen: (Entry) -> Unit) {
    val c = LocalContainer.current
    val ink = LocalInk.current
    val type = remember(typeName) { runCatching { ContentType.valueOf(typeName) }.getOrDefault(ContentType.MANGA) }
    val vm = inkViewModel("gsearch/$typeName") { GlobalSearchViewModel(it, type, query) }
    val pinned by c.repo.prefs.pinned.collectAsStateWithLifecycle()
    val recents = remember { c.settings.recentSources(type.name) }
    val hazeState = remember { HazeState() }
    val list = rememberLazyListState()
    val collapsed by list.rememberCollapsed()

    val ordered = remember(vm.results, pinned) {
        vm.results.sortedWith(compareBy<SourceResult>(
            { if (it.source.id in pinned) 0 else if (it.source.id in recents) 1 else 2 },
            { if (it.source.id in recents && it.source.id !in pinned) recents.indexOf(it.source.id) else 0 },
            { it.source.name.lowercase() },
        ))
    }
    val visible = ordered.filter { it.state == SearchState.LOADING || it.items.isNotEmpty() }
    val running = vm.total > 0 && vm.finished < vm.total

    Box(Modifier.fillMaxSize().background0(ink.bg)) {
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(top = topContentPadding(), bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { LargeTitle("Search all") }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchField(vm.query, vm::update, "Search all ${typeLabel(type)} sources")
                    if (vm.total > 0) {
                        Text(
                            if (running) "Searching ${vm.finished} of ${vm.total} sources…" else "Searched ${vm.total} sources",
                            style = InkType.caption, color = ink.secondary,
                        )
                        if (running) LinearProgressIndicator(progress = { vm.finished / vm.total.toFloat() }, modifier = Modifier.fillMaxWidth(), color = ink.accent)
                    }
                }
            }
            if (vm.query.isBlank()) item { EmptyState(Icons.Outlined.SearchOff, "Type to search every source at once.") }
            else if (!running && visible.isEmpty() && vm.total > 0) item { EmptyState(Icons.Outlined.SearchOff, "No results from any source.") }
            items(visible, key = { it.source.id }) { r ->
                Column {
                    Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.source.name.substringBefore(" ("), style = InkType.headline, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (r.state == SearchState.LOADING) "Searching…" else "${r.items.size} results") + "  ·  " + r.source.lang.uppercase(),
                                style = InkType.caption, color = ink.secondary,
                            )
                        }
                        val isPinned = r.source.id in pinned
                        BarIconButton(if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (isPinned) "Unpin source" else "Pin source",
                            tint = if (isPinned) ink.accent else ink.secondary) { c.repo.prefs.togglePin(r.source.id) }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (r.state == SearchState.LOADING) {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(4) { Box(Modifier.width(110.dp)) { SkeletonGridItem() } }
                        }
                    } else {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(r.items, key = { it.id }) { e -> EntryGridItem(e, Modifier.width(110.dp)) { onOpen(e) } }
                        }
                    }
                }
            }
            val failed = ordered.count { it.state == SearchState.ERROR || (it.state == SearchState.DONE && it.items.isEmpty()) }
            if (!running && failed > 0 && visible.isNotEmpty()) item {
                Text("$failed sources had no results or failed.", style = InkType.caption, color = ink.secondary, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
        GlassTopBar(
            "Search all", collapsed, hazeState, Modifier.align(Alignment.TopCenter),
            leading = { BarIconButton(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", onClick = onBack) },
        )
        OfflineBanner(Modifier.align(Alignment.TopCenter))
    }
}

private fun typeLabel(t: ContentType) = when (t) { ContentType.MANGA -> "manga"; ContentType.NOVEL -> "novel"; else -> "donghua" }

private fun Modifier.background0(c: androidx.compose.ui.graphics.Color) = this.then(Modifier.background(c))

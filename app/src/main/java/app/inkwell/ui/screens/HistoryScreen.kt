package app.inkwell.ui.screens

import app.inkwell.ui.util.relativeDate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.db.HistoryItem
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(hazeState: HazeState, onOpen: (HistoryItem) -> Unit) {
    val c = LocalContainer.current
    val all by c.repo.history().collectAsStateWithLifecycle(emptyList())
    var query by rememberSaveable { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val items = remember(all, query) {
        val q = query.trim()
        if (q.isEmpty()) all else all.filter { it.entryTitle.contains(q, true) || it.episodeName.contains(q, true) }
    }
    val list = rememberLazyListState()
    val collapsed by list.rememberCollapsed()
    val ink = LocalInk.current
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val switchTab = LocalSwitchTab.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    OnTabReselect(3) { list.animateScrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(top = topContentPadding(), bottom = LocalBottomInset.current.calculateBottomPadding() + 16.dp),
        ) {
            item { LargeTitle("History") }
            if (all.isNotEmpty()) item {
                Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { SearchField(query, { query = it }, "Search history") }
            }
            if (items.isEmpty()) item {
                EmptyState(
                    Icons.Outlined.History, if (all.isEmpty()) "Nothing read or watched yet.\nChapters you open show up here." else "Nothing matches.",
                    actionLabel = if (all.isEmpty()) "Browse sources" else null, onAction = if (all.isEmpty()) ({ switchTab(2) }) else null,
                )
            }
            items(items, key = { it.sourceId + "/" + it.entryId }) { h ->
                val dismiss = rememberSwipeToDismissBoxState(
                    confirmValueChange = { v ->
                        if (v == SwipeToDismissBoxValue.EndToStart) {
                            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            scope.launch {
                                c.repo.deleteHistory(h.sourceId, h.entryId)
                                val r = snack.showSnackbar("Removed ${h.entryTitle}", "Undo", duration = SnackbarDuration.Short)
                                if (r == SnackbarResult.ActionPerformed) c.repo.restoreHistory(h)
                            }
                            true
                        } else false
                    },
                )
                SwipeToDismissBox(
                    state = dismiss,
                    modifier = Modifier.animateItem(),
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(ink.accent).padding(end = 24.dp), contentAlignment = Alignment.CenterEnd) {
                            Icon(Icons.Outlined.Delete, "Delete", tint = Color.White)
                        }
                    },
                ) {
                    Column(Modifier.background(ink.bg)) {
                        Row(
                            Modifier.fillMaxWidth().pressable { onOpen(h) }.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(h.coverUrl, Modifier.width(44.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(h.entryTitle, style = InkType.headline, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(h.episodeName, style = InkType.callout, color = ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(relativeDate(h.timestamp), style = InkType.caption, color = ink.secondary)
                        }
                        Hairline(startInset = 72.dp)
                    }
                }
            }
        }
        GlassTopBar(
            "History", collapsed, hazeState, Modifier.align(Alignment.TopCenter),
            trailing = { if (all.isNotEmpty()) BarIconButton(Icons.Outlined.DeleteSweep, "Clear all history") { confirmClear = true } },
        )
        if (confirmClear) AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") }, text = { Text("This removes all ${all.size} history entries. Reading progress is kept.") },
            confirmButton = { TextButton({ confirmClear = false; scope.launch { c.repo.clearHistory() } }) { Text("Clear") } },
            dismissButton = { TextButton({ confirmClear = false }) { Text("Cancel") } },
        )
        SnackbarHost(
            snack,
            Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomInset.current.calculateBottomPadding() + 8.dp),
        )
    }
}

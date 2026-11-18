package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.db.DlStatus
import app.inkwell.data.db.DownloadItem
import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.CardRadius
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch

fun formatBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> "%.1f MB".format(b / (1024.0 * 1024))
    b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
    else -> "$b B"
}

@Composable
fun DownloadsScreen(onBack: () -> Unit, onOpenEntry: (Entry) -> Unit) {
    val c = LocalContainer.current
    val ink = LocalInk.current
    val scope = rememberCoroutineScope()
    val all by c.repo.downloads().collectAsStateWithLifecycle(emptyList())
    val hazeState = remember { HazeState() }
    val list = rememberLazyListState()
    val collapsed by list.rememberCollapsed()
    val queue = remember(all) { all.filter { it.status != DlStatus.DONE } }
    val groups = remember(all) {
        all.filter { it.status == DlStatus.DONE }.groupBy { it.sourceId + "/" + it.entryId }.values.sortedBy { it.first().entryTitle.lowercase() }
    }
    val total = remember(all) { all.filter { it.status == DlStatus.DONE }.sumOf { it.bytes } }
    var confirm by remember { mutableStateOf<List<DownloadItem>?>(null) }
    val running = queue.any { it.status == DlStatus.QUEUED || it.status == DlStatus.DOWNLOADING }

    Box(Modifier.fillMaxSize().background(ink.bg)) {
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(top = topContentPadding(), bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
        ) {
            item { LargeTitle("Downloads") }
            if (all.isEmpty()) item { EmptyState(Icons.Outlined.DownloadDone, "No downloads yet.\nUse the download button on a title's chapter list.") }
            if (queue.isNotEmpty()) {
                item {
                    Row(Modifier.padding(start = 32.dp, end = 16.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("QUEUE (${queue.size})", style = InkType.caption.copy(letterSpacing = 0.sp), color = ink.secondary, modifier = Modifier.weight(1f))
                        Text(
                            if (running) "Pause all" else "Resume all", style = InkType.callout, color = ink.accent,
                            modifier = Modifier.pressable { scope.launch { if (running) c.repo.pauseDownloads() else c.repo.resumeDownloads() } }.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        Text(
                            "Cancel all", style = InkType.callout, color = ink.accent,
                            modifier = Modifier.pressable { scope.launch { c.repo.clearDownloadQueue() } }.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                item {
                    GroupedCard {
                        queue.forEachIndexed { i, d ->
                            QueueRow(d) { scope.launch { c.repo.cancelDownload(d) } }
                            if (i < queue.lastIndex) Hairline(72.dp)
                        }
                    }
                }
            }
            if (groups.isNotEmpty()) {
                item { SectionHeader("Downloaded  ·  ${formatBytes(total)}") }
                item {
                    GroupedCard {
                        groups.forEachIndexed { i, g ->
                            val f = g.first()
                            Row(
                                Modifier.fillMaxWidth().pressable { onOpenEntry(Entry(f.sourceId, f.entryId, f.entryTitle, f.coverUrl, ContentType.valueOf(f.type))) }.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(f.coverUrl, Modifier.width(40.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(f.entryTitle, style = InkType.headline, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${g.size} chapter${if (g.size == 1) "" else "s"}  ·  ${formatBytes(g.sumOf { it.bytes })}", style = InkType.callout, color = ink.secondary)
                                }
                                BarIconButton(Icons.Outlined.Delete, "Delete downloads", tint = ink.secondary) { confirm = g }
                            }
                            if (i < groups.lastIndex) Hairline(64.dp)
                        }
                    }
                }
            }
        }
        GlassTopBar(
            "Downloads", collapsed, hazeState, Modifier.align(Alignment.TopCenter),
            leading = { BarIconButton(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", onClick = onBack) },
        )
    }
    confirm?.let { g ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete downloads?") },
            text = { Text("Removes ${g.size} chapter${if (g.size == 1) "" else "s"} of ${g.first().entryTitle} (${formatBytes(g.sumOf { it.bytes })}) from this device.") },
            confirmButton = { TextButton({ confirm = null; scope.launch { c.repo.deleteDownloads(g) } }) { Text("Delete") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun QueueRow(d: DownloadItem, onCancel: () -> Unit) {
    val ink = LocalInk.current
    Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(d.coverUrl, Modifier.width(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.entryTitle, style = InkType.callout, color = ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(d.episodeName, style = InkType.body, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (d.status) {
                DlStatus.DOWNLOADING -> {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { if (d.total > 0) d.progress / d.total.toFloat() else 0f }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp), color = ink.accent)
                }
                DlStatus.ERROR -> Text("Failed: ${d.error ?: "error"}", style = InkType.caption, color = ink.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DlStatus.PAUSED -> Text("Paused", style = InkType.caption, color = ink.secondary)
                else -> Text("Queued", style = InkType.caption, color = ink.secondary)
            }
        }
        BarIconButton(Icons.Outlined.Close, "Cancel", tint = ink.secondary, onClick = onCancel)
    }
}

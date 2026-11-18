package app.inkwell.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import app.inkwell.data.model.Episode
import app.inkwell.ui.theme.CoverRadius
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.db.UpdateRow
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import app.inkwell.ui.util.relativeDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdatesScreen(hazeState: HazeState, onOpenEpisode: (UpdateRow) -> Unit, onOpenUpcoming: () -> Unit = {}) {
    val c = LocalContainer.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val rows by c.repo.updates().collectAsStateWithLifecycle(emptyList())
    val refreshing by c.repo.refreshing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val collapsed by list.rememberCollapsed()
    val ink = LocalInk.current
    val state = rememberPullToRefreshState()
    val top = topContentPadding()
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    OnTabReselect(1) { list.animateScrollToItem(0) }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { c.repo.refreshUpdates() } },
            state = state,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = state, isRefreshing = refreshing, color = ink.accent, containerColor = ink.card,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
                )
            },
        ) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                contentPadding = PaddingValues(top = top, bottom = LocalBottomInset.current.calculateBottomPadding() + 16.dp),
            ) {
                item { LargeTitle("Updates") }
                if (rows.isEmpty() && refreshing) items(8) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(44.dp).aspectRatio(2f / 3f).clip(androidx.compose.foundation.shape.RoundedCornerShape(CoverRadius)).shimmer())
                        Spacer(Modifier.width(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.width(180.dp).height(12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)).shimmer())
                            Box(Modifier.width(120.dp).height(10.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).shimmer())
                        }
                    }
                } else if (rows.isEmpty()) item {
                    EmptyState(
                        Icons.Outlined.NotificationsNone, "No updates yet.\nNew chapters from your library show up here.",
                        actionLabel = "Refresh library", onAction = { scope.launch { c.repo.refreshUpdates() } },
                    )
                }
                items(rows, key = { "${it.sourceId}/${it.entryId}/${it.episodeId}" }) { r ->
                    val swipe = rememberSwipeToDismissBoxState(
                        confirmValueChange = { v ->
                            if (v == SwipeToDismissBoxValue.StartToEnd) {
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                scope.launch { c.repo.markRead(r.sourceId, r.entryId, listOf(Episode(r.episodeId, r.name, null, r.dateMillis)), !r.isRead) }
                            }
                            false // always snap back; the row dims instead of disappearing
                        },
                    )
                    SwipeToDismissBox(
                        state = swipe, modifier = Modifier.animateItem(), enableDismissFromEndToStart = false,
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().background(ink.accent).padding(start = 24.dp), contentAlignment = Alignment.CenterStart) {
                                Icon(if (r.isRead) Icons.Outlined.MarkChatUnread else Icons.Outlined.MarkChatRead, if (r.isRead) "Mark unread" else "Mark read", tint = Color.White)
                            }
                        },
                    ) {
                    Column(Modifier.background(ink.bg)) {
                    Row(
                        Modifier.fillMaxWidth().alpha(if (r.isRead) 0.5f else 1f)
                            .pressable { onOpenEpisode(r) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(r.coverUrl, Modifier.width(44.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.entryTitle, style = InkType.headline, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(r.name, style = InkType.callout, color = ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(r.dateMillis?.let { relativeDate(it) } ?: "", style = InkType.caption, color = ink.secondary)
                    }
                    Hairline(startInset = 72.dp)
                    }
                    }
                }
            }
        }
        GlassTopBar(
            "Updates", collapsed, hazeState, Modifier.align(Alignment.TopCenter),
            trailing = {
                BarIconButton(Icons.Outlined.Refresh, "Update library now") { app.inkwell.work.Scheduler.updateNow(ctx) }
                BarIconButton(Icons.Outlined.CalendarMonth, "Upcoming releases", onClick = onOpenUpcoming)
            },
        )
    }
}

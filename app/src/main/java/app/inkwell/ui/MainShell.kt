package app.inkwell.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Update
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.inkwell.data.db.HistoryItem
import app.inkwell.data.db.UpdateRow
import app.inkwell.data.model.Entry
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.screens.*
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState

private val TABS = listOf(
    TabItem("Library", Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Filled.LibraryBooks),
    TabItem("Updates", Icons.Outlined.Update, Icons.Filled.Update),
    TabItem("Browse", Icons.Outlined.Explore, Icons.Filled.Explore),
    TabItem("History", Icons.Outlined.History, Icons.Filled.History),
    TabItem("More", Icons.Outlined.MoreHoriz, Icons.Filled.MoreHoriz),
)

@Composable
fun MainShell(
    onOpenEntry: (Entry) -> Unit,
    onOpenUpdate: (UpdateRow) -> Unit,
    onOpenHistory: (HistoryItem) -> Unit,
    onOpenUpcoming: () -> Unit = {},
    onOpenDownloads: () -> Unit = {},
    onSearchAll: (app.inkwell.data.model.ContentType, String) -> Unit = { _, _ -> },
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val hazeState = remember { HazeState() }
    val holder = rememberSaveableStateHolder()
    val reduced = LocalReducedMotion.current
    val ink = LocalInk.current
    val launchTab by app.inkwell.AppLaunch.openTab.collectAsState()
    LaunchedEffect(launchTab) {
        launchTab?.let { tab = it.coerceIn(0, TABS.lastIndex); app.inkwell.AppLaunch.openTab.value = null }
    }
    val barHeight = 49.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(ink.bg)) {
        CompositionLocalProvider(LocalBottomInset provides PaddingValues(bottom = barHeight)) {
            Crossfade(tab, animationSpec = Motion.Default.specFor(reduced), label = "tabs") { t ->
                holder.SaveableStateProvider(t) {
                    when (t) {
                        0 -> LibraryScreen(hazeState, onOpenEntry, onOpenHistory)
                        1 -> UpdatesScreen(hazeState, onOpenUpdate, onOpenUpcoming)
                        2 -> BrowseScreen(hazeState, onOpenEntry, onSearchAll)
                        3 -> HistoryScreen(hazeState, onOpenHistory)
                        else -> MoreScreen(hazeState, onOpenUpcoming, onOpenDownloads)
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter)) {
            GlassTabBar(TABS, tab, hazeState) { tab = it }
        }
    }
}

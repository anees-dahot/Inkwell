package app.inkwell.ui.screens

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.ui.components.*
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

@Composable
fun topContentPadding(): androidx.compose.ui.unit.Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight

@Composable
fun LibraryScreen(hazeState: HazeState, onOpen: (Entry) -> Unit) {
    val c = LocalContainer.current
    val items by c.repo.library().collectAsStateWithLifecycle(emptyList())
    val unread by c.repo.unread().collectAsStateWithLifecycle(emptyList())
    var filter by rememberSaveable { mutableIntStateOf(0) }
    val grid = rememberLazyGridState()
    val collapsed by grid.rememberCollapsed()
    val unreadMap = remember(unread) { unread.associate { (it.sourceId + "/" + it.entryId) to it.unread } }
    val shown = remember(items, filter) {
        val typeName = when (filter) {
            1 -> ContentType.MANGA.name; 2 -> ContentType.NOVEL.name; 3 -> ContentType.VIDEO.name
            else -> null
        }
        items.filter { typeName == null || it.type == typeName }
    }
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
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    LargeTitle("Library", Modifier.padding(horizontal = 0.dp))
                    Spacer(Modifier.height(8.dp))
                    SegmentedControl(listOf("All", "Manga", "Novels", "Donghua"), filter, { filter = it })
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(Icons.Outlined.CollectionsBookmark, "Your library is empty.\nAdd titles from Browse.")
                }
            }
            items(shown, key = { it.sourceId + "/" + it.entryId }) { li ->
                val e = Entry(li.sourceId, li.entryId, li.title, li.coverUrl, ContentType.valueOf(li.type))
                EntryGridItem(e, badge = unreadMap[li.sourceId + "/" + li.entryId] ?: 0) { onOpen(e) }
            }
        }
        GlassTopBar("Library", collapsed, hazeState, Modifier.align(androidx.compose.ui.Alignment.TopCenter))
    }
}

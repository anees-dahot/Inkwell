package app.inkwell.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.LibraryDisplay
import app.inkwell.data.LibrarySort
import app.inkwell.data.db.LibraryCategory
import app.inkwell.data.db.LibraryItem
import app.inkwell.data.libKey
import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.sortLibrary
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.CardRadius
import app.inkwell.ui.theme.CoverRadius
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map

@Composable
fun topContentPadding(): androidx.compose.ui.unit.Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(hazeState: HazeState, onOpen: (Entry) -> Unit, onContinue: (app.inkwell.data.db.HistoryItem) -> Unit = {}) {
    val c = LocalContainer.current
    val ink = LocalInk.current
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val itemsOrNull by remember { c.repo.library().map<List<LibraryItem>, List<LibraryItem>?> { it } }.collectAsStateWithLifecycle(null)
    val items = itemsOrNull ?: emptyList()
    val refreshing by c.repo.refreshing.collectAsStateWithLifecycle()
    val pullState = androidx.compose.material3.pulltorefresh.rememberPullToRefreshState()
    var migrateQueue by remember { mutableStateOf<List<MigrateTarget>>(emptyList()) }
    var migrateTotal by remember { mutableIntStateOf(0) }
    val switchTab = LocalSwitchTab.current
    val unread by c.repo.unread().collectAsStateWithLifecycle(emptyList())
    val history by c.repo.history().collectAsStateWithLifecycle(emptyList())
    val categories by c.repo.categories().collectAsStateWithLifecycle(emptyList())
    val sort by c.repo.prefs.sort.collectAsStateWithLifecycle()
    val asc by c.repo.prefs.ascending.collectAsStateWithLifecycle()
    val display by c.repo.prefs.display.collectAsStateWithLifecycle()
    val askNotif = rememberNotificationPermission()
    val cont = remember(history, items) {
        val keys = items.map { libKey(it.sourceId, it.entryId) }.toSet()
        history.firstOrNull { libKey(it.sourceId, it.entryId) in keys }
    }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var catSel by rememberSaveable { mutableLongStateOf(-1L) } // -1 all, 0 uncategorized
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var menu by remember { mutableStateOf<String?>(null) }
    var showCats by remember { mutableStateOf(false) }
    var pickCat by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    androidx.activity.compose.BackHandler(selecting) { selected = emptySet() }

    val grid = rememberLazyGridState()
    val collapsed by grid.rememberCollapsed()
    OnTabReselect(0) { grid.animateScrollToItem(0) }
    val unreadMap = remember(unread) { unread.associate { libKey(it.sourceId, it.entryId) to it.unread } }
    val lastRead = remember(history) { history.associate { libKey(it.sourceId, it.entryId) to it.timestamp } }
    LaunchedEffect(categories) { if (catSel > 0 && categories.none { it.id == catSel }) catSel = -1L }
    val hasUncategorized = remember(items) { items.any { it.categoryId == 0L } }
    val shown = remember(items, filter, catSel, query, sort, asc, unreadMap, lastRead) {
        val typeName = when (filter) {
            1 -> ContentType.MANGA.name; 2 -> ContentType.NOVEL.name
            else -> null
        }
        val q = query.trim()
        val base = items.filter {
            it.type != ContentType.VIDEO.name && (typeName == null || it.type == typeName) && (catSel < 0 || it.categoryId == catSel) &&
                (q.isEmpty() || it.title.contains(q, ignoreCase = true))
        }
        sortLibrary(base, sort, asc, unreadMap, lastRead)
    }
    val selItems = remember(selected, items) { items.filter { libKey(it.sourceId, it.entryId) in selected } }
    val selKeys = { selItems.map { it.sourceId to it.entryId } }

    fun toggle(li: LibraryItem) {
        val k = libKey(li.sourceId, li.entryId)
        selected = if (k in selected) selected - k else selected + k
    }

    Box(Modifier.fillMaxSize()) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { c.repo.refreshUpdates() } },
            state = pullState,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator(
                    state = pullState, isRefreshing = refreshing, color = ink.accent, containerColor = ink.card,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = topContentPadding()),
                )
            },
        ) {
        LazyVerticalGrid(
            state = grid,
            columns = when (display) {
                LibraryDisplay.GRID -> GridCells.Adaptive(110.dp)
                LibraryDisplay.COMPACT -> GridCells.Adaptive(88.dp)
                LibraryDisplay.LIST -> GridCells.Fixed(1)
            },
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp, top = topContentPadding(),
                bottom = LocalBottomInset.current.calculateBottomPadding() + if (selecting) 72.dp else 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(if (display == LibraryDisplay.COMPACT) 8.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (display == LibraryDisplay.LIST) 8.dp else 16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    LargeTitle("Library", Modifier.padding(horizontal = 0.dp))
                    Spacer(Modifier.height(8.dp))
                    if (!selecting) cont?.let { h ->
                        Row(
                            Modifier.fillMaxWidth().padding(bottom = 12.dp)
                                .clip(RoundedCornerShape(CardRadius))
                                .background(ink.card).pressable { onContinue(h) }.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Cover(h.coverUrl, Modifier.width(44.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Continue reading", style = InkType.caption, color = ink.accent)
                                Text(h.entryTitle, style = InkType.headline, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(h.episodeName, style = InkType.callout, color = ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(Icons.Outlined.PlayArrow, null, tint = ink.accent)
                        }
                    }
                    if (items.isNotEmpty()) {
                        SearchField(query, { query = it }, "Search library")
                        Spacer(Modifier.height(10.dp))
                    }
                    SegmentedControl(listOf("All", "Manga", "Novels"), filter, { filter = it })
                    if (categories.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            item { Chip("All", catSel == -1L) { catSel = -1L } }
                            items(categories, key = { it.id }) { cat -> Chip(cat.name, catSel == cat.id) { catSel = cat.id } }
                            if (hasUncategorized) item { Chip("Uncategorized", catSel == 0L) { catSel = 0L } }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            if (itemsOrNull == null) {
                items(12) { SkeletonGridItem() }
            } else if (shown.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState(
                        if (items.isEmpty()) Icons.Outlined.CollectionsBookmark else Icons.Outlined.SearchOff,
                        if (items.isEmpty()) "Your library is empty.\nAdd titles from Browse." else "Nothing matches.",
                        actionLabel = if (items.isEmpty()) "Browse sources" else null,
                        onAction = if (items.isEmpty()) ({ switchTab(2) }) else null,
                    )
                }
            }
            items(shown, key = { libKey(it.sourceId, it.entryId) }) { li ->
                val e = Entry(li.sourceId, li.entryId, li.title, li.coverUrl, ContentType.valueOf(li.type))
                val k = libKey(li.sourceId, li.entryId)
                val isSel = k in selected
                val badge = unreadMap[k] ?: 0
                val click = { if (selecting) toggle(li) else onOpen(e) }
                val long = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); toggle(li) }
                when (display) {
                    LibraryDisplay.LIST -> LibraryListRow(e, badge, isSel, click, long)
                    else -> LibraryCell(e, badge, isSel, display == LibraryDisplay.COMPACT, click, long)
                }
            }
        }
        }
        GlassTopBar(
            if (selecting) "${selected.size} selected" else "Library", collapsed || selecting, hazeState, Modifier.align(Alignment.TopCenter),
            leading = { if (selecting) BarIconButton(Icons.Outlined.Close, "Cancel selection") { selected = emptySet() } },
            trailing = {
                if (selecting) {
                    BarIconButton(Icons.Outlined.SelectAll, "Select all") { selected = shown.map { libKey(it.sourceId, it.entryId) }.toSet() }
                } else {
                    Box {
                        BarIconButton(Icons.Outlined.SortByAlpha, "Sort") { menu = "sort" }
                        DropdownMenu(menu == "sort", { menu = null }) {
                            LibrarySort.entries.forEach { s ->
                                DropdownMenuItem(
                                    { Text(s.label + if (s == sort) (if (asc) "   ↑" else "   ↓") else "") },
                                    { if (s == sort) c.repo.prefs.setAscending(!asc) else c.repo.prefs.setSort(s); menu = null },
                                )
                            }
                        }
                    }
                    Box {
                        BarIconButton(Icons.Outlined.GridView, "Display") { menu = "display" }
                        DropdownMenu(menu == "display", { menu = null }) {
                            listOf(LibraryDisplay.GRID to "Grid", LibraryDisplay.COMPACT to "Compact grid", LibraryDisplay.LIST to "List").forEach { (d, n) ->
                                DropdownMenuItem({ Text(n + if (d == display) "   ✓" else "") }, { c.repo.prefs.setDisplay(d); menu = null })
                            }
                        }
                    }
                    BarIconButton(Icons.AutoMirrored.Outlined.Label, "Categories") { showCats = true }
                }
            },
        )
        if (selecting) Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomInset.current.calculateBottomPadding())
                .fillMaxWidth().background(ink.card).height(56.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            BarIconButton(Icons.Outlined.MarkChatRead, "Mark read") {
                val k = selKeys(); selected = emptySet(); scope.launch { c.repo.markLibraryRead(k, true) }
            }
            BarIconButton(Icons.Outlined.MarkChatUnread, "Mark unread") {
                val k = selKeys(); selected = emptySet(); scope.launch { c.repo.markLibraryRead(k, false) }
            }
            BarIconButton(Icons.AutoMirrored.Outlined.Label, "Change category") { pickCat = true }
            BarIconButton(Icons.Outlined.Download, "Download unread") {
                val k = selKeys(); selected = emptySet(); askNotif()
                scope.launch {
                    val n = c.repo.downloadUnread(k)
                    android.widget.Toast.makeText(context, if (n == 0) "Nothing to download" else "Queued $n chapters", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            BarIconButton(Icons.Outlined.SwapHoriz, "Migrate to another source") {
                val t = selItems.map { MigrateTarget(it.sourceId, it.entryId, it.title, it.coverUrl, ContentType.valueOf(it.type)) }
                selected = emptySet(); migrateTotal = t.size; migrateQueue = t
            }
            BarIconButton(Icons.Outlined.Delete, "Remove from library") { confirmRemove = true }
        }
    }

    migrateQueue.firstOrNull()?.let { t ->
        key(t.sourceId, t.entryId) {
            MigrateDialog(
                t, positionLabel = if (migrateTotal > 1) "${migrateTotal - migrateQueue.size + 1} of $migrateTotal" else null,
                onDismiss = { migrateQueue = migrateQueue.drop(1) },
                onMigrated = { _, _ -> migrateQueue = migrateQueue.drop(1) },
            )
        }
    }
    if (pickCat) CategoryPickerDialog(
        "Move to category", categories,
        current = selItems.map { it.categoryId }.distinct().singleOrNull() ?: -1,
        onPick = { id -> val k = selKeys(); pickCat = false; selected = emptySet(); scope.launch { c.repo.setCategory(k, id) } },
        onDismiss = { pickCat = false },
    )
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove ${selItems.size} title${if (selItems.size == 1) "" else "s"}?") },
        text = { Text("They will be removed from your library. Downloaded chapters are kept.") },
        confirmButton = {
            TextButton({ val k = selKeys(); confirmRemove = false; selected = emptySet(); scope.launch { c.repo.removeFromLibrary(k) } }) { Text("Remove") }
        },
        dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel") } },
    )
    if (showCats) ManageCategoriesDialog(categories, onDismiss = { showCats = false })
}

@Composable
private fun SelectionMark(modifier: Modifier = Modifier) {
    val ink = LocalInk.current
    Box(modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(ink.accent), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Check, null, tint = Color.White, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun LibraryCell(e: Entry, badge: Int, selected: Boolean, compact: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val ink = LocalInk.current
    Column(Modifier.pressable(onLongClick = onLong, onClick = onClick)) {
        Box {
            Cover(e.coverUrl, Modifier.fillMaxWidth().sharedCover("cover-${e.sourceId}-${e.id}"))
            if (selected) Box(Modifier.matchParentSize().clip(RoundedCornerShape(CoverRadius)).background(ink.accent.copy(alpha = 0.35f)))
            if (compact) Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(bottomStart = CoverRadius, bottomEnd = CoverRadius))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))).padding(start = 6.dp, end = 6.dp, top = 22.dp, bottom = 6.dp),
            ) { Text(e.title, color = Color.White, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            UnreadBadge(badge, Modifier.align(Alignment.TopEnd).padding(5.dp))
            if (selected) SelectionMark(Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        if (!compact) {
            Spacer(Modifier.height(6.dp))
            Text(e.title, style = InkType.caption.copy(fontSize = 13.sp, letterSpacing = 0.sp), color = ink.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibraryListRow(e: Entry, badge: Int, selected: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val ink = LocalInk.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(CardRadius)).background(if (selected) ink.accent.copy(alpha = 0.18f) else ink.card)
            .pressable(onLongClick = onLong, onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(e.coverUrl, Modifier.width(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = InkType.headline, color = ink.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when (e.type) { ContentType.NOVEL -> "Novel"; else -> "Manga" },
                style = InkType.caption, color = ink.secondary,
            )
        }
        if (selected) SelectionMark() else UnreadBadge(badge)
    }
}

@Composable
private fun ManageCategoriesDialog(categories: List<LibraryCategory>, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val ink = LocalInk.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<LibraryCategory?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<LibraryCategory?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categories") },
        text = {
            Column {
                if (categories.isEmpty()) Text("No categories yet. Add one to organize your library.", style = InkType.callout, color = ink.secondary)
                categories.forEachIndexed { i, cat ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(cat.name, style = InkType.body, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        if (i > 0) BarIconButton(Icons.Outlined.KeyboardArrowUp, "Move up", tint = ink.secondary) { scope.launch { c.repo.moveCategory(cat.id, -1) } }
                        else Spacer(Modifier.size(44.dp))
                        if (i < categories.lastIndex) BarIconButton(Icons.Outlined.KeyboardArrowDown, "Move down", tint = ink.secondary) { scope.launch { c.repo.moveCategory(cat.id, 1) } }
                        else Spacer(Modifier.size(44.dp))
                        BarIconButton(Icons.Outlined.Edit, "Rename", tint = ink.secondary) { editing = cat }
                        BarIconButton(Icons.Outlined.Delete, "Delete", tint = ink.secondary) { deleting = cat }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
        dismissButton = { TextButton({ adding = true }) { Text("Add category") } },
    )
    if (adding) NameDialog("New category", "", { adding = false }) { n -> scope.launch { c.repo.addCategory(n) }; adding = false }
    editing?.let { cat -> NameDialog("Rename category", cat.name, { editing = null }) { n -> scope.launch { c.repo.renameCategory(cat, n) }; editing = null } }
    deleting?.let { cat ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete \"${cat.name}\"?") }, text = { Text("Titles in it move to Uncategorized.") },
            confirmButton = { TextButton({ scope.launch { c.repo.deleteCategory(cat.id) }; deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onDone(text.trim()) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

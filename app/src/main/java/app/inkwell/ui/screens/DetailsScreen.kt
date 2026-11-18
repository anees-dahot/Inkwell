package app.inkwell.ui.screens

import androidx.compose.runtime.saveable.rememberSaveable
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MarkChatRead
import androidx.compose.material.icons.outlined.MarkChatUnread
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import app.inkwell.data.db.DlStatus
import app.inkwell.data.db.DownloadItem
import app.inkwell.data.db.LibraryCategory
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.inkwell.AppContainer
import app.inkwell.data.model.ContentType
import app.inkwell.data.model.Entry
import app.inkwell.data.model.EntryDetails
import app.inkwell.data.model.Episode
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.CardRadius
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import coil3.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DetailsViewModel(
    private val c: AppContainer,
    val sourceId: String,
    val entryId: String,
    title: String,
    coverUrl: String?,
) : ViewModel() {
    private val source = c.registry.get(sourceId)
    val baseEntry = Entry(sourceId, entryId, title, coverUrl, source.type)
    var details by mutableStateOf<EntryDetails?>(null); private set
    var episodes by mutableStateOf<List<Episode>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    /** Same title found on other sources (used when this one has no chapters, e.g. licensed). */
    var alternatives by mutableStateOf<List<Entry>>(emptyList()); private set
    var findingAlternatives by mutableStateOf(false); private set

    val inLibrary = c.repo.isInLibrary(sourceId, entryId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val progress = c.repo.progress(sourceId, entryId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = c.repo.historyFor(sourceId, entryId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val downloads = c.repo.downloadsFor(sourceId, entryId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories = c.repo.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // chapter list view state
    var newestFirst by mutableStateOf(true)
    var onlyUnread by mutableStateOf(false)
    var onlyDownloaded by mutableStateOf(false)
    var selected by mutableStateOf<Set<String>>(emptySet())

    val entry: Entry
        get() = details?.entry?.copy(coverUrl = baseEntry.coverUrl ?: details?.entry?.coverUrl, title = baseEntry.title.ifBlank { details?.entry?.title ?: "" }) ?: baseEntry
    val isVideo get() = false

    init { load() }

    fun load() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                details = c.repo.details(sourceId, entryId)
                episodes = c.repo.episodes(sourceId, entryId, force = true)
                if (c.repo.isLibraryEntry(sourceId, entryId)) c.repo.cacheEpisodes(sourceId, entryId, episodes)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load" }
            finally { loading = false }
            if (episodes.isEmpty()) findAlternatives()
        }
    }

    private suspend fun findAlternatives() {
        val title = entry.title.ifBlank { return }
        val key = app.inkwell.data.source.titleKey(title)
        if (key.isEmpty()) return
        val others = c.registry.sources.filter { it.type == source.type && it.id != sourceId && !app.inkwell.data.source.SourceHealth.benched(it.id) }
        findingAlternatives = true
        val found = ArrayList<Entry>()
        app.inkwell.data.source.fanOut(others, parallel = 8, timeoutMs = 15_000, op = { it.search(title, 1) }) { _, r ->
            val hit = r?.items?.firstOrNull { app.inkwell.data.source.titleKey(it.title) == key }
                ?: r?.items?.firstOrNull { val k = app.inkwell.data.source.titleKey(it.title); k.length > 3 && (k.contains(key) || key.contains(k)) }
            if (hit != null) { found += hit; alternatives = found.toList() }
        }
        findingAlternatives = false
    }

    fun sourceName(id: String) = c.registry.find(id)?.name?.substringBefore(" (") ?: id

    val remind = c.repo.observeRemind(sourceId, entryId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleRemind(ctx: android.content.Context) {
        viewModelScope.launch {
            c.repo.setRemind(sourceId, entryId, !remind.value)
            app.inkwell.work.Scheduler.syncExpected(ctx.applicationContext)
        }
    }

    /** Chapters oldest -> newest, whatever order the source returns them in. */
    val readingOrder: List<Episode>
        get() {
            val e = episodes
            return if (e.size > 1 && e.all { it.number != null }) e.sortedBy { it.number } else e.asReversed()
        }

    fun markAllRead() { viewModelScope.launch { c.repo.markAllRead(sourceId, entryId, episodes) } }

    fun addToLibrary(categoryId: Long) { viewModelScope.launch { c.repo.addToLibrary(entry, categoryId) } }
    fun removeFromLibrary() { viewModelScope.launch { c.repo.removeFromLibrary(sourceId, entryId) } }

    fun shareUrl(): String? {
        val src = source
        return when {
            src is app.inkwell.data.source.MangaDexSource -> "https://mangadex.org/title/$entryId"
            src is app.inkwell.data.source.ScraperSource -> when {
                entryId.startsWith("http") -> entryId
                entryId.startsWith("/") -> src.baseUrl.trimEnd('/') + entryId
                else -> src.baseUrl
            }
            else -> null
        }
    }

    val readIds: Set<String> get() = progress.value.filter { it.read }.map { it.episodeId }.toSet()

    fun markRead(ids: Collection<String>, read: Boolean) {
        viewModelScope.launch { c.repo.markRead(sourceId, entryId, episodes.filter { it.id in ids }, read) }
    }

    private fun queue(list: List<Episode>, done: (Int) -> Unit) {
        viewModelScope.launch { done(c.repo.enqueueDownloads(entry, list)) }
    }
    fun downloadEpisodes(ids: Collection<String>, done: (Int) -> Unit) = queue(readingOrder.filter { it.id in ids }, done)
    fun downloadNext(n: Int, done: (Int) -> Unit) {
        val order = readingOrder
        val have = downloads.value.map { it.episodeId }.toSet()
        val read = readIds
        val start = resumeTarget()?.first?.let { t -> order.indexOfFirst { it.id == t.id } }?.coerceAtLeast(0) ?: 0
        queue(order.drop(start).filter { it.id !in read && it.id !in have }.take(n), done)
    }
    fun downloadUnread(done: (Int) -> Unit) { val r = readIds; queue(readingOrder.filter { it.id !in r }, done) }
    fun downloadAll(done: (Int) -> Unit) = queue(readingOrder, done)
    fun deleteDownloads(ids: Collection<String>) {
        viewModelScope.launch { downloads.value.filter { it.episodeId in ids }.let { c.repo.deleteDownloads(it) } }
    }
    fun deleteAllDownloads() { viewModelScope.launch { c.repo.deleteEntryDownloads(sourceId, entryId) } }
    fun cancelDownload(item: DownloadItem) { viewModelScope.launch { c.repo.cancelDownload(item) } }

    /** Episode to resume: last opened (if unfinished), the next newer one if finished, else the oldest. */
    fun resumeTarget(): Pair<Episode, Boolean>? {
        val order = readingOrder
        if (order.isEmpty()) return null
        val h = history.value
        if (h != null) {
            val idx = order.indexOfFirst { it.id == h.episodeId }
            if (idx >= 0) {
                val done = progress.value.firstOrNull { it.episodeId == h.episodeId }?.read == true
                return if (done && idx < order.lastIndex) order[idx + 1] to true else order[idx] to !done
            }
        }
        return order.first() to false
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailsScreen(
    sourceId: String, entryId: String, title: String, coverUrl: String?,
    onBack: () -> Unit, onOpenEpisode: (Entry, Episode) -> Unit,
    onOpenEntry: (Entry) -> Unit = {},
) {
    val vm = inkViewModel("details/$sourceId/$entryId") { DetailsViewModel(it, sourceId, entryId, title, coverUrl) }
    val ink = LocalInk.current
    val hazeState = remember { HazeState() }
    val list = rememberLazyListState()
    val collapsed by list.rememberCollapsed(thresholdDp = 220.dp)
    val inLib by vm.inLibrary.collectAsStateWithLifecycle()
    val remind by vm.remind.collectAsStateWithLifecycle()
    val askNotif = rememberNotificationPermission()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val reduced = LocalReducedMotion.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    val readIds = remember(progress) { progress.filter { it.read }.map { it.episodeId }.toSet() }
    val entry = vm.entry
    val resume = remember(vm.episodes, history, progress) { vm.resumeTarget() }
    val top = topContentPadding()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val dlMap = remember(downloads) { downloads.associateBy { it.episodeId } }
    val shown = remember(vm.episodes, vm.newestFirst, vm.onlyUnread, vm.onlyDownloaded, readIds, dlMap) {
        var l = vm.readingOrder.asReversed()
        if (vm.onlyUnread) l = l.filter { it.id !in readIds }
        if (vm.onlyDownloaded) l = l.filter { dlMap[it.id]?.status == DlStatus.DONE }
        if (vm.newestFirst) l else l.asReversed()
    }
    val selecting = vm.selected.isNotEmpty()
    val canDownload = !vm.isVideo
    val toast = { n: Int -> android.widget.Toast.makeText(context, if (n == 0) "Nothing to download" else "Queued $n chapter${if (n == 1) "" else "s"}", android.widget.Toast.LENGTH_SHORT).show() }
    val startDl: (((Int) -> Unit) -> Unit) -> Unit = { run -> askNotif(); run(toast) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var showMigrate by remember { mutableStateOf(false) }
    val stuck by remember { derivedStateOf { list.firstVisibleItemIndex >= 1 } }
    val stickyTop by androidx.compose.animation.core.animateDpAsState(if (stuck) top else 0.dp, label = "stickyTop")
    androidx.activity.compose.BackHandler(selecting) { vm.selected = emptySet() }

    Box(Modifier.fillMaxSize().background(ink.bg)) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().hazeSource(hazeState),
            contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp),
        ) {
            item {
                Box(Modifier.fillMaxWidth()) {
                    if (entry.coverUrl != null) {
                        AsyncImage(
                            entry.coverUrl, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.matchParentSize().blur(40.dp).alpha(0.55f),
                        )
                    }
                    Box(
                        Modifier.matchParentSize().background(
                            Brush.verticalGradient(listOf(ink.bg.copy(alpha = 0.2f), ink.bg))
                        )
                    )
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = top + 12.dp, bottom = 16.dp)) {
                        Row {
                            Cover(entry.coverUrl, Modifier.width(128.dp).sharedCover("cover-$sourceId-$entryId"))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f).padding(top = 4.dp)) {
                                Text(entry.title, style = InkType.title, color = ink.label, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                vm.details?.authors?.takeIf { it.isNotEmpty() }?.let {
                                    Spacer(Modifier.height(6.dp))
                                    Text(it.joinToString(", "), style = InkType.callout, color = ink.secondary, maxLines = 2)
                                }
                                vm.details?.status?.let {
                                    Spacer(Modifier.height(2.dp))
                                    Text(it, style = InkType.callout, color = ink.accent)
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledPill(if (inLib) "In Library" else "Add to Library", filled = !inLib) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (inLib) vm.removeFromLibrary()
                                else if (categories.isNotEmpty()) showCategoryPicker = true
                                else vm.addToLibrary(0)
                            }
                            if (inLib) ReminderBell(remind) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (!remind) askNotif()
                                vm.toggleRemind(context)
                            }
                        }
                        resume?.let { (ep, resuming) ->
                            Spacer(Modifier.height(10.dp))
                            val label = ep.number?.let { "Ch. " + app.inkwell.work.Notifier.fmt(it) } ?: ep.name
                            FilledPill(
                                (if (resuming) "Resume " else "Start ") + (if (vm.isVideo) label.replace("Ch.", "Ep.") else label),
                                Modifier.fillMaxWidth(),
                            ) { onOpenEpisode(entry, ep) }
                        }
                        vm.details?.genres?.takeIf { it.isNotEmpty() }?.let { g ->
                            Spacer(Modifier.height(16.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                g.take(12).forEach { Chip(it) }
                            }
                        }
                        vm.details?.description?.takeIf { it.isNotBlank() }?.let { d ->
                            Spacer(Modifier.height(16.dp))
                            Text(
                                d, style = InkType.callout, color = ink.label,
                                maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.animateContentSize(Motion.Default.specFor(reduced))
                                    .pressable { expanded = !expanded },
                            )
                            Row(
                                Modifier.pressable { expanded = !expanded }.padding(top = 4.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(if (expanded) "Less" else "More", style = InkType.callout, color = ink.accent)
                                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = ink.accent, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            stickyHeader(key = "chapters-header") {
                Row(Modifier.fillMaxWidth().background(ink.bg).padding(top = stickyTop).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (vm.isVideo) "Episodes" else "Chapters") + if (vm.episodes.isNotEmpty()) " (${vm.episodes.size})" else "",
                        style = InkType.headline, color = ink.label, modifier = Modifier.weight(1f),
                    )
                    if (vm.episodes.isNotEmpty()) {
                        Box {
                            BarIconButton(Icons.Outlined.FilterList, "Sort and filter", tint = if (vm.onlyUnread || vm.onlyDownloaded || !vm.newestFirst) ink.accent else ink.secondary) { menu = "filter" }
                            DropdownMenu(menu == "filter", { menu = null }) {
                                DropdownMenuItem({ Text(if (vm.newestFirst) "Newest first  \u2713" else "Newest first") }, { vm.newestFirst = true; menu = null })
                                DropdownMenuItem({ Text(if (!vm.newestFirst) "Oldest first  \u2713" else "Oldest first") }, { vm.newestFirst = false; menu = null })
                                DropdownMenuItem({ Text(if (vm.onlyUnread) "Unread only  \u2713" else "Unread only") }, { vm.onlyUnread = !vm.onlyUnread; menu = null })
                                if (canDownload) DropdownMenuItem({ Text(if (vm.onlyDownloaded) "Downloaded only  \u2713" else "Downloaded only") }, { vm.onlyDownloaded = !vm.onlyDownloaded; menu = null })
                            }
                        }
                        if (canDownload) Box {
                            BarIconButton(Icons.Outlined.Download, "Download", tint = ink.secondary) { menu = "download" }
                            DropdownMenu(menu == "download", { menu = null }) {
                                DropdownMenuItem({ Text("Next 5 chapters") }, { menu = null; startDl { vm.downloadNext(5, it) } })
                                DropdownMenuItem({ Text("Next 10 chapters") }, { menu = null; startDl { vm.downloadNext(10, it) } })
                                DropdownMenuItem({ Text("Unread") }, { menu = null; startDl { vm.downloadUnread(it) } })
                                DropdownMenuItem({ Text("All") }, { menu = null; startDl { vm.downloadAll(it) } })
                                if (downloads.isNotEmpty()) DropdownMenuItem({ Text("Delete downloads") }, { menu = null; confirmDeleteAll = true })
                            }
                        }
                    }
                    if (vm.episodes.isNotEmpty() || inLib) Box {
                        BarIconButton(Icons.Outlined.MoreVert, "More", tint = ink.secondary) { menu = "more" }
                        DropdownMenu(menu == "more", { menu = null }) {
                            if (vm.episodes.isNotEmpty()) {
                                DropdownMenuItem({ Text("Mark all read") }, { menu = null; vm.markAllRead() })
                                DropdownMenuItem({ Text("Mark all unread") }, { menu = null; vm.markRead(vm.episodes.map { it.id }, false) })
                            }
                            if (inLib) DropdownMenuItem({ Text("Migrate to another source") }, { menu = null; showMigrate = true })
                        }
                    }
                }
            }
            if (vm.error != null && vm.episodes.isEmpty()) {
                item { ErrorState(vm.error!!, vm::load, webViewUrl = (LocalContainer.current.registry.find(sourceId) as? app.inkwell.data.source.ScraperSource)?.baseUrl) }
            } else if (vm.loading && vm.episodes.isEmpty()) {
                items(6) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(56.dp).clip(RoundedCornerShape(CardRadius)).shimmer())
                }
            } else if (vm.episodes.isEmpty()) {
                val official = vm.details?.officialUrl
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        val msg = when {
                            vm.alternatives.isNotEmpty() -> "Not on ${vm.sourceName(sourceId)}. Read it from another source:"
                            vm.findingAlternatives -> "Not on ${vm.sourceName(sourceId)}. Looking on other sources…"
                            official != null -> "This title is licensed and no other source has it."
                            else -> "No chapters available on any source."
                        }
                        Text(msg, style = InkType.callout, color = ink.secondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        if (vm.findingAlternatives) LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), color = ink.accent)
                    }
                }
                items(vm.alternatives, key = { "alt/" + it.sourceId + "/" + it.id }) { alt ->
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(CardRadius))
                            .background(ink.secondary.copy(alpha = 0.10f)).pressable { onOpenEntry(alt) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Cover(alt.coverUrl, Modifier.width(40.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(alt.title, style = InkType.body, color = ink.label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(vm.sourceName(alt.sourceId), style = InkType.caption, color = ink.secondary)
                        }
                    }
                }
                if (official != null && !vm.findingAlternatives) item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "Read officially", color = ink.accent, style = InkType.headline,
                            modifier = Modifier.clip(RoundedCornerShape(CardRadius)).pressable {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(official)))
                            }.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
            itemsIndexed(shown, key = { _, e -> e.id }) { i, ep ->
                val first = i == 0
                val last = i == shown.lastIndex
                val shape = RoundedCornerShape(
                    topStart = if (first) CardRadius else 0.dp, topEnd = if (first) CardRadius else 0.dp,
                    bottomStart = if (last) CardRadius else 0.dp, bottomEnd = if (last) CardRadius else 0.dp,
                )
                val read = ep.id in readIds
                val isSel = ep.id in vm.selected
                val dl = dlMap[ep.id]
                Column(Modifier.padding(horizontal = 16.dp).clip(shape).background(if (isSel) ink.accent.copy(alpha = 0.18f) else ink.card)) {
                    Row(
                        Modifier.fillMaxWidth().pressable(onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            vm.selected = if (isSel) vm.selected - ep.id else vm.selected + ep.id
                        }) {
                            if (selecting) vm.selected = if (isSel) vm.selected - ep.id else vm.selected + ep.id
                            else onOpenEpisode(entry, ep)
                        }.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).alpha(if (read) 0.5f else 1f)) {
                            Text(ep.name, style = InkType.body.copy(fontSize = 16.sp), color = ink.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(ep.dateMillis?.let { relativeDate(it) }, ep.scanlator).joinToString(" • ")
                            if (sub.isNotEmpty()) Text(sub, style = InkType.caption, color = ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (read) Icon(Icons.Outlined.CheckCircle, "Read", tint = ink.secondary, modifier = Modifier.padding(horizontal = 6.dp).size(20.dp))
                        if (canDownload) Box {
                            val id = ep.id
                            Box(
                                Modifier.size(44.dp).pressable {
                                    when (dl?.status) {
                                        null -> { askNotif(); vm.downloadEpisodes(listOf(id)) { toast(it) } }
                                        else -> menu = "ep-$id"
                                    }
                                },
                                contentAlignment = Alignment.Center,
                            ) {
                                when (dl?.status) {
                                    null -> Icon(Icons.Outlined.Download, "Download", tint = ink.secondary, modifier = Modifier.size(22.dp))
                                    DlStatus.DONE -> Icon(Icons.Outlined.DownloadDone, "Downloaded", tint = ink.accent, modifier = Modifier.size(22.dp))
                                    DlStatus.DOWNLOADING -> CircularProgressIndicator(
                                        progress = { if (dl.total > 0) dl.progress / dl.total.toFloat() else 0f },
                                        modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp, color = ink.accent,
                                    )
                                    DlStatus.ERROR -> Icon(Icons.Outlined.ErrorOutline, "Failed", tint = ink.accent, modifier = Modifier.size(22.dp))
                                    DlStatus.PAUSED -> Icon(Icons.Outlined.Pause, "Paused", tint = ink.secondary, modifier = Modifier.size(22.dp))
                                    else -> Icon(Icons.Outlined.Schedule, "Queued", tint = ink.secondary, modifier = Modifier.size(22.dp))
                                }
                            }
                            DropdownMenu(menu == "ep-$id", { menu = null }) {
                                if (dl != null) DropdownMenuItem(
                                    { Text(if (dl.status == DlStatus.DONE) "Delete download" else if (dl.status == DlStatus.ERROR) "Remove" else "Cancel download") },
                                    { menu = null; vm.cancelDownload(dl) },
                                )
                                if (dl?.status == DlStatus.ERROR) DropdownMenuItem(
                                    { Text("Retry") },
                                    { menu = null; vm.cancelDownload(dl); vm.downloadEpisodes(listOf(id)) { toast(it) } },
                                )
                            }
                        } else Spacer(Modifier.width(12.dp))
                    }
                    if (!last) Hairline()
                }
            }
            if (shown.isEmpty() && vm.episodes.isNotEmpty()) item {
                EmptyState(Icons.Outlined.Inbox, "No chapters match the current filter.")
            }
        }
        if (selecting) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(ink.card)
                .padding(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).height(56.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            val sel = vm.selected
            val clear = { vm.selected = emptySet() }
            BarIconButton(Icons.Outlined.MarkChatRead, "Mark read") { vm.markRead(sel, true); clear() }
            BarIconButton(Icons.Outlined.MarkChatUnread, "Mark unread") { vm.markRead(sel, false); clear() }
            if (canDownload) {
                BarIconButton(Icons.Outlined.Download, "Download") { askNotif(); vm.downloadEpisodes(sel) { toast(it) }; clear() }
                BarIconButton(Icons.Outlined.Delete, "Delete downloads") { vm.deleteDownloads(sel); clear() }
            }
        }
        GlassTopBar(
            if (selecting) "${vm.selected.size} selected" else entry.title, collapsed || selecting, hazeState, Modifier.align(Alignment.TopCenter),
            titleInset = 100.dp,
            leading = {
                if (selecting) BarIconButton(Icons.Outlined.Close, "Cancel selection") { vm.selected = emptySet() }
                else BarIconButton(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", onClick = onBack)
            },
            trailing = {
                if (selecting) {
                    BarIconButton(Icons.Outlined.SelectAll, "Select all") { vm.selected = shown.map { it.id }.toSet() }
                    return@GlassTopBar
                }
                vm.shareUrl()?.let { url ->
                    BarIconButton(Icons.Outlined.Share, "Share") {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, entry.title).putExtra(Intent.EXTRA_TEXT, entry.title + "\n" + url)
                        app.inkwell.AppLock.skipNextLock = true; context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                val openWebView = LocalOpenWebView.current
                val base = (LocalContainer.current.registry.find(sourceId) as? app.inkwell.data.source.ScraperSource)?.baseUrl
                if (base != null) BarIconButton(Icons.Outlined.Language, "Open in WebView") { openWebView(base) }
            },
        )
        OfflineBanner(Modifier.align(Alignment.TopCenter))
    }
    if (showCategoryPicker) CategoryPickerDialog(
        "Add to category", categories,
        onPick = { showCategoryPicker = false; vm.addToLibrary(it) }, onDismiss = { showCategoryPicker = false },
    )
    if (showMigrate) MigrateDialog(
        MigrateTarget(sourceId, entryId, entry.title, entry.coverUrl, entry.type),
        onDismiss = { showMigrate = false },
        onMigrated = { _, _ -> showMigrate = false; onBack() },
    )
    if (confirmDeleteAll) AlertDialog(
        onDismissRequest = { confirmDeleteAll = false },
        title = { Text("Delete downloads?") }, text = { Text("Removes all downloaded and queued chapters of this title.") },
        confirmButton = { TextButton({ confirmDeleteAll = false; vm.deleteAllDownloads() }) { Text("Delete") } },
        dismissButton = { TextButton({ confirmDeleteAll = false }) { Text("Cancel") } },
    )
}

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Icon
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

    val inLibrary = c.repo.isInLibrary(sourceId, entryId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val progress = c.repo.progress(sourceId, entryId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = c.repo.historyFor(sourceId, entryId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val entry: Entry
        get() = details?.entry?.copy(coverUrl = baseEntry.coverUrl ?: details?.entry?.coverUrl, title = baseEntry.title.ifBlank { details?.entry?.title ?: "" }) ?: baseEntry
    val isVideo get() = source.type == ContentType.VIDEO
    fun openUrl(): String = c.youtube.openUrl(entryId)

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
        }
    }

    val remind = c.repo.observeRemind(sourceId, entryId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun toggleRemind(ctx: android.content.Context) {
        viewModelScope.launch {
            c.repo.setRemind(sourceId, entryId, !remind.value)
            app.inkwell.work.Scheduler.syncExpected(ctx.applicationContext)
        }
    }

    fun markAllRead() { viewModelScope.launch { c.repo.markAllRead(sourceId, entryId, episodes) } }

    fun toggleLibrary() {
        viewModelScope.launch {
            if (inLibrary.value) c.repo.removeFromLibrary(sourceId, entryId) else c.repo.addToLibrary(entry)
        }
    }

    /** Episode to resume: last opened (if unfinished), the next newer one if finished, else the oldest. */
    fun resumeTarget(): Pair<Episode, Boolean>? {
        if (episodes.isEmpty()) return null
        val h = history.value
        if (h != null) {
            val idx = episodes.indexOfFirst { it.id == h.episodeId }
            if (idx >= 0) {
                val done = progress.value.firstOrNull { it.episodeId == h.episodeId }?.read == true
                return if (done && idx > 0) episodes[idx - 1] to true else episodes[idx] to !done
            }
        }
        return episodes.last() to false
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun DetailsScreen(
    sourceId: String, entryId: String, title: String, coverUrl: String?,
    onBack: () -> Unit, onOpenEpisode: (Entry, Episode) -> Unit,
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
                                vm.toggleLibrary()
                            }
                            if (inLib) ReminderBell(remind) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (!remind) askNotif()
                                vm.toggleRemind(context)
                            }
                            resume?.let { (ep, resuming) ->
                                FilledPill(if (resuming) "Resume" else "Start", filled = true) { onOpenEpisode(entry, ep) }
                            }
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
                        }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (vm.isVideo) "Episodes" else "Chapters") + if (vm.episodes.isNotEmpty()) " (${vm.episodes.size})" else "",
                        style = InkType.headline, color = ink.label, modifier = Modifier.weight(1f),
                    )
                    if (vm.episodes.isNotEmpty()) Text(
                        "Mark all read", style = InkType.callout, color = ink.accent,
                        modifier = Modifier.pressable { vm.markAllRead() }.padding(vertical = 4.dp),
                    )
                }
                if (vm.isVideo) {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Showing latest uploads.", style = InkType.callout, color = ink.secondary)
                        FilledPill("Open full playlist in YouTube", filled = false) {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(vm.openUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                        Spacer(Modifier.height(4.dp))
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
                        EmptyState(Icons.Outlined.Inbox, if (official != null) "This title is licensed. Read it on the official site." else "No ${if (vm.isVideo) "episodes" else "chapters"} available.")
                        if (official != null) {
                            Text(
                                "Read officially", color = ink.accent, style = InkType.headline,
                                modifier = Modifier.clip(RoundedCornerShape(CardRadius)).pressable {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(official)))
                                }.padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
            itemsIndexed(vm.episodes, key = { _, e -> e.id }) { i, ep ->
                val first = i == 0
                val last = i == vm.episodes.lastIndex
                val shape = RoundedCornerShape(
                    topStart = if (first) CardRadius else 0.dp, topEnd = if (first) CardRadius else 0.dp,
                    bottomStart = if (last) CardRadius else 0.dp, bottomEnd = if (last) CardRadius else 0.dp,
                )
                val read = ep.id in readIds
                Column(Modifier.padding(horizontal = 16.dp).clip(shape).background(ink.card)) {
                    Row(
                        Modifier.fillMaxWidth().pressable { onOpenEpisode(entry, ep) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).alpha(if (read) 0.5f else 1f)) {
                            Text(ep.name, style = InkType.body.copy(fontSize = 16.sp), color = ink.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val sub = listOfNotNull(ep.dateMillis?.let { relativeDate(it) }, ep.scanlator).joinToString(" • ")
                            if (sub.isNotEmpty()) Text(sub, style = InkType.caption, color = ink.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (read) Icon(Icons.Outlined.CheckCircle, "Read", tint = ink.secondary, modifier = Modifier.size(20.dp))
                    }
                    if (!last) Hairline()
                }
            }
        }
        GlassTopBar(
            entry.title, collapsed, hazeState, Modifier.align(Alignment.TopCenter),
            leading = { BarIconButton(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", onClick = onBack) },
            trailing = {
                val openWebView = LocalOpenWebView.current
                val base = (LocalContainer.current.registry.find(sourceId) as? app.inkwell.data.source.ScraperSource)?.baseUrl
                if (base != null) BarIconButton(Icons.Outlined.Language, "Open in WebView") { openWebView(base) }
            },
        )
    }
}

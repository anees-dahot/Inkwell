package app.inkwell.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkwell.AppContainer
import app.inkwell.data.model.Entry
import app.inkwell.data.model.Episode
import app.inkwell.data.model.NovelSource
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class NovelReaderViewModel(
    private val c: AppContainer,
    private val sourceId: String,
    private val entryId: String,
    private val startEpisodeId: String,
) : ViewModel() {
    var entry by mutableStateOf<Entry?>(null); private set
    var episodes by mutableStateOf<List<Episode>>(emptyList()); private set
    var current by mutableStateOf<Episode?>(null); private set
    var paragraphs by mutableStateOf<List<String>>(emptyList()); private set
    var initialParagraph by mutableIntStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set

    init { start() }

    fun start() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val d = c.repo.details(sourceId, entryId)
                entry = d.entry
                episodes = c.repo.episodes(sourceId, entryId)
                open(current?.id ?: startEpisodeId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load"; loading = false }
        }
    }

    fun open(episodeId: String) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val ep = episodes.firstOrNull { it.id == episodeId } ?: error("Chapter not found")
                val text = (c.registry.get(sourceId) as NovelSource).chapterText(episodeId)
                if (text.isEmpty()) error("This chapter has no readable content")
                val prog = c.repo.getProgress(sourceId, entryId, episodeId)
                initialParagraph = if (prog != null && !prog.read) prog.page.coerceIn(0, text.lastIndex) else 0
                current = ep
                paragraphs = text
                c.repo.saveProgress(entry!!, ep, initialParagraph, text.size, false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load chapter" }
            finally { loading = false }
        }
    }

    fun onScroll(lastVisible: Int) {
        val ep = current ?: return
        val en = entry ?: return
        viewModelScope.launch {
            c.repo.saveProgress(en, ep, lastVisible, paragraphs.size, lastVisible >= paragraphs.lastIndex)
        }
    }

    private fun idx() = episodes.indexOfFirst { it.id == current?.id }
    fun nextEpisode(): Episode? = idx().takeIf { it > 0 }?.let { episodes[it - 1] }
    fun prevEpisode(): Episode? = idx().takeIf { it >= 0 && it < episodes.lastIndex }?.let { episodes[it + 1] }
}

@Composable
fun NovelReaderScreen(sourceId: String, entryId: String, episodeId: String, onBack: () -> Unit) {
    val vm = inkViewModel("novelreader/$sourceId/$entryId") { NovelReaderViewModel(it, sourceId, entryId, episodeId) }
    val ink = LocalInk.current
    val reduced = LocalReducedMotion.current
    var overlay by rememberSaveable { mutableStateOf(true) }
    val hazeState = remember { HazeState() }
    val activity = LocalActivity.current
    val view = LocalView.current

    DisposableEffect(Unit) {
        view.keepScreenOn = true
        val ctl = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val prevLight = ctl?.isAppearanceLightStatusBars
        ctl?.isAppearanceLightStatusBars = false
        onDispose {
            view.keepScreenOn = false
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            if (prevLight != null) ctl.isAppearanceLightStatusBars = prevLight
        }
    }

    Box(Modifier.fillMaxSize().background(ink.bg)) {
        val ep = vm.current
        when {
            vm.error != null && vm.paragraphs.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                ErrorState(vm.error!!, webViewUrl = (LocalContainer.current.registry.find(sourceId) as? app.inkwell.data.source.ScraperSource)?.baseUrl, onRetry = { if (vm.episodes.isEmpty()) vm.start() else vm.open(ep?.id ?: episodeId) })
            }
            vm.loading || ep == null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(400.dp).shimmer())
            }
            else -> key(ep.id) {
                val list = rememberLazyListState(initialFirstVisibleItemIndex = vm.initialParagraph)
                val haptic = LocalHapticFeedback.current
                var lastSaved by remember { mutableIntStateOf(-1) }

                LaunchedEffect(list) {
                    snapshotFlow { list.firstVisibleItemIndex to (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
                        .collect { (first, last) ->
                            val idx = if (first >= vm.paragraphs.size) vm.paragraphs.lastIndex else last.coerceAtMost(vm.paragraphs.lastIndex)
                            if (idx != lastSaved) { lastSaved = idx; vm.onScroll(idx) }
                        }
                }
                LaunchedEffect(vm.paragraphs.size, lastSaved) {
                    if (vm.paragraphs.size > 1 && lastSaved == vm.paragraphs.lastIndex) {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }

                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().hazeSource(hazeState),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(vm.paragraphs) { i, p ->
                        Text(
                            p, style = InkType.body.copy(fontSize = 17.sp, lineHeight = 27.sp),
                            color = ink.label,
                            modifier = Modifier.padding(top = if (i == 0) (topContentPadding() + 24.dp) else 0.dp),
                        )
                    }
                    item {
                        Spacer(Modifier.height(48.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            val next = vm.nextEpisode()
                            if (next != null) FilledPill("Next chapter") { vm.open(next.id) }
                            else Text("End of available chapters", color = ink.secondary, style = InkType.callout)
                        }
                    }
                }

                val slide = remember(reduced) { Motion.Sheet.specFor<IntOffset>(reduced) }
                AnimatedVisibility(
                    overlay, Modifier.align(Alignment.TopCenter),
                    enter = (if (reduced) fadeIn() else slideInVertically(slide) { -it } + fadeIn()),
                    exit = (if (reduced) fadeOut() else slideOutVertically(slide) { -it } + fadeOut()),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Box(Modifier.matchParentSize().glass(hazeState, dark = true))
                        Column {
                            Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                            Row(Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                BarIconButton(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", Color.White, onBack)
                                Column(Modifier.weight(1f)) {
                                    Text(vm.entry?.title ?: "", color = Color.White, style = InkType.callout, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(ep.name, color = Color.White.copy(alpha = 0.6f), style = InkType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    overlay, Modifier.align(Alignment.BottomCenter),
                    enter = (if (reduced) fadeIn() else slideInVertically(slide) { it } + fadeIn()),
                    exit = (if (reduced) fadeOut() else slideOutVertically(slide) { it } + fadeOut()),
                ) {
                    Box(Modifier.fillMaxWidth()) {
                        Box(Modifier.matchParentSize().glass(hazeState, dark = true))
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            val prev = vm.prevEpisode()
                            val next = vm.nextEpisode()
                            BarIconButton(Icons.Outlined.SkipPrevious, "Previous chapter", if (prev != null) Color.White else Color.White.copy(0.3f)) { prev?.let { vm.open(it.id) } }
                            Text(
                                "${(lastSaved.coerceAtLeast(0) + 1).coerceAtMost(vm.paragraphs.size)} / ${vm.paragraphs.size}",
                                color = Color.White.copy(alpha = 0.8f), style = InkType.caption,
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            BarIconButton(Icons.Outlined.SkipNext, "Next chapter", if (next != null) Color.White else Color.White.copy(0.3f)) { next?.let { vm.open(it.id) } }
                            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                        }
                    }
                }
            }
        }
    }
}

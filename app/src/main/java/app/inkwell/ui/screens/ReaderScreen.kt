package app.inkwell.ui.screens

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkwell.AppContainer
import app.inkwell.data.ReaderDirection
import app.inkwell.data.ReaderMode
import app.inkwell.data.model.Entry
import app.inkwell.data.model.Episode
import app.inkwell.data.model.MangaSource
import app.inkwell.data.model.Page
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import coil3.SingletonImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class ReaderViewModel(
    private val c: AppContainer,
    private val sourceId: String,
    private val entryId: String,
    private val startEpisodeId: String,
) : ViewModel() {
    var entry by mutableStateOf<Entry?>(null); private set
    var genres by mutableStateOf<List<String>>(emptyList()); private set
    var episodes by mutableStateOf<List<Episode>>(emptyList()); private set
    var current by mutableStateOf<Episode?>(null); private set
    var pages by mutableStateOf<List<Page>>(emptyList()); private set
    var initialPage by mutableIntStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set

    init { start() }

    fun start() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val d = c.repo.details(sourceId, entryId)
                entry = d.entry; genres = d.genres
                episodes = c.repo.episodes(sourceId, entryId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load"; loading = false; return@launch }
            open(current?.id ?: startEpisodeId)
        }
    }

    fun open(episodeId: String) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val ep = episodes.firstOrNull { it.id == episodeId } ?: error("Chapter not found")
                val pg = (c.registry.get(sourceId) as MangaSource).pages(episodeId)
                if (pg.isEmpty()) error("This chapter has no pages")
                val prog = c.repo.getProgress(sourceId, entryId, episodeId)
                initialPage = if (prog != null && !prog.read) prog.page.coerceIn(0, pg.lastIndex) else 0
                current = ep
                pages = pg
                c.repo.saveProgress(entry!!, ep, initialPage, pg.size, false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load pages" }
            finally { loading = false }
        }
    }

    fun onPage(index: Int, lastVisible: Int) {
        val ep = current ?: return
        val en = entry ?: return
        viewModelScope.launch { c.repo.saveProgress(en, ep, index, pages.size, lastVisible >= pages.lastIndex) }
    }

    /** Index in [episodes] (newest first). */
    private fun idx() = episodes.indexOfFirst { it.id == current?.id }
    fun nextEpisode(): Episode? = idx().takeIf { it > 0 }?.let { episodes[it - 1] }
    fun prevEpisode(): Episode? = idx().takeIf { it >= 0 && it < episodes.lastIndex }?.let { episodes[it + 1] }

    fun defaultMode(): Int {
        val s = c.settings
        val rtl = s.readerDirection.value == ReaderDirection.RTL
        return when (s.readerMode.value) {
            ReaderMode.WEBTOON -> 0
            ReaderMode.PAGED -> if (rtl) 2 else 1
            ReaderMode.AUTO -> if (genres.any { it.equals("Long Strip", true) }) 0 else if (rtl) 2 else 1
        }
    }
}

private val MODE_LABELS = listOf("Webtoon", "Paged →", "Paged ←")

@Composable
fun ReaderScreen(sourceId: String, entryId: String, episodeId: String, onBack: () -> Unit) {
    val vm = inkViewModel("reader/$sourceId/$entryId") { ReaderViewModel(it, sourceId, entryId, episodeId) }
    val ink = LocalInk.current
    val reduced = LocalReducedMotion.current
    var overlay by rememberSaveable { mutableStateOf(true) }
    var modeOverride by rememberSaveable { mutableStateOf<Int?>(null) }
    val hazeState = remember { HazeState() }
    val activity = LocalActivity.current
    val view = LocalView.current
    val context = LocalContext.current

    // keep screen on + dark status bar icons while reading
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
    LaunchedEffect(overlay) {
        val ctl = activity?.window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (overlay) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val ep = vm.current
        when {
            vm.error != null && vm.pages.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                ErrorState(vm.error!!, onRetry = { if (vm.episodes.isEmpty()) vm.start() else vm.open(ep?.id ?: episodeId) })
            }
            vm.loading || ep == null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.7f).shimmer())
            }
            else -> key(ep.id) {
                val mode = modeOverride ?: vm.defaultMode()
                var current by remember { mutableIntStateOf(vm.initialPage) }
                var seekTo by remember { mutableStateOf<Int?>(null) }
                val total = vm.pages.size
                val haptic = LocalHapticFeedback.current

                Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    if (mode == 0) WebtoonReader(
                        vm, current, seekTo, { overlay = !overlay },
                        onPage = { p, last -> current = p; vm.onPage(p, last) },
                        onSeekDone = { seekTo = null },
                        onNext = vm.nextEpisode()?.let { n -> { vm.open(n.id) } },
                    ) else PagedReader(
                        vm, rtl = mode == 2, seekTo = seekTo, toggle = { overlay = !overlay },
                        onPage = { p -> current = p; vm.onPage(p, p) },
                        onSeekDone = { seekTo = null },
                    )
                }

                // reached last page -> light haptic once
                LaunchedEffect(current) { if (current == total - 1 && total > 1) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
                // prefetch next ~3 pages
                LaunchedEffect(current) {
                    val loader = SingletonImageLoader.get(context)
                    for (i in 1..3) vm.pages.getOrNull(current + i)?.let {
                        loader.enqueue(ImageRequest.Builder(context).data(it.imageUrl).size(2048).build())
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
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            val rtl = mode == 2
                            var sliding by remember { mutableStateOf<Float?>(null) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val prev = vm.prevEpisode()
                                val next = vm.nextEpisode()
                                BarIconButton(Icons.Outlined.SkipPrevious, "Previous chapter", if (prev != null) Color.White else Color.White.copy(0.3f)) { prev?.let { vm.open(it.id) } }
                                CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                    Slider(
                                        value = sliding ?: current.toFloat(),
                                        onValueChange = { sliding = it },
                                        onValueChangeFinished = { sliding?.let { seekTo = it.toInt() }; sliding = null },
                                        valueRange = 0f..(total - 1).coerceAtLeast(1).toFloat(),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White, activeTrackColor = ink.accent, inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                BarIconButton(Icons.Outlined.SkipNext, "Next chapter", if (next != null) Color.White else Color.White.copy(0.3f)) { next?.let { vm.open(it.id) } }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${(sliding?.toInt() ?: current) + 1} / $total", color = Color.White.copy(alpha = 0.8f), style = InkType.caption, modifier = Modifier.padding(start = 8.dp).weight(1f))
                                Row(
                                    Modifier.pressable { modeOverride = (mode + 1) % 3 }.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(if (mode == 0) Icons.Outlined.ViewAgenda else Icons.Outlined.AutoStories, null, tint = ink.accent, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(MODE_LABELS[mode], color = ink.accent, style = InkType.callout)
                                }
                            }
                            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                        }
                    }
                }
            }
        }
        if (vm.error != null && vm.pages.isNotEmpty()) { /* transient error ignored; progress still saved */ }
    }
}

@Composable
private fun PageImage(page: Page, modifier: Modifier, fit: ContentScale, minAspect: Float? = null) {
    var attempt by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    key(attempt) {
        SubcomposeAsyncImage(
            model = ImageRequest.Builder(context).data(page.imageUrl).size(2048).build(),
            contentDescription = "Page ${page.index + 1}",
            contentScale = fit,
            modifier = modifier,
            loading = {
                Box(Modifier.fillMaxWidth().then(if (minAspect != null) Modifier.aspectRatio(minAspect) else Modifier.fillMaxHeight()).shimmer())
            },
            error = {
                Box(
                    Modifier.fillMaxWidth().then(if (minAspect != null) Modifier.aspectRatio(minAspect) else Modifier.fillMaxHeight())
                        .clickable { attempt++ },
                    contentAlignment = Alignment.Center,
                ) { Text("Failed to load page. Tap to retry.", color = Color.White.copy(0.7f), style = InkType.callout) }
            },
        )
    }
}

@Composable
private fun WebtoonReader(
    vm: ReaderViewModel, current: Int, seekTo: Int?, toggle: () -> Unit,
    onPage: (Int, Int) -> Unit, onSeekDone: () -> Unit, onNext: (() -> Unit)?,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = vm.initialPage)
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .collect { (first, last) -> if (first < vm.pages.size) onPage(first, minOf(last, vm.pages.lastIndex)) }
    }
    LaunchedEffect(seekTo) { seekTo?.let { list.scrollToItem(it); onSeekDone() } }
    LazyColumn(state = list, modifier = Modifier.fillMaxSize().zoomable(maxScale = 3f, doubleTapScale = 2f, onTap = { toggle() })) {
        itemsIndexed(vm.pages, key = { _, p -> p.index }) { _, p ->
            TiledImage(
                url = p.imageUrl, contentDescription = "Page ${p.index + 1}",
                placeholder = { Box(Modifier.fillMaxWidth().aspectRatio(0.7f).shimmer()) },
                error = { retry ->
                    Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clickable { retry() }, contentAlignment = Alignment.Center) {
                        Text("Failed to load page. Tap to retry.", color = Color.White.copy(0.7f), style = InkType.callout)
                    }
                },
            )
        }
        item {
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                if (onNext != null) FilledPill("Next chapter", onClick = onNext)
                else Text("End of available chapters", color = Color.White.copy(0.6f), style = InkType.callout)
            }
        }
    }
}

@Composable
private fun PagedReader(
    vm: ReaderViewModel, rtl: Boolean, seekTo: Int?, toggle: () -> Unit,
    onPage: (Int) -> Unit, onSeekDone: () -> Unit,
) {
    val pager = rememberPagerState(initialPage = vm.initialPage) { vm.pages.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { onPage(it) } }
    LaunchedEffect(seekTo) { seekTo?.let { pager.animateScrollToPage(it, animationSpec = Motion.Default.spec()); onSeekDone() } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = rtl,
            beyondViewportPageCount = 1,
            key = { vm.pages[it].index },
            flingBehavior = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = Motion.Bouncy.spec()),
        ) { i ->
            PageImage(
                vm.pages[i],
                Modifier.fillMaxSize().zoomable(onTap = { off ->
                    val frac = off.x / w
                    when {
                        frac < 0.25f -> scope.launch { pager.animateScrollToPage((pager.currentPage + if (rtl) 1 else -1).coerceIn(0, vm.pages.lastIndex), animationSpec = Motion.Snappy.spec()) }
                        frac > 0.75f -> scope.launch { pager.animateScrollToPage((pager.currentPage + if (rtl) -1 else 1).coerceIn(0, vm.pages.lastIndex), animationSpec = Motion.Snappy.spec()) }
                        else -> toggle()
                    }
                }),
                ContentScale.Fit,
            )
        }
    }
}

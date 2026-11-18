package app.inkwell.ui.screens.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import coil3.request.transformations
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.inkwell.data.ReaderBackground
import app.inkwell.data.ReaderScale
import app.inkwell.data.ReaderTapZones
import app.inkwell.data.ReaderViewMode
import app.inkwell.ui.components.FilledPill
import app.inkwell.ui.components.TiledImage
import app.inkwell.ui.components.shimmer
import app.inkwell.ui.components.zoomable
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.theme.InkType
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.size.Size
import kotlinx.coroutines.launch

class ReaderConfig(
    val scale: ReaderScale,
    val bg: ReaderBackground,
    val tapZones: ReaderTapZones,
    val gap: Boolean,
    val crop: Boolean = false,
) {
    val fg: Color get() = if (bg == ReaderBackground.WHITE) Color.Black else Color.White
}

/** In-memory reader state that intentionally resets when the app process restarts. */
object ReaderSession {
    /** Custom brightness (0.01..1) or -1 for system; not persisted. */
    val brightness = kotlinx.coroutines.flow.MutableStateFlow(-1f)
}

/** Lets volume keys / external code turn pages in whichever reader is on screen. */
class ReaderNav {
    var next: () -> Unit = {}
    var prev: () -> Unit = {}
}

fun pageRequest(context: android.content.Context, url: String, original: Boolean, crop: Boolean = false): ImageRequest =
    ImageRequest.Builder(context).data(url).apply {
        if (original) size(Size.ORIGINAL) else size(2048)
        if (crop) transformations(CropBorders)
    }.build()

@Composable
private fun PageImage(
    url: String, desc: String, modifier: Modifier, fit: ContentScale, cfg: ReaderConfig, minAspect: Float? = null,
    alignment: Alignment = Alignment.Center,
) {
    var attempt by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val box = Modifier.fillMaxWidth().then(if (minAspect != null) Modifier.aspectRatio(minAspect) else Modifier.fillMaxHeight())
    key(attempt) {
        SubcomposeAsyncImage(
            model = pageRequest(context, url, cfg.scale == ReaderScale.ORIGINAL, cfg.crop),
            contentDescription = desc,
            contentScale = fit,
            alignment = alignment,
            modifier = modifier,
            loading = { Box(box.shimmer()) },
            error = {
                Box(box.clickable { attempt++ }, contentAlignment = Alignment.Center) {
                    Text("Failed to load page. Tap to retry.", color = cfg.fg.copy(0.7f), style = InkType.callout)
                }
            },
        )
    }
}

@Composable
fun TransitionView(t: RItem.Trans, vm: ReaderViewModel, cfg: ReaderConfig, modifier: Modifier = Modifier) {
    val isLast = vm.chapters.lastOrNull()?.ep?.id == t.from.id
    Column(modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Finished", color = cfg.fg.copy(0.6f), style = InkType.caption)
        Text(t.from.name, color = cfg.fg, style = InkType.headline, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        if (t.to != null) {
            Text("Next", color = cfg.fg.copy(0.6f), style = InkType.caption)
            Text(t.to.name, color = cfg.fg, style = InkType.headline, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            if (isLast && vm.nextError) {
                Text("Could not load the next chapter", color = cfg.fg.copy(0.7f), style = InkType.callout)
                Spacer(Modifier.height(12.dp))
                FilledPill("Retry") { vm.loadNext() }
            } else if (isLast) {
                CircularProgressIndicator(color = cfg.fg.copy(0.7f), strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            }
        } else {
            Text("No more chapters", color = cfg.fg.copy(0.6f), style = InkType.callout)
        }
    }
}

private fun zoneAction(frac: Float, cfg: ReaderConfig): Int {
    // -1 = previous, 1 = next (LTR / top-to-bottom reading), 0 = toggle bars
    if (cfg.tapZones == ReaderTapZones.OFF) return 0
    val a = when { frac < 0.28f -> -1; frac > 0.72f -> 1; else -> 0 }
    return if (cfg.tapZones == ReaderTapZones.INVERTED) -a else a
}

/** Groups reader items into screens: single items, or two consecutive pages of one chapter. */
private fun buildSpreads(items: List<RItem>, dual: Boolean, coverAlone: Boolean): List<IntArray> {
    if (!dual) return List(items.size) { intArrayOf(it) }
    val out = ArrayList<IntArray>(items.size / 2 + 1)
    var i = 0
    while (i < items.size) {
        val a = items[i] as? RItem.Pg
        val b = items.getOrNull(i + 1) as? RItem.Pg
        if (a != null && b != null && b.ep.id == a.ep.id && !(coverAlone && a.idx == 0)) { out += intArrayOf(i, i + 1); i += 2 }
        else { out += intArrayOf(i); i++ }
    }
    return out
}

private fun List<IntArray>.spreadOf(item: Int): Int = indexOfFirst { item in it }.coerceAtLeast(0)

@Composable
fun PagedReader(
    vm: ReaderViewModel, vertical: Boolean, rtl: Boolean, startIndex: Int, seekTo: Int?, cfg: ReaderConfig, nav: ReaderNav,
    toggle: () -> Unit, onIndex: (first: Int, last: Int) -> Unit, onSeekDone: () -> Unit, onLongPress: (RItem.Pg) -> Unit,
    dual: Boolean = false, coverAlone: Boolean = true,
) {
    val items = vm.items
    val spreads = remember(items, dual, coverAlone) { buildSpreads(items, dual && !vertical, coverAlone) }
    val spreadsState = rememberUpdatedState(spreads)
    val pager = rememberPagerState(
        initialPage = spreads.spreadOf(startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))),
    ) { spreadsState.value.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage to spreadsState.value }.collect { (p, sp) ->
            sp.getOrNull(p)?.let { onIndex(it.first(), it.last()) }
        }
    }
    LaunchedEffect(seekTo) { seekTo?.let { pager.animateScrollToPage(spreads.spreadOf(it), animationSpec = Motion.Default.spec()); onSeekDone() } }
    fun go(d: Int) {
        scope.launch {
            pager.animateScrollToPage((pager.currentPage + d).coerceIn(0, (spreadsState.value.size - 1).coerceAtLeast(0)), animationSpec = Motion.Snappy.spec())
        }
    }
    SideEffect { nav.next = { go(1) }; nav.prev = { go(-1) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val fling = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = Motion.Bouncy.spec())
        val onTap: (Offset) -> Unit = { off ->
            val a = zoneAction(if (vertical) off.y / h else off.x / w, cfg)
            if (a == 0) toggle() else go(if (rtl && !vertical) -a else a)
        }
        val content: @Composable (Int) -> Unit = { p ->
            val sp = spreads.getOrNull(p)
            val first = sp?.let { vm.items.getOrNull(it.first()) }
            val second = sp?.takeIf { it.size > 1 }?.let { vm.items.getOrNull(it[1]) as? RItem.Pg }
            when {
                first is RItem.Pg && second != null -> SpreadPane(first, second, rtl, cfg, onLongPress, onTap)
                first is RItem.Pg -> PagePane(first, vertical, cfg, onLong = { onLongPress(first) }, tapHandler = onTap)
                first is RItem.Trans -> TransitionView(first, vm, cfg, Modifier.fillMaxSize().wrapContentSize().clickable(
                    indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { toggle() })
                else -> Unit
            }
        }
        val key: (Int) -> Any = { p -> spreads.getOrNull(p)?.let { vm.items.getOrNull(it.first())?.key } ?: p }
        if (vertical) VerticalPager(
            state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, flingBehavior = fling, key = key,
        ) { content(it) }
        else HorizontalPager(
            state = pager, modifier = Modifier.fillMaxSize(), reverseLayout = rtl, beyondViewportPageCount = 1, flingBehavior = fling, key = key,
        ) { content(it) }
    }
}

/** Two pages side by side; in right-to-left books the first page sits on the right. */
@Composable
private fun SpreadPane(a: RItem.Pg, b: RItem.Pg, rtl: Boolean, cfg: ReaderConfig, onLong: (RItem.Pg) -> Unit, onTap: (Offset) -> Unit) {
    val (left, right) = if (rtl) b to a else a to b
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val half = constraints.maxWidth / 2f
        Row(Modifier.fillMaxSize().zoomable(onTap = onTap, onLongPress = { off -> onLong(if (off.x < half) left else right) })) {
            PageImage(left.page.imageUrl, "Page ${left.idx + 1}", Modifier.weight(1f).fillMaxHeight(), ContentScale.Fit, cfg, alignment = Alignment.CenterEnd)
            PageImage(right.page.imageUrl, "Page ${right.idx + 1}", Modifier.weight(1f).fillMaxHeight(), ContentScale.Fit, cfg, alignment = Alignment.CenterStart)
        }
    }
}

/** Free-scrolling sideways strip (no snapping), left-to-right or right-to-left. */
@Composable
fun HorizontalStripReader(
    vm: ReaderViewModel, rtl: Boolean, startIndex: Int, seekTo: Int?, cfg: ReaderConfig, nav: ReaderNav,
    toggle: () -> Unit, onVisible: (Int, Int) -> Unit, onSeekDone: () -> Unit, onLongPress: (RItem.Pg) -> Unit,
    autoScroll: Boolean = false, speedDp: Float = 60f,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startIndex.coerceIn(0, (vm.items.size - 1).coerceAtLeast(0)))
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bounds = remember { HashMap<Int, androidx.compose.ui.geometry.Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .collect { (f, l) -> onVisible(f, l) }
    }
    LaunchedEffect(seekTo) { seekTo?.let { list.scrollToItem(it); onSeekDone() } }
    AutoScrollEffect(list, autoScroll, speedDp)
    fun scroll(dir: Int) {
        scope.launch { list.animateScrollBy(list.layoutInfo.viewportSize.width * 0.85f * dir, Motion.Default.spec()) }
    }
    SideEffect { nav.next = { scroll(1) }; nav.prev = { scroll(-1) } }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val w = constraints.maxWidth.toFloat()
        androidx.compose.foundation.lazy.LazyRow(
            state = list,
            reverseLayout = rtl,
            horizontalArrangement = Arrangement.spacedBy(if (cfg.gap) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().zoomable(
                maxScale = 3f, doubleTapScale = 2f,
                onTap = { off ->
                    val a = zoneAction(off.x / w, cfg)
                    if (a == 0) toggle() else scroll(if (rtl) -a else a)
                },
                onLongPress = { off ->
                    val hit = off + origin
                    list.layoutInfo.visibleItemsInfo.firstOrNull { bounds[it.index]?.contains(hit) == true }
                        ?.let { vm.items.getOrNull(it.index) as? RItem.Pg }?.let(onLongPress)
                },
            ),
        ) {
            itemsIndexed(vm.items, key = { _, it -> it.key }) { i, it ->
                val track = Modifier.onGloballyPositioned { c -> bounds[i] = c.boundsInRoot() }
                when (it) {
                    is RItem.Pg -> {
                        var attempt by remember { mutableIntStateOf(0) }
                        key(attempt) {
                            SubcomposeAsyncImage(
                                model = pageRequest(context, it.page.imageUrl, false, cfg.crop),
                                contentDescription = "Page ${it.idx + 1}",
                                contentScale = ContentScale.FillHeight,
                                modifier = track.fillMaxHeight(),
                                loading = { Box(Modifier.fillMaxHeight().aspectRatio(0.7f).shimmer()) },
                                error = {
                                    Box(Modifier.fillMaxHeight().aspectRatio(0.7f).clickable { attempt++ }, contentAlignment = Alignment.Center) {
                                        Text("Failed to load page.\nTap to retry.", color = cfg.fg.copy(0.7f), style = InkType.callout, textAlign = TextAlign.Center)
                                    }
                                },
                            )
                        }
                    }
                    is RItem.Trans -> TransitionView(it, vm, cfg, track.fillMaxHeight().width(300.dp).wrapContentSize())
                }
            }
        }
        if (vm.nextLoading) androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
            color = app.inkwell.ui.theme.LocalInk.current.accent, trackColor = Color.Transparent,
        )
    }
}

/** Scrolls [list] forward at [speedDp] per second while [active]; user drags still work on top of it. */
@Composable
fun AutoScrollEffect(list: androidx.compose.foundation.lazy.LazyListState, active: Boolean, speedDp: Float) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(active, speedDp) {
        if (!active) return@LaunchedEffect
        val pxPerSec = with(density) { speedDp.dp.toPx() }
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                if (last != 0L) list.dispatchRawDelta(pxPerSec * ((t - last) / 1_000_000_000f))
                last = t
            }
        }
    }
}

@Composable
private fun PagePane(
    it: RItem.Pg, vertical: Boolean, cfg: ReaderConfig, onLong: () -> Unit, tapHandler: (Offset) -> Unit,
) {
    val z = Modifier.fillMaxSize().zoomable(onTap = tapHandler, onLongPress = { onLong() })
    val desc = "Page ${it.idx + 1}"
    when {
        cfg.scale == ReaderScale.FIT_WIDTH && !vertical ->
            Box(z.verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
                PageImage(it.page.imageUrl, desc, Modifier.fillMaxWidth(), ContentScale.FillWidth, cfg, minAspect = 0.7f)
            }
        cfg.scale == ReaderScale.ORIGINAL && !vertical ->
            Box(z.verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
                PageImage(it.page.imageUrl, desc, Modifier, ContentScale.None, cfg, minAspect = 0.7f)
            }
        else -> PageImage(it.page.imageUrl, desc, z, ContentScale.Fit, cfg)
    }
}

@Composable
fun WebtoonReader(
    vm: ReaderViewModel, startIndex: Int, seekTo: Int?, cfg: ReaderConfig, nav: ReaderNav,
    toggle: () -> Unit, onVisible: (Int, Int) -> Unit, onSeekDone: () -> Unit, onLongPress: (RItem.Pg) -> Unit,
    gapDp: Dp = if (cfg.gap) 8.dp else 0.dp, sidePadding: Dp = 0.dp, autoScroll: Boolean = false, speedDp: Float = 60f,
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startIndex.coerceIn(0, (vm.items.size - 1).coerceAtLeast(0)))
    val scope = rememberCoroutineScope()
    AutoScrollEffect(list, autoScroll, speedDp)
    LaunchedEffect(list) {
        snapshotFlow { list.firstVisibleItemIndex to (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) }
            .collect { (f, l) -> onVisible(f, l) }
    }
    LaunchedEffect(seekTo) { seekTo?.let { list.scrollToItem(it); onSeekDone() } }
    fun scroll(dir: Int) {
        scope.launch { list.animateScrollBy(list.layoutInfo.viewportSize.height * 0.85f * dir, Motion.Default.spec()) }
    }
    SideEffect { nav.next = { scroll(1) }; nav.prev = { scroll(-1) } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight.toFloat()
        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = list,
            verticalArrangement = Arrangement.spacedBy(gapDp),
            contentPadding = PaddingValues(horizontal = sidePadding),
            modifier = Modifier.fillMaxSize().zoomable(
                maxScale = 3f, doubleTapScale = 2f,
                onTap = { off ->
                    val a = zoneAction(off.y / h, cfg)
                    if (a == 0) toggle() else scroll(a)
                },
                onLongPress = { off ->
                    list.layoutInfo.visibleItemsInfo.firstOrNull { off.y >= it.offset && off.y <= it.offset + it.size }
                        ?.let { vm.items.getOrNull(it.index) as? RItem.Pg }?.let(onLongPress)
                },
            ),
        ) {
            items(vm.items, key = { it.key }) { it ->
                when (it) {
                    is RItem.Pg -> TiledImage(
                        url = it.page.imageUrl, contentDescription = "Page ${it.idx + 1}",
                        placeholder = { Box(Modifier.fillMaxWidth().aspectRatio(0.7f).shimmer()) },
                        error = { retry ->
                            Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clickable { retry() }, contentAlignment = Alignment.Center) {
                                Text("Failed to load page. Tap to retry.", color = cfg.fg.copy(0.7f), style = InkType.callout)
                            }
                        },
                    )
                    is RItem.Trans -> TransitionView(it, vm, cfg, Modifier.fillMaxWidth().height(320.dp).wrapContentSize())
                }
            }
        }
        if (vm.nextLoading) androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
            color = app.inkwell.ui.theme.LocalInk.current.accent, trackColor = Color.Transparent,
        )
        }
    }
}

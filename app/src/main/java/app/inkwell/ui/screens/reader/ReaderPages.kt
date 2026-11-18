package app.inkwell.ui.screens.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
) {
    val fg: Color get() = if (bg == ReaderBackground.WHITE) Color.Black else Color.White
}

/** Lets volume keys / external code turn pages in whichever reader is on screen. */
class ReaderNav {
    var next: () -> Unit = {}
    var prev: () -> Unit = {}
}

fun pageRequest(context: android.content.Context, url: String, original: Boolean): ImageRequest =
    ImageRequest.Builder(context).data(url).apply { if (original) size(Size.ORIGINAL) else size(2048) }.build()

@Composable
private fun PageImage(url: String, desc: String, modifier: Modifier, fit: ContentScale, cfg: ReaderConfig, minAspect: Float? = null) {
    var attempt by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val box = Modifier.fillMaxWidth().then(if (minAspect != null) Modifier.aspectRatio(minAspect) else Modifier.fillMaxHeight())
    key(attempt) {
        SubcomposeAsyncImage(
            model = pageRequest(context, url, cfg.scale == ReaderScale.ORIGINAL),
            contentDescription = desc,
            contentScale = fit,
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

@Composable
fun PagedReader(
    vm: ReaderViewModel, vertical: Boolean, rtl: Boolean, startIndex: Int, seekTo: Int?, cfg: ReaderConfig, nav: ReaderNav,
    toggle: () -> Unit, onIndex: (Int) -> Unit, onSeekDone: () -> Unit, onLongPress: (RItem.Pg) -> Unit,
) {
    val items = vm.items
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { vm.items.size }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { onIndex(it) } }
    LaunchedEffect(seekTo) { seekTo?.let { pager.animateScrollToPage(it, animationSpec = Motion.Default.spec()); onSeekDone() } }
    fun go(d: Int) {
        scope.launch { pager.animateScrollToPage((pager.currentPage + d).coerceIn(0, vm.items.lastIndex), animationSpec = Motion.Snappy.spec()) }
    }
    SideEffect { nav.next = { go(1) }; nav.prev = { go(-1) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val fling = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = Motion.Bouncy.spec())
        val content: @Composable (Int) -> Unit = { i ->
            when (val it = vm.items.getOrNull(i)) {
                is RItem.Pg -> PagePane(it, vertical, cfg, onLong = { onLongPress(it) }, tapHandler = { off ->
                    val a = zoneAction(if (vertical) off.y / h else off.x / w, cfg)
                    if (a == 0) toggle() else go(if (rtl && !vertical) -a else a)
                })
                is RItem.Trans -> TransitionView(it, vm, cfg, Modifier.fillMaxSize().wrapContentSize().clickable(
                    indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                ) { toggle() })
                null -> Unit
            }
        }
        if (vertical) VerticalPager(
            state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1, flingBehavior = fling,
            key = { vm.items.getOrNull(it)?.key ?: it },
        ) { content(it) }
        else HorizontalPager(
            state = pager, modifier = Modifier.fillMaxSize(), reverseLayout = rtl, beyondViewportPageCount = 1, flingBehavior = fling,
            key = { vm.items.getOrNull(it)?.key ?: it },
        ) { content(it) }
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
) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startIndex.coerceIn(0, (vm.items.size - 1).coerceAtLeast(0)))
    val scope = rememberCoroutineScope()
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
        LazyColumn(
            state = list,
            verticalArrangement = Arrangement.spacedBy(if (cfg.gap) 8.dp else 0.dp),
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
    }
}

package app.inkwell.ui.screens

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.inkwell.data.ReaderBackground
import app.inkwell.data.ReaderViewMode
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.screens.reader.*
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import coil3.SingletonImageLoader
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ReaderScreen(sourceId: String, entryId: String, episodeId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val vm = inkViewModel("reader/$sourceId/$entryId") { ReaderViewModel(it, sourceId, entryId, episodeId, context.applicationContext) }
    val ink = LocalInk.current
    val reduced = LocalReducedMotion.current
    val prefs = LocalContainer.current.settings.reader
    var overlay by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var actionPage by remember { mutableStateOf<RItem.Pg?>(null) }
    val hazeState = remember { HazeState() }
    val activity = LocalActivity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val nav = remember { ReaderNav() }
    val focus = remember { FocusRequester() }

    val scale by prefs.scale.collectAsStateWithLifecycle()
    val bgPref by prefs.background.collectAsStateWithLifecycle()
    val tapZones by prefs.tapZones.collectAsStateWithLifecycle()
    val keepOn by prefs.keepScreenOn.collectAsStateWithLifecycle()
    val pageNum by prefs.pageNumber.collectAsStateWithLifecycle()
    val volumeKeys by prefs.volumeKeys.collectAsStateWithLifecycle()
    val gap by prefs.webtoonGap.collectAsStateWithLifecycle()
    val brightness by prefs.brightness.collectAsStateWithLifecycle()
    val cfg = ReaderConfig(scale, bgPref, tapZones, gap)
    val bgColor = when (bgPref) { ReaderBackground.BLACK -> Color.Black; ReaderBackground.WHITE -> Color.White; ReaderBackground.GRAY -> Color(0xFF3A3A3C) }

    // dark status bar icons while reading, restore on exit
    DisposableEffect(Unit) {
        val ctl = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val prevLight = ctl?.isAppearanceLightStatusBars
        ctl?.isAppearanceLightStatusBars = false
        onDispose {
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            if (prevLight != null) ctl.isAppearanceLightStatusBars = prevLight
        }
    }
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(brightness) {
        val w = activity?.window
        if (w != null) w.attributes = w.attributes.also { it.screenBrightness = if (brightness < 0f) -1f else brightness }
        onDispose { if (w != null) w.attributes = w.attributes.also { it.screenBrightness = -1f } }
    }
    LaunchedEffect(overlay) {
        val ctl = activity?.window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (overlay) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
    }
    LaunchedEffect(showSettings, actionPage, vm.generation, vm.loading) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(bgColor)
            .onPreviewKeyEvent { e ->
                if (!volumeKeys || (e.key != Key.VolumeUp && e.key != Key.VolumeDown)) false
                else {
                    if (e.type == KeyEventType.KeyDown) { if (e.key == Key.VolumeUp) nav.prev() else nav.next() }
                    true
                }
            }
            .focusRequester(focus).focusable(),
    ) {
        val items = vm.items
        val mode = vm.mode
        when {
            vm.error != null && vm.chapters.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                ErrorState(vm.error!!, webViewUrl = (LocalContainer.current.registry.find(sourceId) as? app.inkwell.data.source.ScraperSource)?.baseUrl, onRetry = {
                    if (vm.episodes.isEmpty()) vm.start() else vm.open(vm.chapters.firstOrNull()?.ep?.id ?: episodeId)
                })
            }
            vm.loading || mode == null || items.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.7f).shimmer())
            }
            else -> key(vm.generation, mode) {
                var curIdx by remember { mutableIntStateOf(vm.initialItem) }
                var lastIdx by remember { mutableIntStateOf(vm.initialItem) }
                var seekTo by remember { mutableStateOf<Int?>(null) }
                val haptic = LocalHapticFeedback.current
                val cur = items.getOrNull(curIdx)
                val curEp = when (cur) { is RItem.Pg -> cur.ep; is RItem.Trans -> cur.from; null -> vm.chapters.first().ep }
                val total = (cur as? RItem.Pg)?.total ?: (cur as? RItem.Trans)?.total ?: 1
                val page = ((cur as? RItem.Pg)?.idx ?: (total - 1)).coerceIn(0, total - 1)
                val chapterStart = items.indexOfFirst { it is RItem.Pg && it.ep.id == curEp.id && it.idx == 0 }.coerceAtLeast(0)

                // progress (debounced), mark read on last page
                LaunchedEffect(Unit) {
                    snapshotFlow { curIdx to lastIdx }.debounce(250).collect { (c, l) -> vm.report(vm.items.getOrNull(c), vm.items.getOrNull(l)) }
                }
                // seamless: load next chapter when nearing the end of the last loaded one
                LaunchedEffect(curIdx, vm.chapters.size) {
                    val last = vm.chapters.lastOrNull() ?: return@LaunchedEffect
                    val p = cur
                    val near = (p is RItem.Pg && p.ep.id == last.ep.id && p.idx >= p.total - 6) || (p is RItem.Trans && p.from.id == last.ep.id)
                    if (near && !vm.nextError) vm.loadNext()
                }
                // prefetch the next pages (this chapter and the start of the next)
                LaunchedEffect(curIdx, vm.chapters.size) {
                    val loader = SingletonImageLoader.get(context)
                    val original = scale == app.inkwell.data.ReaderScale.ORIGINAL
                    for (i in 1..4) (vm.items.getOrNull(curIdx + i) as? RItem.Pg)?.let {
                        if (mode == ReaderViewMode.WEBTOON) prefetchPage(context, it.page.imageUrl)
                        else loader.enqueue(pageRequest(context, it.page.imageUrl, original))
                    }
                }
                LaunchedEffect(curIdx) { if (cur is RItem.Pg && cur.idx == cur.total - 1 && cur.total > 1) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

                Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    if (mode == ReaderViewMode.WEBTOON) WebtoonReader(
                        vm, vm.initialItem, seekTo, cfg, nav, { overlay = !overlay },
                        onVisible = { f, l -> curIdx = f; lastIdx = l },
                        onSeekDone = { seekTo = null }, onLongPress = { actionPage = it },
                    ) else PagedReader(
                        vm, vertical = mode == ReaderViewMode.VERTICAL, rtl = mode == ReaderViewMode.RTL, startIndex = vm.initialItem,
                        seekTo = seekTo, cfg = cfg, nav = nav, toggle = { overlay = !overlay },
                        onIndex = { curIdx = it; lastIdx = it }, onSeekDone = { seekTo = null }, onLongPress = { actionPage = it },
                    )
                }

                // page number overlay while bars are hidden
                AnimatedVisibility(
                    pageNum && !overlay, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut(),
                ) {
                    Text(
                        "${page + 1} / $total", color = Color.White, style = InkType.caption,
                        modifier = Modifier.navigationBarsPadding().padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 5.dp),
                    )
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
                                    Text(curEp.name, color = Color.White.copy(alpha = 0.6f), style = InkType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                BarIconButton(Icons.Outlined.Tune, "Reader settings", Color.White) { showSettings = true }
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
                            var sliding by remember { mutableStateOf<Float?>(null) }
                            val prev = vm.prevOf(curEp)
                            val next = vm.nextOf(curEp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BarIconButton(Icons.Outlined.SkipPrevious, "Previous chapter", if (prev != null) Color.White else Color.White.copy(0.3f)) { prev?.let { vm.open(it.id) } }
                                CompositionLocalProvider(LocalLayoutDirection provides if (mode == ReaderViewMode.RTL) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                    Slider(
                                        value = sliding ?: page.toFloat(),
                                        onValueChange = { sliding = it },
                                        onValueChangeFinished = { sliding?.let { seekTo = chapterStart + it.toInt().coerceIn(0, total - 1) }; sliding = null },
                                        valueRange = 0f..(total - 1).coerceAtLeast(1).toFloat(),
                                        colors = SliderDefaults.colors(
                                            thumbColor = Color.White, activeTrackColor = ink.accent, inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                                        ),
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                BarIconButton(Icons.Outlined.SkipNext, "Next chapter", if (next != null) Color.White else Color.White.copy(0.3f)) {
                                    next?.let { n ->
                                        val at = items.indexOfFirst { it is RItem.Pg && it.ep.id == n.id && it.idx == 0 }
                                        if (at >= 0) seekTo = at else vm.open(n.id)
                                    }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${(sliding?.toInt() ?: page) + 1} / $total", color = Color.White.copy(alpha = 0.8f), style = InkType.caption, modifier = Modifier.padding(start = 8.dp).weight(1f))
                                Text(mode.label, color = ink.accent, style = InkType.callout, modifier = Modifier.pressable { showSettings = true }.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                        }
                    }
                }
            }
        }
        if (showSettings) ReaderSettingsSheet(vm, prefs) { showSettings = false }
        actionPage?.let { pg ->
            ModalBottomSheet(onDismissRequest = { actionPage = null }, containerColor = ink.bg) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                    Text("Page ${pg.idx + 1}", color = ink.label, style = InkType.title, modifier = Modifier.padding(bottom = 8.dp))
                    val name = "${vm.entry?.title ?: "page"}_${pg.ep.name}_${pg.idx + 1}".replace(Regex("[^A-Za-z0-9._-]+"), "_")
                    Text("Share", color = ink.accent, style = InkType.body, modifier = Modifier.fillMaxWidth().pressable {
                        actionPage = null
                        scope.launch {
                            runCatching { context.startActivity(PageActions.share(context, pg.page.imageUrl, name)) }
                                .onFailure { Toast.makeText(context, "Could not share", Toast.LENGTH_SHORT).show() }
                        }
                    }.padding(vertical = 12.dp))
                    if (PageActions.canSave) Text("Save to Pictures", color = ink.accent, style = InkType.body, modifier = Modifier.fillMaxWidth().pressable {
                        actionPage = null
                        scope.launch {
                            val ok = runCatching { PageActions.saveToPictures(context, pg.page.imageUrl, name) }.getOrNull() != null
                            Toast.makeText(context, if (ok) "Saved to Pictures/Inkwell" else "Could not save image", Toast.LENGTH_SHORT).show()
                        }
                    }.padding(vertical = 12.dp))
                }
            }
        }
    }
}

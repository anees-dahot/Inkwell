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
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material.icons.outlined.StayCurrentLandscape
import androidx.compose.material.icons.outlined.StayCurrentPortrait
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.ViewColumn
import androidx.compose.material.icons.outlined.ViewDay
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.sp
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import app.inkwell.data.ReaderDualPage
import app.inkwell.data.ReaderOrientation
import app.inkwell.ui.util.relativeDate
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
    app.inkwell.data.TrackReading(sourceId, entryId)
    val context = LocalContext.current
    val vm = inkViewModel("reader/$sourceId/$entryId") { ReaderViewModel(it, sourceId, entryId, episodeId, context.applicationContext) }
    val ink = LocalInk.current
    val reduced = LocalReducedMotion.current
    val prefs = LocalContainer.current.settings.reader
    var overlay by rememberSaveable { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showModes by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(false) }
    var actionPage by remember { mutableStateOf<RItem.Pg?>(null) }
    val hazeState = remember { HazeState() }
    val activity = LocalActivity.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val nav = remember { ReaderNav() }
    val focus = remember { FocusRequester() }
    val carry = remember { intArrayOf(-1, 0) } // (generation, item index) of the last viewed position

    val scale by prefs.scale.collectAsStateWithLifecycle()
    val bgPref by prefs.background.collectAsStateWithLifecycle()
    val tapZones by prefs.tapZones.collectAsStateWithLifecycle()
    val keepOn by prefs.keepScreenOn.collectAsStateWithLifecycle()
    val pageNum by prefs.pageNumber.collectAsStateWithLifecycle()
    val volumeKeys by prefs.volumeKeys.collectAsStateWithLifecycle()
    val gap by prefs.webtoonGap.collectAsStateWithLifecycle()
    val brightness by ReaderSession.brightness.collectAsStateWithLifecycle()
    val dualPref by prefs.dualPage.collectAsStateWithLifecycle()
    val coverAlone by prefs.dualCoverAlone.collectAsStateWithLifecycle()
    val orient by prefs.orientation.collectAsStateWithLifecycle()
    val crop by prefs.cropBorders.collectAsStateWithLifecycle()
    val autoSpeed by prefs.autoScrollSpeed.collectAsStateWithLifecycle()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val dual = dualPref == ReaderDualPage.ALWAYS || (dualPref == ReaderDualPage.LANDSCAPE && landscape)
    val cfg = ReaderConfig(scale, bgPref, tapZones, gap, crop)
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
    DisposableEffect(orient) {
        val a = activity
        a?.requestedOrientation = when (orient) {
            ReaderOrientation.FREE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { a?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
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
    LaunchedEffect(showSettings, actionPage, vm.generation, vm.loading, vm.mode, overlay) { runCatching { focus.requestFocus() } }

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
            else -> key(vm.generation, mode, dual && mode.pagedHorizontal) {
                // Switching reading mode rebuilds this block: resume at the page being viewed, not the saved one.
                val startIdx = remember { (if (carry[0] == vm.generation) carry[1] else vm.initialItem).coerceIn(0, items.lastIndex) }
                var curIdx by remember { mutableIntStateOf(startIdx) }
                var lastIdx by remember { mutableIntStateOf(startIdx) }
                LaunchedEffect(curIdx) { carry[0] = vm.generation; carry[1] = curIdx }
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
                        if (mode.continuousVertical) prefetchPage(context, it.page.imageUrl)
                        else loader.enqueue(pageRequest(context, it.page.imageUrl, original && !mode.continuousHorizontal, crop))
                    }
                }
                LaunchedEffect(curIdx) { if (cur is RItem.Pg && cur.idx == cur.total - 1 && cur.total > 1) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

                Box(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    val scrolling = autoScroll && !overlay
                    when {
                        mode.continuousVertical -> WebtoonReader(
                            vm, startIdx, seekTo, cfg, nav, { overlay = !overlay },
                            onVisible = { f, l -> curIdx = f; lastIdx = l },
                            onSeekDone = { seekTo = null }, onLongPress = { actionPage = it },
                            gapDp = if (mode == ReaderViewMode.LONG_STRIP) 16.dp else if (gap) 8.dp else 0.dp,
                            sidePadding = if (mode == ReaderViewMode.LONG_STRIP && landscape) 64.dp else 0.dp,
                            autoScroll = scrolling, speedDp = autoSpeed,
                        )
                        mode.continuousHorizontal -> HorizontalStripReader(
                            vm, rtl = mode.rtl, startIndex = startIdx, seekTo = seekTo, cfg = cfg, nav = nav, toggle = { overlay = !overlay },
                            onVisible = { f, l -> curIdx = f; lastIdx = l },
                            onSeekDone = { seekTo = null }, onLongPress = { actionPage = it },
                            autoScroll = scrolling, speedDp = autoSpeed,
                        )
                        else -> PagedReader(
                            vm, vertical = mode == ReaderViewMode.VERTICAL, rtl = mode.rtl, startIndex = startIdx,
                            seekTo = seekTo, cfg = cfg, nav = nav, toggle = { overlay = !overlay },
                            onIndex = { f, l -> curIdx = f; lastIdx = l }, onSeekDone = { seekTo = null }, onLongPress = { actionPage = it },
                            dual = dual && mode.pagedHorizontal, coverAlone = coverAlone,
                        )
                    }
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
                                    val sub = listOfNotNull(curEp.name, curEp.dateMillis?.let { relativeDate(it) }, curEp.scanlator).joinToString(" · ")
                                    Text(sub, color = Color.White.copy(alpha = 0.6f), style = InkType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    val shape = RoundedCornerShape(24.dp)
                    Column(
                        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp).padding(bottom = 10.dp)
                            .widthIn(max = 640.dp).clip(shape).glass(hazeState, dark = true)
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    ) {
                        var sliding by remember { mutableStateOf<Float?>(null) }
                        val prev = vm.prevOf(curEp)
                        val next = vm.nextOf(curEp)
                        val shown = (sliding?.toInt() ?: page) + 1
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BarIconButton(Icons.Outlined.SkipPrevious, "Previous chapter", if (prev != null) Color.White else Color.White.copy(0.3f)) { prev?.let { vm.open(it.id) } }
                            Text(
                                "$shown", color = Color.White, style = InkType.caption,
                                modifier = Modifier.widthIn(min = 28.dp), maxLines = 1,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            CompositionLocalProvider(LocalLayoutDirection provides if (mode.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                                Slider(
                                    value = sliding ?: page.toFloat(),
                                    onValueChange = { sliding = it },
                                    onValueChangeFinished = { sliding?.let { seekTo = chapterStart + it.toInt().coerceIn(0, total - 1) }; sliding = null },
                                    valueRange = 0f..(total - 1).coerceAtLeast(1).toFloat(),
                                    steps = (total - 2).coerceIn(0, 60),
                                    colors = SliderDefaults.colors(
                                        thumbColor = Color.White, activeTrackColor = ink.accent, inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                                        activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
                                    ),
                                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                                )
                            }
                            Text(
                                "$total", color = Color.White.copy(alpha = 0.6f), style = InkType.caption,
                                modifier = Modifier.widthIn(min = 28.dp), maxLines = 1,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                            BarIconButton(Icons.Outlined.SkipNext, "Next chapter", if (next != null) Color.White else Color.White.copy(0.3f)) {
                                next?.let { n ->
                                    val at = items.indexOfFirst { it is RItem.Pg && it.ep.id == n.id && it.idx == 0 }
                                    if (at >= 0) seekTo = at else vm.open(n.id)
                                }
                            }
                        }
                        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(0.5.dp).background(Color.White.copy(alpha = 0.12f)))
                        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            BarAction(modeIcon(mode), mode.label) { showModes = true }
                            BarAction(
                                when (orient) {
                                    ReaderOrientation.FREE -> Icons.Outlined.ScreenRotation
                                    ReaderOrientation.PORTRAIT -> Icons.Outlined.StayCurrentPortrait
                                    ReaderOrientation.LANDSCAPE -> Icons.Outlined.StayCurrentLandscape
                                },
                                orient.label,
                            ) { prefs.setOrientation(ReaderOrientation.entries[(orient.ordinal + 1) % ReaderOrientation.entries.size]) }
                            if (!mode.continuousVertical) BarAction(Icons.Outlined.Crop, "Crop", active = crop) {
                                prefs.setCropBorders(!crop)
                            }
                            if (mode.continuousVertical || mode.continuousHorizontal) BarAction(
                                if (autoScroll) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, "Auto", active = autoScroll,
                            ) { autoScroll = !autoScroll; if (autoScroll) overlay = false }
                            BarAction(Icons.AutoMirrored.Outlined.List, "Chapters") { showChapters = true }
                            BarAction(Icons.Outlined.Tune, "Settings") { showSettings = true }
                        }
                    }
                }
                if (showChapters) ChapterListSheet(vm, curEp.id, onDismiss = { showChapters = false }) { ep ->
                    showChapters = false
                    val at = items.indexOfFirst { it is RItem.Pg && it.ep.id == ep.id && it.idx == 0 }
                    if (at >= 0) seekTo = at else vm.open(ep.id)
                }
            }
        }
        if (showModes) ModePickerSheet(vm.mode ?: ReaderViewMode.LTR, onDismiss = { showModes = false }) { m ->
            showModes = false
            vm.setTitleMode(m)
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

fun modeIcon(m: ReaderViewMode): ImageVector = when (m) {
    ReaderViewMode.LTR, ReaderViewMode.RTL -> Icons.Outlined.ViewCarousel
    ReaderViewMode.VERTICAL -> Icons.Outlined.SwapVert
    ReaderViewMode.WEBTOON -> Icons.Outlined.ViewDay
    ReaderViewMode.LONG_STRIP -> Icons.Outlined.ViewStream
    ReaderViewMode.SCROLL_LTR, ReaderViewMode.SCROLL_RTL -> Icons.Outlined.ViewColumn
}

@Composable
private fun BarAction(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit) {
    val ink = LocalInk.current
    val tint = if (active) ink.accent else Color.White
    Column(
        Modifier.widthIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).pressable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, color = tint.copy(alpha = if (active) 1f else 0.75f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModePickerSheet(current: ReaderViewMode, onDismiss: () -> Unit, onPick: (ReaderViewMode) -> Unit) {
    val ink = LocalInk.current
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ink.bg) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("Reading mode", color = ink.label, style = InkType.title, modifier = Modifier.padding(bottom = 4.dp))
            Text("Saved for this title", color = ink.secondary, style = InkType.caption, modifier = Modifier.padding(bottom = 12.dp))
            ReaderViewMode.entries.forEach { m ->
                val sel = m == current
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (sel) ink.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .pressable { onPick(m) }.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(modeIcon(m), null, tint = if (sel) ink.accent else ink.secondary, modifier = Modifier.size(22.dp))
                    Column(Modifier.padding(start = 14.dp).weight(1f)) {
                        Text(m.label, color = if (sel) ink.accent else ink.label, style = InkType.body)
                        Text(modeHint(m), color = ink.secondary, style = InkType.caption)
                    }
                }
            }
        }
    }
}

private fun modeHint(m: ReaderViewMode) = when (m) {
    ReaderViewMode.LTR -> "One page at a time, swipe left"
    ReaderViewMode.RTL -> "One page at a time, manga style"
    ReaderViewMode.VERTICAL -> "One page at a time, swipe up"
    ReaderViewMode.WEBTOON -> "Continuous vertical, no gaps"
    ReaderViewMode.LONG_STRIP -> "Continuous vertical with page gaps"
    ReaderViewMode.SCROLL_LTR -> "Continuous sideways scroll"
    ReaderViewMode.SCROLL_RTL -> "Continuous sideways scroll, manga style"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterListSheet(vm: ReaderViewModel, currentId: String, onDismiss: () -> Unit, onPick: (app.inkwell.data.model.Episode) -> Unit) {
    val ink = LocalInk.current
    val eps = vm.episodes
    val curIndex = eps.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (curIndex - 3).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ink.bg) {
        Text(
            "Chapters (${eps.size})", color = ink.label, style = InkType.title,
            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
        )
        androidx.compose.foundation.lazy.LazyColumn(state = list, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(eps.size, key = { eps[it].id }) { i ->
                val ep = eps[i]
                val sel = ep.id == currentId
                Row(
                    Modifier.fillMaxWidth().background(if (sel) ink.accent.copy(alpha = 0.12f) else Color.Transparent)
                        .pressable { onPick(ep) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(ep.name, color = if (sel) ink.accent else ink.label, style = InkType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(ep.dateMillis?.let { relativeDate(it) }, ep.scanlator).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, color = ink.secondary, style = InkType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (sel) Text("Reading", color = ink.accent, style = InkType.caption)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

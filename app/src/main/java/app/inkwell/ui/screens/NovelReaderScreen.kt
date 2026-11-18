package app.inkwell.ui.screens

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.LocalReducedMotion
import app.inkwell.ui.motion.Motion
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.screens.novel.NovelSettingsSheet
import app.inkwell.ui.screens.novel.NovelTtsPlayer
import app.inkwell.ui.screens.novel.novelFontFamily
import app.inkwell.ui.screens.novel.novelPalette
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class Chap(val ep: Episode, val paras: List<String>)

class NovelReaderViewModel(
    private val c: AppContainer,
    private val sourceId: String,
    private val entryId: String,
    private val startEpisodeId: String,
) : ViewModel() {
    var entry by mutableStateOf<Entry?>(null); private set
    var episodes by mutableStateOf<List<Episode>>(emptyList()); private set
    /** Loaded chapters in reading order. One entry unless continuous scroll appended more. */
    var chapters by mutableStateOf<List<Chap>>(emptyList()); private set
    /** Bumped whenever the list is replaced, so the screen rebuilds its scroll state. */
    var generation by mutableIntStateOf(0); private set
    var initialParagraph by mutableIntStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var appending by mutableStateOf(false); private set
    var appendError by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    val current: Episode? get() = chapters.firstOrNull()?.ep

    init { start() }

    fun start() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val d = c.repo.details(sourceId, entryId)
                entry = d.entry
                // Reader assumes newest-first; some sources return oldest-first, so normalize locally.
                val raw = c.repo.episodes(sourceId, entryId)
                val a = raw.firstOrNull()?.number; val b = raw.lastOrNull()?.number
                episodes = if (a != null && b != null && a < b) raw.asReversed() else raw
                openNow(current?.id ?: startEpisodeId, null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load"; loading = false }
        }
    }

    fun open(episodeId: String, startPara: Int? = null) { viewModelScope.launch { openNow(episodeId, startPara) } }

    /** Replace the loaded chapters with a single chapter. Uses saved progress unless [startPara] is given. */
    suspend fun openNow(episodeId: String, startPara: Int?) {
        loading = true; error = null; appendError = null
        try {
            val ep = episodes.firstOrNull { it.id == episodeId } ?: error("Chapter not found")
            val text = c.repo.chapterText(sourceId, entryId, episodeId)
            if (text.isEmpty()) error("This chapter has no readable content")
            val prog = c.repo.getProgress(sourceId, entryId, episodeId)
            initialParagraph = startPara
                ?: if (prog != null && !prog.read) prog.page.coerceIn(0, text.lastIndex) else 0
            chapters = listOf(Chap(ep, text))
            generation++
            c.repo.saveProgress(entry!!, ep, initialParagraph, text.size, false)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "Could not load chapter" }
        finally { loading = false }
    }

    fun appendNext() { viewModelScope.launch { appendNow() } }

    /** Append the chapter after the last loaded one (continuous scroll). */
    suspend fun appendNow() {
        if (appending) return
        val last = chapters.lastOrNull() ?: return
        val next = nextOf(last.ep) ?: return
        appending = true; appendError = null
        try {
            val text = c.repo.chapterText(sourceId, entryId, next.id)
            if (text.isEmpty()) error("This chapter has no readable content")
            if (chapters.none { it.ep.id == next.id }) chapters = chapters + Chap(next, text)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { appendError = e.message ?: "Could not load next chapter" }
        finally { appending = false }
    }

    /** Make sure [ep] is loaded (TTS chapter advance). */
    suspend fun ensureLoaded(ep: Episode, append: Boolean): Boolean {
        if (chapters.any { it.ep.id == ep.id }) return true
        if (append) appendNow() else openNow(ep.id, 0)
        return chapters.any { it.ep.id == ep.id }
    }

    fun save(ci: Int, para: Int, read: Boolean) {
        val en = entry ?: return
        val ch = chapters.getOrNull(ci) ?: return
        viewModelScope.launch { c.repo.saveProgress(en, ch.ep, para, ch.paras.size, read) }
    }

    private fun idx(ep: Episode?) = episodes.indexOfFirst { it.id == ep?.id }
    fun nextOf(ep: Episode?): Episode? = idx(ep).takeIf { it > 0 }?.let { episodes[it - 1] }
    fun prevOf(ep: Episode?): Episode? = idx(ep).let { if (it >= 0 && it < episodes.lastIndex) episodes[it + 1] else null }
    /** 1-based chapter number in reading order. */
    fun numberOf(ep: Episode?): Int = idx(ep).let { if (it < 0) 0 else episodes.size - it }
}

private class RItem(val ci: Int, val kind: Int, val pi: Int) // kind: 0 header, 1 paragraph, 2 footer

/** Reader-side facade over the process-wide [NovelTtsPlayer] (which keeps speaking after the screen is gone). */
private class TtsDriver(val vm: NovelReaderViewModel, val ctx: android.content.Context) {
    private val p = NovelTtsPlayer
    val state get() = p.state
    /** Spoken position, only when the playing book is the one shown here. */
    val pos get() = if (vm.entry?.let { p.isFor(it.sourceId, it.id) } == true) p.pos else null
    val active get() = p.active

    fun start(epId: String, pi: Int) {
        val en = vm.entry ?: return
        val paras = vm.chapters.firstOrNull { it.ep.id == epId }?.paras ?: return
        p.start(ctx, en, vm.episodes, epId, pi, paras)
    }
    fun step(d: Int) = p.step(d)
    fun pause() = p.pause()
    fun resume() = p.resume()
    fun restart() = p.restart()
    fun stop() = p.stop()
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun NovelReaderScreen(sourceId: String, entryId: String, episodeId: String, onBack: () -> Unit) {
    app.inkwell.data.TrackReading(sourceId, entryId)
    val c = LocalContainer.current
    val vm = inkViewModel("novelreader/$sourceId/$entryId") { NovelReaderViewModel(it, sourceId, entryId, episodeId) }
    val ink = LocalInk.current
    val reduced = LocalReducedMotion.current
    val prefs by c.settings.novel.collectAsState()
    val pal = novelPalette(prefs.theme, ink)
    var overlay by rememberSaveable { mutableStateOf(true) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showChapters by rememberSaveable { mutableStateOf(false) }
    val hazeState = remember { HazeState() }
    val activity = LocalActivity.current
    val view = LocalView.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsNow by rememberUpdatedState(prefs)

    val driver = remember { TtsDriver(vm, ctx.applicationContext) }
    // Read-aloud keeps running after this screen is gone (foreground service); just detach.
    DisposableEffect(Unit) {
        NovelTtsPlayer.prepare(ctx)
        NovelTtsPlayer.attached = true
        onDispose { NovelTtsPlayer.attached = false; NovelTtsPlayer.releaseIfIdle() }
    }
    // Follow the service when it moves into another chapter while this screen is open.
    var seenEp by remember { mutableStateOf(NovelTtsPlayer.pos?.first) }
    LaunchedEffect(driver.pos?.first) {
        val e = driver.pos?.first ?: run { seenEp = null; return@LaunchedEffect }
        if (e == seenEp) return@LaunchedEffect
        seenEp = e
        if (vm.chapters.any { it.ep.id == e }) return@LaunchedEffect
        if (prefsNow.continuous && vm.nextOf(vm.chapters.lastOrNull()?.ep)?.id == e) vm.appendNow()
        else vm.openNow(e, driver.pos?.second ?: 0)
    }

    LaunchedEffect(prefs.keepScreenOn) { view.keepScreenOn = prefs.keepScreenOn }
    DisposableEffect(Unit) {
        val ctl = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        val prevLight = ctl?.isAppearanceLightStatusBars
        val prevBehavior = ctl?.systemBarsBehavior
        ctl?.isAppearanceLightStatusBars = false
        ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            view.keepScreenOn = false
            ctl?.show(WindowInsetsCompat.Type.systemBars())
            if (prevLight != null) ctl.isAppearanceLightStatusBars = prevLight
            if (prevBehavior != null) ctl.systemBarsBehavior = prevBehavior
        }
    }
    LaunchedEffect(overlay) {
        val ctl = activity?.window?.let { WindowCompat.getInsetsController(it, view) } ?: return@LaunchedEffect
        if (overlay) ctl.show(WindowInsetsCompat.Type.systemBars()) else ctl.hide(WindowInsetsCompat.Type.systemBars())
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(showSettings, showChapters) {
        if (!showSettings && !showChapters) runCatching { focus.requestFocus() }
    }
    // Page-scroll handle shared by tap zones and volume keys; set once a list exists.
    var pageScroll by remember { mutableStateOf<(Int) -> Unit>({}) }

    Box(
        Modifier.fillMaxSize().background(pal.bg)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (prefsNow.volumeKeys && (e.key == Key.VolumeUp || e.key == Key.VolumeDown)) {
                    if (e.type == KeyEventType.KeyDown) pageScroll(if (e.key == Key.VolumeDown) 1 else -1)
                    true
                } else false
            },
    ) {
        val ep = vm.current
        when {
            vm.error != null && vm.chapters.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                ErrorState(vm.error!!, webViewUrl = (LocalContainer.current.registry.find(sourceId) as? app.inkwell.data.source.ScraperSource)?.baseUrl, onRetry = { if (vm.episodes.isEmpty()) vm.start() else vm.open(ep?.id ?: episodeId) })
            }
            vm.loading && vm.chapters.isEmpty() || ep == null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                Box(Modifier.fillMaxWidth().height(400.dp).shimmer())
            }
            else -> key(vm.generation) {
                val list = rememberLazyListState(initialFirstVisibleItemIndex = 1 + vm.initialParagraph)
                val haptic = LocalHapticFeedback.current
                val chapters = vm.chapters
                val items = remember(chapters) {
                    buildList {
                        chapters.forEachIndexed { ci, ch ->
                            add(RItem(ci, 0, 0))
                            ch.paras.indices.forEach { add(RItem(ci, 1, it)) }
                            add(RItem(ci, 2, 0))
                        }
                    }
                }
                fun indexOf(ci: Int, pi: Int) = items.indexOfFirst { it.ci == ci && it.kind == 1 && it.pi == pi }.coerceAtLeast(0)

                // Current reading position (chapter index, paragraph) and read progress.
                var curCi by remember { mutableIntStateOf(0) }
                var curPi by remember { mutableIntStateOf(vm.initialParagraph) }

                DisposableEffect(list) {
                    pageScroll = { dir -> scope.launch { list.animateScrollBy(dir * list.layoutInfo.viewportSize.height * 0.9f) } }
                    onDispose { pageScroll = {} }
                }

                LaunchedEffect(list, items) {
                    var lastKey = ""
                    snapshotFlow {
                        val first = items.getOrNull(list.firstVisibleItemIndex)
                        val vis = list.layoutInfo.visibleItemsInfo.mapNotNull { items.getOrNull(it.index) }
                        first to vis
                    }.collect { (first, vis) ->
                        first ?: return@collect
                        val ci = first.ci
                        val size = chapters.getOrNull(ci)?.paras?.size ?: return@collect
                        val firstPara = if (first.kind == 0) 0 else if (first.kind == 2) size - 1 else first.pi
                        curCi = ci; curPi = firstPara
                        val inCh = vis.filter { it.ci == ci }
                        val lastPara = inCh.maxOfOrNull { if (it.kind == 2) size - 1 else if (it.kind == 1) it.pi else 0 } ?: firstPara
                        val ended = inCh.any { it.kind == 2 }
                        val read = ended || (lastPara + 1).toFloat() / size >= 0.95f
                        val k = "$ci/$firstPara/$read"
                        if (k != lastKey) { lastKey = k; vm.save(ci, firstPara, read) }
                    }
                }
                // Continuous scroll: append the next chapter as the end nears.
                LaunchedEffect(list, items, prefs.continuous) {
                    if (!prefs.continuous) return@LaunchedEffect
                    snapshotFlow { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= items.size - 4 }
                        .collect { near ->
                            if (near && !vm.appending && vm.appendError == null && vm.nextOf(chapters.last().ep) != null) vm.appendNext()
                        }
                }
                // Haptic tick the first time the end of the final chapter shows.
                LaunchedEffect(list) {
                    var done = false
                    snapshotFlow { list.layoutInfo.visibleItemsInfo.any { items.getOrNull(it.index)?.kind == 2 } }
                        .collect { if (it && !done && chapters.size == 1 && chapters[0].paras.size > 1) { done = true; haptic.performHapticFeedback(HapticFeedbackType.LongPress) } }
                }
                // Keep the spoken paragraph in view.
                LaunchedEffect(driver.pos, list) {
                    val (e, p) = driver.pos ?: return@LaunchedEffect
                    val ci = chapters.indexOfFirst { it.ep.id == e }
                    if (ci < 0) return@LaunchedEffect
                    val idx = indexOf(ci, p)
                    val info = list.layoutInfo
                    val vi = info.visibleItemsInfo.firstOrNull { it.index == idx }
                    val topSafe = info.viewportStartOffset
                    if (vi == null || vi.offset < topSafe || vi.offset + vi.size > info.viewportEndOffset - info.afterContentPadding / 2) {
                        list.animateScrollToItem(idx)
                    }
                }

                val topPad = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() + TopBarHeight + 12.dp
                val botPad = WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding() + 110.dp
                val fs = prefs.fontSize
                val bodyStyle = InkType.body.copy(
                    fontSize = fs.sp, lineHeight = (fs * prefs.lineSpacing).sp,
                    fontFamily = novelFontFamily(prefs.font),
                    textAlign = if (prefs.justify) TextAlign.Justify else TextAlign.Start,
                )

                SelectionContainer(Modifier.fillMaxSize().hazeSource(hazeState)) {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize().pointerInput(prefs.tapZones) {
                            detectTapGestures { o ->
                                val h = size.height
                                when {
                                    prefs.tapZones && o.y < h * 0.28f -> pageScroll(-1)
                                    prefs.tapZones && o.y > h * 0.72f -> pageScroll(1)
                                    else -> overlay = !overlay
                                }
                            }
                        },
                        contentPadding = PaddingValues(start = prefs.margin.dp, end = prefs.margin.dp, top = topPad, bottom = botPad),
                        verticalArrangement = Arrangement.spacedBy(prefs.paraSpacing.dp),
                    ) {
                        items(items.size, key = { i -> val r = items[i]; "${chapters[r.ci].ep.id}:${r.kind}:${r.pi}" }) { i ->
                            val r = items[i]
                            val ch = chapters[r.ci]
                            when (r.kind) {
                                0 -> Text(
                                    ch.ep.name, color = pal.text, textAlign = TextAlign.Start,
                                    style = bodyStyle.copy(fontSize = (fs + 4).sp, lineHeight = ((fs + 4) * 1.3f).sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(top = if (r.ci > 0) 24.dp else 0.dp, bottom = 8.dp),
                                )
                                1 -> {
                                    val hl = driver.pos?.let { it.first == ch.ep.id && it.second == r.pi } == true
                                    Text(
                                        ch.paras[r.pi], style = bodyStyle, color = pal.text,
                                        modifier = Modifier.fillMaxWidth().then(
                                            if (hl) Modifier.clip(RoundedCornerShape(6.dp)).background(ink.accent.copy(alpha = 0.22f)) else Modifier
                                        ),
                                    )
                                }
                                else -> ChapterFooter(vm, ch, r.ci == chapters.lastIndex, prefs.continuous, pal.secondary) { id -> driver.stop(); vm.open(id, 0) }
                            }
                        }
                    }
                }

                val slide = remember(reduced) { Motion.Sheet.specFor<IntOffset>(reduced) }
                AnimatedVisibility(
                    overlay, Modifier.align(Alignment.TopCenter),
                    enter = (if (reduced) fadeIn() else slideInVertically(slide) { -it } + fadeIn()),
                    exit = (if (reduced) fadeOut() else slideOutVertically(slide) { -it } + fadeOut()),
                ) {
                    val curEp = chapters.getOrNull(curCi)?.ep ?: ep
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
                                BarIconButton(Icons.Outlined.Headphones, "Listen", if (driver.active) ink.accent else Color.White) {
                                    if (driver.active) driver.stop()
                                    else if (NovelTtsPlayer.tts.let { it == null || it.failed || (it.ready && !it.hasEngine) }) {
                                        android.widget.Toast.makeText(ctx, "No text-to-speech engine found. Install one (e.g. Speech Services by Google).", android.widget.Toast.LENGTH_LONG).show()
                                        runCatching {
                                            ctx.startActivity(android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    } else chapters.getOrNull(curCi)?.let { driver.start(it.ep.id, curPi) }
                                }
                                BarIconButton(Icons.Outlined.FormatListBulleted, "Chapters", Color.White) { showChapters = true }
                                BarIconButton(Icons.Outlined.TextFields, "Reader settings", Color.White) { showSettings = true }
                            }
                        }
                    }
                }

                Column(Modifier.align(Alignment.BottomCenter)) {
                    AnimatedVisibility(driver.active, enter = fadeIn(), exit = fadeOut()) {
                        MiniPlayer(driver, chapters, prefs.ttsRate, bottomInset = !overlay, hazeState = hazeState) { r ->
                            c.settings.updateNovel { it.copy(ttsRate = r) }; driver.restart()
                        }
                    }
                    AnimatedVisibility(
                        overlay,
                        enter = (if (reduced) fadeIn() else slideInVertically(slide) { it } + fadeIn()),
                        exit = (if (reduced) fadeOut() else slideOutVertically(slide) { it } + fadeOut()),
                    ) {
                        val curEp = chapters.getOrNull(curCi)?.ep ?: ep
                        val size = chapters.getOrNull(curCi)?.paras?.size ?: 1
                        val prev = vm.prevOf(curEp)
                        val next = vm.nextOf(curEp)
                        val frac = ((curPi + 1f) / size).coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth()) {
                            Box(Modifier.matchParentSize().glass(hazeState, dark = true))
                            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    BarIconButton(Icons.Outlined.ChevronLeft, "Previous chapter", if (prev != null) Color.White else Color.White.copy(0.3f)) { prev?.let { driver.stop(); vm.open(it.id, 0) } }
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            "Chapter ${vm.numberOf(curEp)} of ${vm.episodes.size}  ·  ${(frac * 100).toInt()}%",
                                            color = Color.White.copy(alpha = 0.85f), style = InkType.caption,
                                        )
                                        Slider(
                                            value = frac, valueRange = 0f..1f,
                                            onValueChange = { f ->
                                                val pi = ((f * size).toInt()).coerceIn(0, size - 1)
                                                scope.launch { list.scrollToItem(indexOf(curCi, pi)) }
                                            },
                                            modifier = Modifier.height(28.dp),
                                            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = ink.accent, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                                        )
                                    }
                                    BarIconButton(Icons.Outlined.ChevronRight, "Next chapter", if (next != null) Color.White else Color.White.copy(0.3f)) { next?.let { driver.stop(); vm.open(it.id, 0) } }
                                }
                                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSettings) NovelSettingsSheet(prefs, { f -> c.settings.updateNovel(f) }) { showSettings = false }

    if (showChapters) {
        val ink2 = LocalInk.current
        val curId = vm.current?.id
        ModalBottomSheet(
            onDismissRequest = { showChapters = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = ink2.bg,
        ) {
            val eps = vm.episodes
            val currentIdx = eps.indexOfFirst { it.id == curId }.coerceAtLeast(0)
            val ls = rememberLazyListState(initialFirstVisibleItemIndex = (currentIdx - 2).coerceAtLeast(0))
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
                Text("Chapters (${eps.size})", style = InkType.headline, color = ink2.label, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                LazyColumn(Modifier.weight(1f), state = ls) {
                    items(eps.size, key = { eps[it].id }) { i ->
                        val e = eps[i]
                        val sel = e.id == curId
                        Row(
                            Modifier.fillMaxWidth().pressable { showChapters = false; driver.stop(); vm.open(e.id) }.padding(horizontal = 16.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(e.name, style = InkType.body, color = if (sel) ink2.accent else ink2.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (sel) Icon(Icons.Outlined.Check, null, tint = ink2.accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterFooter(
    vm: NovelReaderViewModel, ch: Chap, last: Boolean, continuous: Boolean, secondary: Color, open: (String) -> Unit,
) {
    val next = vm.nextOf(ch.ep)
    val prev = vm.prevOf(ch.ep)
    Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            !last -> Box(Modifier.width(48.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(secondary.copy(alpha = 0.4f)))
            next == null -> Text("End of available chapters", color = secondary, style = InkType.callout)
            continuous -> {
                if (vm.appendError != null) FilledPill("Retry next chapter") { vm.appendNext() }
                else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = secondary)
                    Text("Loading next chapter", color = secondary, style = InkType.callout)
                }
            }
            else -> {
                FilledPill("Next chapter") { open(next.id) }
                if (prev != null) FilledPill("Previous chapter", filled = false) { open(prev.id) }
            }
        }
    }
}

@Composable
private fun MiniPlayer(
    d: TtsDriver, chapters: List<Chap>, rate: Float, bottomInset: Boolean, hazeState: HazeState, onRate: (Float) -> Unit,
) {
    val pos = NovelTtsPlayer.pos
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().glass(hazeState, dark = true))
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BarIconButton(Icons.Outlined.Close, "Stop listening", Color.White) { d.stop() }
                Column(Modifier.weight(1f)) {
                    Text("Listening", color = Color.White, style = InkType.callout, maxLines = 1)
                    Text(
                        if (pos != null) "${NovelTtsPlayer.chapterName} · ${pos.second + 1}/${NovelTtsPlayer.paraCount}" else "",
                        color = Color.White.copy(alpha = 0.6f), style = InkType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                BarIconButton(Icons.Outlined.SkipPrevious, "Previous paragraph", Color.White) { d.step(-1) }
                BarIconButton(if (d.state == 1) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (d.state == 1) "Pause" else "Play", Color.White) {
                    if (d.state == 1) d.pause() else d.resume()
                }
                BarIconButton(Icons.Outlined.SkipNext, "Next paragraph", Color.White) { d.step(1) }
                val steps = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.18f))
                        .pressable { onRate(steps.firstOrNull { it > rate + 0.01f } ?: steps.first()) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) { Text("${Math.round(rate * 100) / 100f}x", color = Color.White, style = InkType.caption) }
                Spacer(Modifier.width(4.dp))
            }
            if (bottomInset) Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}

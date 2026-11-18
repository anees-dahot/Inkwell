package app.inkwell.ui.screens

import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkwell.AppContainer
import app.inkwell.data.model.Entry
import app.inkwell.data.model.Episode
import app.inkwell.ui.components.*
import app.inkwell.ui.motion.pressable
import app.inkwell.ui.theme.InkType
import app.inkwell.ui.theme.LocalInk
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.FullscreenListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class PlayerViewModel(
    private val c: AppContainer,
    private val sourceId: String,
    private val entryId: String,
    private val startEpisodeId: String,
) : ViewModel() {
    var entry by mutableStateOf<Entry?>(null); private set
    var episodes by mutableStateOf<List<Episode>>(emptyList()); private set
    var current by mutableStateOf<Episode?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var watched by mutableStateOf<Set<String>>(emptySet()); private set

    init { load() }

    fun load() {
        viewModelScope.launch {
            error = null
            try {
                val d = c.repo.details(sourceId, entryId)
                entry = d.entry
                episodes = c.repo.episodes(sourceId, entryId)
                val ep = episodes.firstOrNull { it.id == startEpisodeId } ?: episodes.firstOrNull() ?: error("No episodes")
                select(ep)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load" }
        }
    }

    fun select(ep: Episode) {
        current = ep
        val en = entry ?: return
        viewModelScope.launch { c.repo.saveProgress(en, ep, 0, 0, false) }
    }

    fun markWatched(ep: Episode) {
        val en = entry ?: return
        watched = watched + ep.id
        viewModelScope.launch { c.repo.saveProgress(en, ep, 1, 1, true) }
    }

    fun next(): Episode? {
        val i = episodes.indexOfFirst { it.id == current?.id }
        return if (i > 0) episodes[i - 1] else null
    }
}

@Composable
fun PlayerScreen(sourceId: String, entryId: String, episodeId: String, onBack: () -> Unit) {
    app.inkwell.data.TrackReading(sourceId, entryId)
    val vm = inkViewModel("player/$sourceId/$entryId") { PlayerViewModel(it, sourceId, entryId, episodeId) }
    val c = LocalContainer.current
    val ink = LocalInk.current
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var exitFullscreen by remember { mutableStateOf<(() -> Unit)?>(null) }
    var player by remember { mutableStateOf<YouTubePlayer?>(null) }
    var ytError by remember { mutableStateOf<PlayerConstants.PlayerError?>(null) }
    val curVm by rememberUpdatedState(vm)

    val playerView = remember {
        YouTubePlayerView(context).apply { enableAutomaticInitialization = false }
    }
    var duration by remember { mutableFloatStateOf(0f) }

    DisposableEffect(playerView) {
        lifecycleOwner.lifecycle.addObserver(playerView)
        playerView.addFullscreenListener(object : FullscreenListener {
            override fun onEnterFullscreen(fullscreenView2: View, exitFullscreen2: () -> Unit) {
                fullscreenView = fullscreenView2; exitFullscreen = exitFullscreen2
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                activity?.window?.let { w ->
                    WindowCompat.getInsetsController(w, w.decorView).apply {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        hide(WindowInsetsCompat.Type.systemBars())
                    }
                }
            }
            override fun onExitFullscreen() {
                fullscreenView = null; exitFullscreen = null
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity?.window?.let { w ->
                    WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars())
                }
            }
        })
        val options = IFramePlayerOptions.Builder(context).controls(1).fullscreen(1).build()
        playerView.initialize(object : AbstractYouTubePlayerListener() {
            override fun onReady(youTubePlayer: YouTubePlayer) { player = youTubePlayer }
            override fun onVideoDuration(youTubePlayer: YouTubePlayer, d: Float) { duration = d }
            override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) { ytError = error }
            override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) {
                val ep = curVm.current ?: return
                if (duration > 0f && second / duration > 0.9f && ep.id !in curVm.watched) curVm.markWatched(ep)
            }
            override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) {
                if (state == PlayerConstants.PlayerState.ENDED) curVm.next()?.let { curVm.select(it) }
            }
        }, true, options)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(playerView)
            playerView.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    val ep = vm.current
    LaunchedEffect(player, ep?.id) {
        val p = player; val e = ep
        if (p != null && e != null) { ytError = null; duration = 0f; p.loadVideo(e.id, 0f) }
    }
    BackHandler(enabled = fullscreenView != null) { exitFullscreen?.invoke() }

    Box(Modifier.fillMaxSize().background(ink.bg)) {
        Column(Modifier.fillMaxSize()) {
            if (fullscreenView == null) {
                Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                Row(Modifier.fillMaxWidth().height(TopBarHeight).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BarIconButton(Icons.AutoMirrored.Outlined.ArrowBackIos, "Back", onClick = onBack)
                    Text(vm.entry?.title ?: "", style = InkType.headline, color = ink.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                AndroidView(factory = {
                    (playerView.parent as? android.view.ViewGroup)?.removeView(playerView)
                    playerView
                }, modifier = Modifier.fillMaxSize())
                if (ytError != null) {
                    Column(
                        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.8f)).padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text("This video can't be played inside the app.", color = Color.White, style = InkType.callout)
                        Spacer(Modifier.height(10.dp))
                        FilledPill("Open in YouTube") {
                            ep?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${it.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
            }
            if (vm.error != null && ep == null) {
                ErrorState(vm.error!!, vm::load)
            } else if (ep != null) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp)) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text(ep.name, style = InkType.title.copy(fontSize = 19.sp), color = ink.label)
                            Spacer(Modifier.height(4.dp))
                            Text(relativeDate(ep.dateMillis), style = InkType.caption, color = ink.secondary)
                            vm.next()?.let { n ->
                                Spacer(Modifier.height(12.dp))
                                FilledPill("Next episode", filled = false) { vm.select(n) }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Showing latest uploads.", style = InkType.callout, color = ink.secondary)
                            Spacer(Modifier.height(8.dp))
                            FilledPill("Open full playlist in YouTube", filled = false) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.youtube.openUrl(entryId))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                    }
                    items(vm.episodes, key = { it.id }) { e ->
                        val playing = e.id == ep.id
                        Row(
                            Modifier.fillMaxWidth().pressable { vm.select(e) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (playing) Icon(Icons.Outlined.PlayArrow, null, tint = ink.accent, modifier = Modifier.size(20.dp))
                            else Spacer(Modifier.width(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                e.name, style = InkType.callout, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                color = if (playing) ink.accent else if (e.id in vm.watched) ink.secondary else ink.label,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Hairline(startInset = 44.dp)
                    }
                }
            }
        }
        fullscreenView?.let { fv ->
            AndroidView(
                factory = { ctx -> FrameLayout(ctx).apply { addView(fv) } },
                onRelease = { it.removeAllViews() },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }
}

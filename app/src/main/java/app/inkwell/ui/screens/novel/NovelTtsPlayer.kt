package app.inkwell.ui.screens.novel

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import app.inkwell.InkwellApp
import app.inkwell.data.model.Entry
import app.inkwell.data.model.Episode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide read-aloud playback. Lives outside the reader screen so speech keeps going when the
 * user leaves; [NovelTtsService] hosts it as a foreground media service. The reader just observes
 * [state]/[pos] (highlight) and sends commands.
 */
object NovelTtsPlayer {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var state by mutableIntStateOf(0); private set // 0 idle, 1 playing, 2 paused
    var pos by mutableStateOf<Pair<String, Int>?>(null); private set
    var entry by mutableStateOf<Entry?>(null); private set
    var tts by mutableStateOf<NovelTts?>(null); private set
    val active get() = state != 0

    /** True while a reader screen is composed (it saves progress itself then). */
    var attached = false
    /** Service hook, called after every state/position change. */
    var listener: (() -> Unit)? = null

    private var episodes: List<Episode> = emptyList() // newest first
    private val texts = HashMap<String, List<String>>()
    private var lossResume = false
    private var focusReq: AudioFocusRequest? = null

    fun prepare(ctx: Context): NovelTts {
        app = ctx.applicationContext
        return tts ?: NovelTts(app).also { tts = it }
    }

    fun isFor(sourceId: String, entryId: String) = entry?.let { it.sourceId == sourceId && it.id == entryId } == true
    val chapterName: String get() = pos?.let { p -> episodes.firstOrNull { it.id == p.first }?.name } ?: ""
    val paraCount: Int get() = pos?.let { texts[it.first]?.size } ?: 0

    fun start(ctx: Context, entry: Entry, episodes: List<Episode>, epId: String, para: Int, paras: List<String>) {
        prepare(ctx)
        this.entry = entry; this.episodes = episodes
        texts[epId] = paras
        pos = epId to para
        state = 1
        requestFocus()
        runCatching { ContextCompat.startForegroundService(app, Intent(app, NovelTtsService::class.java)) }
        changed()
        speak()
    }

    private fun prefs() = (app as InkwellApp).container.settings.novel.value

    private fun speak() {
        val (e, p) = pos ?: return
        val text = texts[e]?.getOrNull(p)
        if (text == null) { stop(); return }
        if (text.isBlank()) { step(1); return }
        val en = entry
        if (!attached && en != null) scope.launch {
            val ep = episodes.firstOrNull { it.id == e } ?: return@launch
            runCatching { (app as InkwellApp).container.repo.saveProgress(en, ep, p, texts[e]?.size ?: 0, false) }
        }
        val pr = prefs()
        tts?.speak(text, pr.ttsRate, pr.ttsPitch) { if (state == 1) step(1) }
    }

    fun step(d: Int) {
        val (e, p) = pos ?: return
        val paras = texts[e] ?: return stop()
        val n = p + d
        if (n in paras.indices) { moveTo(e to n); if (state == 1) speak(); return }
        val i = episodes.indexOfFirst { it.id == e }
        val target = if (n >= paras.size) episodes.getOrNull(i - 1) else episodes.getOrNull(i + 1)
        if (target == null) { if (n >= paras.size) stop(); return }
        val en = entry ?: return stop()
        scope.launch {
            val t = texts[target.id]
                ?: runCatching { (app as InkwellApp).container.repo.chapterText(en.sourceId, en.id, target.id) }.getOrNull()?.takeIf { it.isNotEmpty() }
            if (t == null) { stop(); return@launch }
            texts[target.id] = t
            moveTo(target.id to if (n < 0) t.lastIndex else 0)
            if (state == 1) speak()
        }
    }

    fun pause() { tts?.stop(); state = 2; changed() }
    fun resume() { if (pos == null) return; requestFocus(); state = 1; changed(); speak() }
    fun toggle() { if (state == 1) pause() else if (state == 2) resume() }
    fun restart() { if (state == 1) speak() }

    fun stop() {
        tts?.stop(); state = 0; pos = null; texts.clear(); lossResume = false
        abandonFocus()
        changed()
    }

    /** Free the engine when nothing is playing and no reader is open. */
    fun releaseIfIdle() {
        if (state == 0 && !attached) { tts?.release(); tts = null }
    }

    private fun moveTo(p: Pair<String, Int>) { pos = p; changed() }
    private fun changed() { listener?.invoke() }

    // ---- audio focus ----
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { lossResume = false; if (state == 1) pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (state == 1) { lossResume = true; pause() }
            AudioManager.AUDIOFOCUS_GAIN -> if (lossResume) { lossResume = false; resume() }
        }
    }

    private fun requestFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = focusReq ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener(focusListener).build().also { focusReq = it }
        am.requestAudioFocus(req)
    }

    private fun abandonFocus() {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusReq?.let { am.abandonAudioFocusRequest(it) }
    }
}

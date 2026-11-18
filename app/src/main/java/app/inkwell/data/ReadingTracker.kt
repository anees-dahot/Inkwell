package app.inkwell.data

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.inkwell.data.db.InkDatabase
import app.inkwell.data.db.ReadingSession
import app.inkwell.ui.components.LocalContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Records foreground reading/watching sessions. Readers call [TrackReading] once; it starts a session
 * when the screen is visible and ends it when the screen stops or leaves. Nothing is recorded in incognito.
 */
class ReadingTracker(private val db: InkDatabase, private val settings: Settings, private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var token = 0L
    private var source: String? = null
    private var entry: String? = null
    private var wallStart = 0L
    private var elapsedStart = 0L

    /** Begins a session (ending any open one) and returns a token for [stop]. */
    @Synchronized
    fun start(sourceId: String, entryId: String): Long {
        finish()
        if (settings.incognito.value) return 0L
        source = sourceId; entry = entryId
        wallStart = System.currentTimeMillis(); elapsedStart = SystemClock.elapsedRealtime()
        token += 1
        return token
    }

    @Synchronized
    fun stop(t: Long) {
        if (t != 0L && t == token) finish()
    }

    private fun finish() {
        val s = source ?: return
        val e = entry ?: return
        source = null; entry = null
        val dur = (SystemClock.elapsedRealtime() - elapsedStart).coerceAtMost(MAX_SESSION_MS)
        if (dur < MIN_SESSION_MS || settings.incognito.value) return
        val started = wallStart
        scope.launch {
            runCatching { db.stats().insert(ReadingSession(sourceId = s, entryId = e, startedAt = started, durationMs = dur)) }
            runCatching { app.inkwell.widget.InkwellWidgets.refresh(appContext) }
        }
    }

    companion object {
        const val MIN_SESSION_MS = 3_000L
        const val MAX_SESSION_MS = 6 * 60 * 60 * 1000L
    }
}

/** One-line hook for readers/players: tracks time spent while this screen is on screen. */
@Composable
fun TrackReading(sourceId: String, entryId: String) {
    val tracker = LocalContainer.current.tracker
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, sourceId, entryId) {
        var token = 0L
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) token = tracker.start(sourceId, entryId)
            else if (e == Lifecycle.Event.ON_STOP) tracker.stop(token)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); tracker.stop(token) }
    }
}

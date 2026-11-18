package app.inkwell.ui.screens.novel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import app.inkwell.AppLaunch
import app.inkwell.MainActivity
import app.inkwell.R

/** Foreground media service hosting [NovelTtsPlayer] so read-aloud survives leaving the reader / locking the screen. */
class NovelTtsService : Service() {
    private lateinit var session: MediaSession
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (NovelTtsPlayer.state == 1) NovelTtsPlayer.pause() }
    }
    private var foreground = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Read aloud", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        session = MediaSession(this, "InkwellTts").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { NovelTtsPlayer.resume() }
                override fun onPause() { NovelTtsPlayer.pause() }
                override fun onSkipToNext() { NovelTtsPlayer.step(1) }
                override fun onSkipToPrevious() { NovelTtsPlayer.step(-1) }
                override fun onStop() { NovelTtsPlayer.stop() }
            })
            isActive = true
        }
        ContextCompat.registerReceiver(this, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
        NovelTtsPlayer.listener = { refresh() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            A_TOGGLE -> NovelTtsPlayer.toggle()
            A_NEXT -> NovelTtsPlayer.step(1)
            A_PREV -> NovelTtsPlayer.step(-1)
            A_STOP -> NovelTtsPlayer.stop()
        }
        goForeground()
        if (!NovelTtsPlayer.active) quit()
        return START_NOT_STICKY
    }

    private fun refresh() {
        if (!NovelTtsPlayer.active) { quit(); return }
        goForeground()
    }

    private fun quit() {
        NovelTtsPlayer.listener = null
        stopForeground(STOP_FOREGROUND_REMOVE); foreground = false
        stopSelf()
    }

    private fun goForeground() {
        val n = build()
        if (!foreground) {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(ID, n)
            foreground = true
        } else getSystemService(NotificationManager::class.java).notify(ID, n)
    }

    private fun act(code: Int, action: String) =
        PendingIntent.getService(this, code, Intent(this, NovelTtsService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun build(): Notification {
        val p = NovelTtsPlayer
        val playing = p.state == 1
        val en = p.entry
        val title = en?.title ?: "Inkwell"
        val chapter = p.chapterName
        val para = p.pos?.second
        val text = if (chapter.isEmpty()) "Reading aloud" else if (para != null && p.paraCount > 0) "$chapter · ${para + 1}/${p.paraCount}" else chapter

        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, chapter.ifEmpty { title }).putString(MediaMetadata.METADATA_KEY_ARTIST, title).build())
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build(),
        )

        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply {
            if (en != null && p.pos != null) {
                putExtra(AppLaunch.EXTRA_NOVEL_SOURCE, en.sourceId); putExtra(AppLaunch.EXTRA_NOVEL_ENTRY, en.id); putExtra(AppLaunch.EXTRA_NOVEL_EPISODE, p.pos!!.first)
            }
        }
        fun action(icon: Int, label: String, code: Int, a: String) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), label, act(code, a)).build()
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.ic_stat_inkwell)
            .setContentTitle(title).setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 77, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOnlyAlertOnce(true).setOngoing(playing).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(action(android.R.drawable.ic_media_previous, "Previous paragraph", 1, A_PREV))
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, "Pause", 2, A_TOGGLE)
                else action(android.R.drawable.ic_media_play, "Play", 2, A_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_next, "Next paragraph", 3, A_NEXT))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Stop", 4, A_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(noisy) }
        session.isActive = false; session.release()
        if (NovelTtsPlayer.active) NovelTtsPlayer.stop()
        NovelTtsPlayer.listener = null
        NovelTtsPlayer.releaseIfIdle()
        super.onDestroy()
    }

    companion object {
        const val CH = "tts"
        const val ID = 4201
        const val A_TOGGLE = "app.inkwell.tts.TOGGLE"
        const val A_NEXT = "app.inkwell.tts.NEXT"
        const val A_PREV = "app.inkwell.tts.PREV"
        const val A_STOP = "app.inkwell.tts.STOP"
    }
}

package app.inkwell.work

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import app.inkwell.InkwellApp
import app.inkwell.data.Repository
import app.inkwell.data.db.DlStatus
import app.inkwell.data.db.DownloadItem
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/** Entry points for the offline download queue (WorkManager-backed, 2 chapters at a time). */
object Downloads {
    private const val WORK = "downloads"
    const val NOTIF_ID = 2001
    const val ACTION_PAUSE = "app.inkwell.DL_PAUSE"
    const val ACTION_CANCEL = "app.inkwell.DL_CANCEL"
    const val CONCURRENCY = 2

    internal val active = ConcurrentHashMap<String, Job>()
    internal fun key(i: DownloadItem) = "${i.sourceId}/${i.entryId}/${i.episodeId}"

    private fun request(ctx: Context): OneTimeWorkRequest {
        val wifi = (ctx.applicationContext as InkwellApp).container.settings.downloadWifiOnly.value
        return OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (wifi) NetworkType.UNMETERED else NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
    }

    fun start(ctx: Context) {
        WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request(ctx))
    }

    /** Re-applies network constraints (wifi-only toggled) if anything is pending. */
    suspend fun restartIfPending(ctx: Context) {
        val repo = (ctx.applicationContext as InkwellApp).container.repo
        if (repo.hasQueuedDownloads()) {
            WorkManager.getInstance(ctx.applicationContext).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request(ctx))
        }
    }

    fun stop(ctx: Context) { WorkManager.getInstance(ctx.applicationContext).cancelUniqueWork(WORK) }

    fun cancelActive(i: DownloadItem) { active.remove(key(i))?.cancel() }

    /** Picks up unfinished queue entries after a process restart. */
    suspend fun resumeOnStart(ctx: Context) {
        val repo = (ctx.applicationContext as InkwellApp).container.repo
        if (repo.pendingDownloads() > 0) start(ctx)
    }
}

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val repo: Repository get() = (applicationContext as InkwellApp).container.repo

    override suspend fun getForegroundInfo(): ForegroundInfo = info(emptyList())

    private fun info(items: List<DownloadItem>): ForegroundInfo {
        val active = items.filter { it.status == DlStatus.DOWNLOADING }
        val pending = items.count { it.status == DlStatus.QUEUED || it.status == DlStatus.DOWNLOADING }
        val done = active.sumOf { it.progress }
        val total = active.sumOf { it.total }
        fun action(name: String, label: String, code: Int) = NotificationCompat.Action(
            0, label,
            PendingIntent.getBroadcast(
                applicationContext, code, Intent(applicationContext, DownloadActionReceiver::class.java).setAction(name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val b = NotificationCompat.Builder(applicationContext, Notifier.CH_DOWNLOADS)
            .setSmallIcon(app.inkwell.R.drawable.ic_stat_inkwell).setOngoing(true).setOnlyAlertOnce(true)
            .setContentTitle(if (pending > 1) "Downloading $pending chapters" else "Downloading chapter")
            .setContentText(active.firstOrNull()?.let { "${it.entryTitle} · ${it.episodeName}" } ?: "Preparing…")
            .setProgress(total.coerceAtLeast(0), done, total == 0)
            .addAction(action(Downloads.ACTION_PAUSE, "Pause", 1))
            .addAction(action(Downloads.ACTION_CANCEL, "Cancel", 2))
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(Downloads.NOTIF_ID, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(Downloads.NOTIF_ID, b.build())
    }

    override suspend fun doWork(): Result {
        Notifier.ensureChannels(applicationContext)
        repo.resetStuckDownloads()
        runCatching { setForeground(getForegroundInfo()) }
        try {
            coroutineScope {
                val ui = launch {
                    repo.downloads().collect { items ->
                        runCatching { setForeground(info(items)) }
                        delay(600)
                    }
                }
                do {
                    (1..Downloads.CONCURRENCY).map {
                        launch {
                            while (true) {
                                val item = repo.claimNextDownload()
                                if (item != null) { runOne(item); continue }
                                // idle: stay alive while the other slot is busy so newly queued chapters run in parallel
                                if (repo.pendingDownloads() == 0) break
                                delay(400)
                            }
                        }
                    }.joinAll()
                } while (repo.hasQueuedDownloads())
                ui.cancel()
            }
        } finally {
            withContext(NonCancellable) { repo.resetStuckDownloads() }
        }
        return Result.success()
    }

    private suspend fun CoroutineScope.runOne(item: DownloadItem) {
        val k = Downloads.key(item)
        val job = launch {
            try {
                val bytes = repo.downloader.download(item) { d, t -> repo.downloadProgress(item, d, t) }
                repo.downloadDone(item, bytes)
            } catch (c: CancellationException) { throw c } catch (x: Exception) {
                repo.downloadFailed(item, x.message ?: "Download failed")
            }
        }
        Downloads.active[k] = job
        try { job.join() } finally { Downloads.active.remove(k) }
    }
}

class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = (context.applicationContext as InkwellApp).container.repo
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Downloads.ACTION_PAUSE -> repo.pauseDownloads()
                    Downloads.ACTION_CANCEL -> repo.clearDownloadQueue()
                }
            } finally { pending.finish() }
        }
    }
}

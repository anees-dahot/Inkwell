package app.inkwell.work

import android.content.Context
import androidx.work.*
import app.inkwell.InkwellApp
import app.inkwell.data.ReleaseEstimator
import app.inkwell.data.Settings
import app.inkwell.data.db.CustomReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

object Scheduler {
    private const val PERIODIC = "library-update"
    private const val NOW = "library-update-now"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun syncPeriodic(ctx: Context, s: Settings) {
        val wm = WorkManager.getInstance(ctx)
        val hours = s.updateHours.value
        if (hours <= 0) { wm.cancelUniqueWork(PERIODIC); return }
        val req = PeriodicWorkRequestBuilder<LibraryUpdateWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints(s)).build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    private fun constraints(s: Settings) = Constraints.Builder()
        .setRequiredNetworkType(if (s.updateWifiOnly.value) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(s.updateCharging.value).build()

    fun updateNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<LibraryUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
    }

    /** Next [weekday] (Calendar constant) at hour:minute strictly after [now]. */
    fun nextWeekly(now: Long, weekday: Int, hour: Int, minute: Int): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val add = (weekday - c.get(Calendar.DAY_OF_WEEK) + 7) % 7
        c.add(Calendar.DAY_OF_YEAR, add)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 7)
        return c.timeInMillis
    }

    fun scheduleCustom(ctx: Context, r: CustomReminder) {
        val wm = WorkManager.getInstance(ctx)
        val name = "reminder-${r.id}"
        if (!r.enabled) { wm.cancelUniqueWork(name); return }
        val delay = nextWeekly(System.currentTimeMillis(), r.weekday, r.hour, r.minute) - System.currentTimeMillis()
        val req = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("kind" to "custom", "id" to r.id)).build()
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, req)
    }

    fun cancelCustom(ctx: Context, id: String) { WorkManager.getInstance(ctx).cancelUniqueWork("reminder-$id") }

    /** Schedules a 09:00 reminder on each bell title's expected release day. */
    suspend fun syncExpected(ctx: Context) {
        val repo = (ctx.applicationContext as InkwellApp).container.repo
        val wm = WorkManager.getInstance(ctx)
        val now = System.currentTimeMillis()
        val bellNames = HashSet<String>()
        for (i in repo.remindItems()) {
            val name = "expected-${i.sourceId}/${i.entryId}"
            val next = i.expectedNext
            if (next == null) { wm.cancelUniqueWork(name); continue }
            val c = Calendar.getInstance().apply { timeInMillis = next; set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0) }
            var at = c.timeInMillis
            if (at <= now) {
                if (ReleaseEstimator.dayOf(next) == ReleaseEstimator.dayOf(now)) at = now + 60_000 else { wm.cancelUniqueWork(name); continue }
            }
            bellNames += name
            val req = OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(at - now, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf("kind" to "expected", "s" to i.sourceId, "e" to i.entryId)).build()
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, req)
        }
    }

    fun syncExpectedAsync(ctx: Context) { scope.launch { syncExpected(ctx.applicationContext) } }

    fun syncAll(ctx: Context) {
        val app = ctx.applicationContext
        val c = (app as InkwellApp).container
        syncPeriodic(app, c.settings)
        scope.launch {
            runCatching { Downloads.resumeOnStart(app) }
            c.repo.allReminders().forEach { scheduleCustom(app, it) }
            syncExpected(app)
        }
    }
}

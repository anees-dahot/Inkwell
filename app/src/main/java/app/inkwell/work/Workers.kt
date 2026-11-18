package app.inkwell.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.inkwell.InkwellApp

class LibraryUpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as InkwellApp).container.repo
        val fresh = repo.refreshLibrary()
        Notifier.newChapters(applicationContext, fresh)
        runCatching { Scheduler.syncExpected(applicationContext) }
        return Result.success()
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as InkwellApp).container.repo
        when (inputData.getString("kind")) {
            "custom" -> {
                val r = repo.getReminder(inputData.getString("id") ?: return Result.success()) ?: return Result.success()
                if (!r.enabled) return Result.success()
                Notifier.reminder(applicationContext, r.id.hashCode(), "Time to read", r.title, r.sourceId, r.entryId, r.title, r.coverUrl)
                Scheduler.scheduleCustom(applicationContext, r)
            }
            "expected" -> {
                val s = inputData.getString("s") ?: return Result.success()
                val e = inputData.getString("e") ?: return Result.success()
                val item = repo.libraryItem(s, e)?.takeIf { it.remind } ?: return Result.success()
                val n = item.lastChapterNumber?.let { "Ch. ${Notifier.fmt(kotlin.math.floor(it) + 1f)} expected today" } ?: "New chapter expected today"
                Notifier.reminder(applicationContext, "exp-$s/$e".hashCode(), item.title, n, s, e, item.title, item.coverUrl)
            }
        }
        return Result.success()
    }
}

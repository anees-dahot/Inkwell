package app.inkwell.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.inkwell.AppLaunch
import app.inkwell.MainActivity
import app.inkwell.data.NewChapters
import app.inkwell.data.model.Episode

object Notifier {
    const val CH_UPDATES = "updates"
    const val CH_REMINDERS = "reminders"
    const val CH_DOWNLOADS = "downloads"
    private const val GROUP = "inkwell.updates"
    private const val SUMMARY_ID = 1001

    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, "Library updates", NotificationManager.IMPORTANCE_LOW).apply {
            description = "New chapters found while updating your library"
        })
        nm.createNotificationChannel(NotificationChannel(CH_DOWNLOADS, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of chapter downloads"
        })
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Release reminders and reading reminders"
        })
    }

    fun canPost(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pending(ctx: Context, code: Int, fill: Intent.() -> Unit): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply(fill)
        return PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun chapterLabel(eps: List<Episode>): String {
        if (eps.size > 1) return "${eps.size} new chapters"
        val e = eps.first()
        return e.number?.let { "Ch. ${fmt(it)}" } ?: e.name
    }

    fun fmt(n: Float) = if (n % 1f == 0f) n.toInt().toString() else n.toString()

    /** Grouped notification for titles without a bell; bell titles get their own reminder notification. */
    fun newChapters(ctx: Context, list: List<NewChapters>) {
        if (list.isEmpty() || !canPost(ctx)) return
        ensureChannels(ctx)
        val nm = NotificationManagerCompat.from(ctx)
        val (bell, plain) = list.partition { it.item.remind }
        for (n in bell) {
            val i = n.item
            reminder(ctx, "${i.sourceId}/${i.entryId}".hashCode(), "New chapter: ${i.title}", chapterLabel(n.episodes), i.sourceId, i.entryId, i.title, i.coverUrl)
        }
        if (plain.isEmpty()) return
        val total = plain.sumOf { it.episodes.size }
        val names = plain.map { it.item.title }
        val head = names.take(3).joinToString(", ") + if (names.size > 3) "…" else ""
        val open = pending(ctx, 1) { putExtra(AppLaunch.EXTRA_TAB, 1) }
        for (n in plain) {
            val i = n.item
            nm.notify(
                "${i.sourceId}/${i.entryId}".hashCode(),
                NotificationCompat.Builder(ctx, CH_UPDATES).setSmallIcon(app.inkwell.R.drawable.ic_stat_inkwell)
                    .setContentTitle(i.title).setContentText(chapterLabel(n.episodes))
                    .setGroup(GROUP).setAutoCancel(true).setContentIntent(open).build()
            )
        }
        val inbox = NotificationCompat.InboxStyle().also { st -> plain.take(6).forEach { st.addLine("${it.item.title} · ${chapterLabel(it.episodes)}") } }
        nm.notify(
            SUMMARY_ID,
            NotificationCompat.Builder(ctx, CH_UPDATES).setSmallIcon(app.inkwell.R.drawable.ic_stat_inkwell)
                .setContentTitle(if (total == 1) "1 new chapter" else "$total new chapters")
                .setContentText(head).setStyle(inbox)
                .setGroup(GROUP).setGroupSummary(true).setAutoCancel(true).setContentIntent(open).build()
        )
    }

    /** Single reminder notification that opens the title's details page. */
    fun reminder(ctx: Context, id: Int, title: String, text: String, s: String, e: String, entryTitle: String, cover: String?) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val open = pending(ctx, id) {
            putExtra(AppLaunch.EXTRA_SOURCE, s); putExtra(AppLaunch.EXTRA_ENTRY, e)
            putExtra(AppLaunch.EXTRA_TITLE, entryTitle); putExtra(AppLaunch.EXTRA_COVER, cover)
        }
        NotificationManagerCompat.from(ctx).notify(
            id,
            NotificationCompat.Builder(ctx, CH_REMINDERS).setSmallIcon(app.inkwell.R.drawable.ic_stat_inkwell)
                .setContentTitle(title).setContentText(text).setAutoCancel(true).setContentIntent(open).build()
        )
    }
}

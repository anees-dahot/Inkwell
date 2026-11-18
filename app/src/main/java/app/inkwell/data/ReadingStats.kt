package app.inkwell.data

import app.inkwell.data.db.ProgressItem
import app.inkwell.data.db.ReadingSession
import app.inkwell.data.model.ContentType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayStat(val day: LocalDate, val minutes: Float, val chapters: Int)
data class TitleStat(val sourceId: String, val entryId: String, val title: String, val chapters: Int, val timeMs: Long)

data class ReadingStats(
    val totalChapters: Int,
    val chaptersByType: Map<ContentType, Int>,
    val totalTimeMs: Long,
    val timeByType: Map<ContentType, Long>,
    val currentStreak: Int,
    val longestStreak: Int,
    /** Last 30 days, oldest first, ending today. */
    val last30: List<DayStat>,
    val top: List<TitleStat>,
)

object StatsCalc {
    fun compute(
        read: List<ProgressItem>,
        sessions: List<ReadingSession>,
        titles: Map<Pair<String, String>, String>,
        typeOf: (String) -> ContentType,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
        topN: Int = 5,
    ): ReadingStats {
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val today = day(now)

        val chaptersByType = read.groupingBy { typeOf(it.sourceId) }.eachCount()
        val timeByType = HashMap<ContentType, Long>()
        for (s in sessions) timeByType.merge(typeOf(s.sourceId), s.durationMs, Long::plus)

        val active = HashSet<LocalDate>()
        read.forEach { active += day(it.updatedAt) }
        sessions.forEach { active += day(it.startedAt) }

        // current streak: consecutive active days ending today (or yesterday if today is still empty)
        var cur = 0
        var d = if (today in active) today else today.minusDays(1)
        while (d in active) { cur++; d = d.minusDays(1) }
        var longest = 0
        var run = 0
        var prev: LocalDate? = null
        for (a in active.sorted()) {
            run = if (prev != null && prev.plusDays(1) == a) run + 1 else 1
            if (run > longest) longest = run
            prev = a
        }

        val minutesPerDay = HashMap<LocalDate, Float>()
        for (s in sessions) minutesPerDay.merge(day(s.startedAt), s.durationMs / 60_000f, Float::plus)
        val chaptersPerDay = read.groupingBy { day(it.updatedAt) }.eachCount()
        val last30 = (29 downTo 0).map { n ->
            val x = today.minusDays(n.toLong())
            DayStat(x, minutesPerDay[x] ?: 0f, chaptersPerDay[x] ?: 0)
        }

        val chapterCount = read.groupingBy { it.sourceId to it.entryId }.eachCount()
        val timeMap = HashMap<Pair<String, String>, Long>()
        for (s in sessions) timeMap.merge(s.sourceId to s.entryId, s.durationMs, Long::plus)
        val top = (chapterCount.keys + timeMap.keys).map { k ->
            TitleStat(k.first, k.second, titles[k] ?: k.second, chapterCount[k] ?: 0, timeMap[k] ?: 0L)
        }.sortedWith(compareByDescending<TitleStat> { it.chapters }.thenByDescending { it.timeMs }).take(topN)

        return ReadingStats(
            totalChapters = read.size, chaptersByType = chaptersByType,
            totalTimeMs = sessions.sumOf { it.durationMs }, timeByType = timeByType,
            currentStreak = cur, longestStreak = longest, last30 = last30, top = top,
        )
    }

    fun formatDuration(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 1 -> if (ms >= 1000) "${ms / 1000}s" else "0m"
            m < 60 -> "${m}m"
            else -> "${m / 60}h ${m % 60}m"
        }
    }
}

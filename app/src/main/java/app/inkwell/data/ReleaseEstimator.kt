package app.inkwell.data

import java.util.TimeZone

/** Mihon-style next-release estimate from chapter dates (median interval of recent release days). */
object ReleaseEstimator {
    const val DAY = 86_400_000L

    data class Result(val lastDate: Long?, val lastNumber: Float?, val expectedNext: Long?)

    fun dayOf(millis: Long, tz: TimeZone = TimeZone.getDefault()): Long =
        Math.floorDiv(millis + tz.getOffset(millis), DAY)

    fun startOfDay(day: Long, tz: TimeZone = TimeZone.getDefault()): Long {
        val guess = day * DAY - tz.getOffset(day * DAY)
        return day * DAY - tz.getOffset(guess)
    }

    fun estimate(chapters: List<Pair<Float?, Long?>>, now: Long, tz: TimeZone = TimeZone.getDefault()): Result {
        val dates = chapters.mapNotNull { it.second }.filter { it > 0 }
        val lastNumber = chapters.mapNotNull { it.first }.maxOrNull()
        if (dates.isEmpty()) return Result(null, lastNumber, null)
        val last = dates.max()
        val days = dates.map { dayOf(it, tz) }.distinct().sortedDescending().take(10)
        if (days.size < 3) return Result(last, lastNumber, null)
        val gaps = days.zipWithNext { a, b -> a - b }.sorted()
        val mid = gaps.size / 2
        val interval = (if (gaps.size % 2 == 1) gaps[mid].toDouble() else (gaps[mid - 1] + gaps[mid]) / 2.0)
            .let { Math.round(it) }.coerceAtLeast(1)
        val today = dayOf(now, tz)
        val lastDay = days.first()
        val since = today - lastDay
        if (since > maxOf(interval * 6, 45L)) return Result(last, lastNumber, null)
        val k = maxOf(1L, Math.floorDiv(since + interval - 1, interval))
        return Result(last, lastNumber, startOfDay(lastDay + k * interval, tz))
    }
}

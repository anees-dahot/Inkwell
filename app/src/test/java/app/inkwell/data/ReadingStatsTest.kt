package app.inkwell.data

import app.inkwell.data.db.ProgressItem
import app.inkwell.data.db.ReadingSession
import app.inkwell.data.model.ContentType
import org.junit.Assert.assertEquals
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Test

class ReadingStatsTest {
    private val zone = ZoneOffset.UTC
    private fun ms(d: LocalDate, h: Int = 12) = d.atTime(h, 0).toInstant(zone).toEpochMilli()
    private val today = LocalDate.of(2026, 5, 20)
    private fun prog(src: String, e: String, ep: String, d: LocalDate) = ProgressItem(src, e, ep, 0, 0, true, ms(d))
    private fun typeOf(s: String) = if (s == "yt") ContentType.VIDEO else ContentType.MANGA

    @Test fun streaksAndTotals() {
        val read = listOf(
            prog("md", "a", "1", today), prog("md", "a", "2", today.minusDays(1)), prog("md", "a", "3", today.minusDays(2)),
            prog("md", "b", "1", today.minusDays(10)), prog("md", "b", "2", today.minusDays(11)),
            prog("yt", "v", "1", today.minusDays(40)),
        )
        val sessions = listOf(ReadingSession(1, "md", "a", ms(today), 600_000), ReadingSession(2, "yt", "v", ms(today.minusDays(5)), 60_000))
        val s = StatsCalc.compute(read, sessions, mapOf(("md" to "a") to "Alpha"), ::typeOf, ms(today), zone)
        assertEquals(6, s.totalChapters)
        assertEquals(5, s.chaptersByType[ContentType.MANGA])
        assertEquals(1, s.chaptersByType[ContentType.VIDEO])
        assertEquals(660_000L, s.totalTimeMs)
        assertEquals(3, s.currentStreak)
        assertEquals(3, s.longestStreak)
        assertEquals(30, s.last30.size)
        assertEquals(10f, s.last30.last().minutes, 0.01f)
        assertEquals("Alpha", s.top.first().title)
    }

    @Test fun streakContinuesFromYesterday() {
        val read = listOf(prog("md", "a", "1", today.minusDays(1)), prog("md", "a", "2", today.minusDays(2)))
        val s = StatsCalc.compute(read, emptyList(), emptyMap(), ::typeOf, ms(today), zone)
        assertEquals(2, s.currentStreak)
        val gap = StatsCalc.compute(listOf(prog("md", "a", "1", today.minusDays(3))), emptyList(), emptyMap(), ::typeOf, ms(today), zone)
        assertEquals(0, gap.currentStreak)
        assertEquals(1, gap.longestStreak)
    }
}

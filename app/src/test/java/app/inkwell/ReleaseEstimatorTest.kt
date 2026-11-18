package app.inkwell

import app.inkwell.data.ReleaseEstimator
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

class ReleaseEstimatorTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val d = ReleaseEstimator.DAY

    @Test fun weeklyReleaseProjectsNextWeek() {
        val now = 100 * d + 3 * 3_600_000L
        val chapters = (0..4).map { (it + 1f) to (100 * d - (4 - it) * 7 * d + 1000) }
        val r = ReleaseEstimator.estimate(chapters.map { it.first to it.second }, now, utc)
        assertEquals(107L, r.expectedNext!! / d)
        assertEquals(5f, r.lastNumber)
    }

    @Test fun tooFewChaptersUnknown() {
        val r = ReleaseEstimator.estimate(listOf(1f to 5 * d, 2f to 12 * d), 13 * d, utc)
        assertNull(r.expectedNext)
    }

    @Test fun staleSeriesUnknown() {
        val r = ReleaseEstimator.estimate((1..5).map { it.toFloat() to it * 7 * d }, 1000 * d, utc)
        assertNull(r.expectedNext)
    }
}

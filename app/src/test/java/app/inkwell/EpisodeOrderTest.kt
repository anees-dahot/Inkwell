package app.inkwell

import app.inkwell.data.model.Episode
import app.inkwell.data.normalizeEpisodes
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeOrderTest {
    private fun ep(id: String, n: Float?, d: Long? = null) = Episode(id, id, n, d)

    @Test fun ascendingNumbersFlipToNewestFirst() {
        val out = normalizeEpisodes(listOf(ep("a", 1f), ep("b", 2f), ep("c", 3f)))
        assertEquals(listOf("c", "b", "a"), out.map { it.id })
    }

    @Test fun descendingStaysAndIsStable() {
        val out = normalizeEpisodes(listOf(ep("c", 3f), ep("b", 2f), ep("a", 1f)))
        assertEquals(listOf("c", "b", "a"), out.map { it.id })
    }

    @Test fun unnumberedUsesDateTrend() {
        val out = normalizeEpisodes(listOf(ep("a", null, 1), ep("b", null, 2), ep("c", null, 3)))
        assertEquals(listOf("c", "b", "a"), out.map { it.id })
    }

    @Test fun noSignalKeepsOrder() {
        val out = normalizeEpisodes(listOf(ep("a", null), ep("b", null)))
        assertEquals(listOf("a", "b"), out.map { it.id })
    }

    @Test fun namesWithLeadingNumbersFlip() {
        val eps = (1..3).map { Episode("$it", "$it. Chapter $it", null, null) }
        assertEquals(listOf("3", "2", "1"), normalizeEpisodes(eps).map { it.id })
    }
}

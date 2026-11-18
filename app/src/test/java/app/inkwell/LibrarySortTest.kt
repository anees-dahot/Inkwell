package app.inkwell

import app.inkwell.data.LibrarySort
import app.inkwell.data.db.LibraryItem
import app.inkwell.data.sortLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySortTest {
    private fun li(id: String, title: String, added: Long, last: Long? = null) =
        LibraryItem("s", id, title, null, "MANGA", added, lastChapterDate = last)

    private val items = listOf(li("a", "Beta", 1, 50), li("b", "alpha", 3, 10), li("c", "Gamma", 2, 90))

    @Test fun titleAscIsCaseInsensitive() =
        assertEquals(listOf("b", "a", "c"), sortLibrary(items, LibrarySort.TITLE, true, emptyMap(), emptyMap()).map { it.entryId })

    @Test fun dateAddedDescNewestFirst() =
        assertEquals(listOf("b", "c", "a"), sortLibrary(items, LibrarySort.DATE_ADDED, false, emptyMap(), emptyMap()).map { it.entryId })

    @Test fun unreadAndLatest() {
        assertEquals("c", sortLibrary(items, LibrarySort.UNREAD, false, mapOf("s/c" to 5, "s/a" to 1), emptyMap()).first().entryId)
        assertEquals("c", sortLibrary(items, LibrarySort.LATEST_CHAPTER, false, emptyMap(), emptyMap()).first().entryId)
        assertEquals("a", sortLibrary(items, LibrarySort.LAST_READ, false, emptyMap(), mapOf("s/a" to 99L)).first().entryId)
    }
}

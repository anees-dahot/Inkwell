package app.inkwell.data

import android.content.Context
import app.inkwell.data.db.LibraryItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class LibrarySort(val label: String) {
    TITLE("Title"), LAST_READ("Last read"), LATEST_CHAPTER("Latest chapter"), UNREAD("Unread count"), DATE_ADDED("Date added")
}
enum class LibraryDisplay { GRID, COMPACT, LIST }

/** Library view preferences + pinned global-search sources (kept out of Settings). */
class LibraryPrefs(context: Context) {
    private val sp = context.getSharedPreferences("inkwell_library", Context.MODE_PRIVATE)

    private val _sort = MutableStateFlow(runCatching { LibrarySort.valueOf(sp.getString("sort", "")!!) }.getOrDefault(LibrarySort.DATE_ADDED))
    val sort: StateFlow<LibrarySort> = _sort
    fun setSort(v: LibrarySort) { _sort.value = v; sp.edit().putString("sort", v.name).apply() }

    private val _asc = MutableStateFlow(sp.getBoolean("asc", false))
    val ascending: StateFlow<Boolean> = _asc
    fun setAscending(v: Boolean) { _asc.value = v; sp.edit().putBoolean("asc", v).apply() }

    private val _display = MutableStateFlow(runCatching { LibraryDisplay.valueOf(sp.getString("display", "")!!) }.getOrDefault(LibraryDisplay.GRID))
    val display: StateFlow<LibraryDisplay> = _display
    fun setDisplay(v: LibraryDisplay) { _display.value = v; sp.edit().putString("display", v.name).apply() }

    private val _pinned = MutableStateFlow(sp.getStringSet("pinned", emptySet())?.toSet() ?: emptySet())
    val pinned: StateFlow<Set<String>> = _pinned
    fun togglePin(sourceId: String) {
        val n = if (sourceId in _pinned.value) _pinned.value - sourceId else _pinned.value + sourceId
        _pinned.value = n; sp.edit().putStringSet("pinned", n).apply()
    }
}

fun libKey(sourceId: String, entryId: String) = "$sourceId/$entryId"

fun sortLibrary(
    items: List<LibraryItem>, sort: LibrarySort, ascending: Boolean,
    unread: Map<String, Int>, lastRead: Map<String, Long>,
): List<LibraryItem> {
    val cmp: Comparator<LibraryItem> = when (sort) {
        LibrarySort.TITLE -> compareBy { it.title.lowercase() }
        LibrarySort.LAST_READ -> compareBy { lastRead[libKey(it.sourceId, it.entryId)] ?: 0L }
        LibrarySort.LATEST_CHAPTER -> compareBy { it.lastChapterDate ?: 0L }
        LibrarySort.UNREAD -> compareBy { unread[libKey(it.sourceId, it.entryId)] ?: 0 }
        LibrarySort.DATE_ADDED -> compareBy { it.addedAt }
    }
    val sorted = items.sortedWith(cmp.thenBy { it.title.lowercase() })
    return if (ascending) sorted else sorted.reversed()
}

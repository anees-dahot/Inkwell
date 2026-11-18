package app.inkwell

import kotlinx.coroutines.flow.MutableStateFlow

data class EntryTarget(val sourceId: String, val entryId: String, val title: String, val coverUrl: String?)

/** Deep-link requests coming from notifications. */
object AppLaunch {
    /** Main shell tab index to select (1 = Updates). */
    val openTab = MutableStateFlow<Int?>(null)
    val openEntry = MutableStateFlow<EntryTarget?>(null)

    const val EXTRA_TAB = "inkwell.tab"
    const val EXTRA_SOURCE = "inkwell.source"
    const val EXTRA_ENTRY = "inkwell.entry"
    const val EXTRA_TITLE = "inkwell.title"
    const val EXTRA_COVER = "inkwell.cover"

    fun handle(intent: android.content.Intent?) {
        intent ?: return
        if (intent.hasExtra(EXTRA_TAB)) openTab.value = intent.getIntExtra(EXTRA_TAB, 0)
        val s = intent.getStringExtra(EXTRA_SOURCE); val e = intent.getStringExtra(EXTRA_ENTRY)
        if (s != null && e != null) openEntry.value = EntryTarget(s, e, intent.getStringExtra(EXTRA_TITLE) ?: "", intent.getStringExtra(EXTRA_COVER))
        intent.removeExtra(EXTRA_TAB); intent.removeExtra(EXTRA_SOURCE); intent.removeExtra(EXTRA_ENTRY)
    }
}

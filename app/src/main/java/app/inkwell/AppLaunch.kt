package app.inkwell

import kotlinx.coroutines.flow.MutableStateFlow

data class NovelTarget(val sourceId: String, val entryId: String, val episodeId: String)

data class EntryTarget(val sourceId: String, val entryId: String, val title: String, val coverUrl: String?)

data class EpisodeTarget(val sourceId: String, val entryId: String, val episodeId: String)

/** Deep-link requests coming from notifications. */
object AppLaunch {
    /** Main shell tab index to select (1 = Updates). */
    val openTab = MutableStateFlow<Int?>(null)
    val openEntry = MutableStateFlow<EntryTarget?>(null)
    /** Opens the reader/player for a specific chapter (home screen widget). */
    val openEpisode = MutableStateFlow<EpisodeTarget?>(null)
    /** Reopen the novel reader (from the read-aloud notification). */
    val openNovel = MutableStateFlow<NovelTarget?>(null)

    const val EXTRA_TAB = "inkwell.tab"
    const val EXTRA_SOURCE = "inkwell.source"
    const val EXTRA_ENTRY = "inkwell.entry"
    const val EXTRA_TITLE = "inkwell.title"
    const val EXTRA_COVER = "inkwell.cover"
    const val EXTRA_EPISODE = "inkwell.episode"
    const val EXTRA_NOVEL_SOURCE = "inkwell.nv.source"
    const val EXTRA_NOVEL_ENTRY = "inkwell.nv.entry"
    const val EXTRA_NOVEL_EPISODE = "inkwell.nv.episode"

    fun handle(intent: android.content.Intent?) {
        intent ?: return
        if (intent.hasExtra(EXTRA_TAB)) openTab.value = intent.getIntExtra(EXTRA_TAB, 0)
        val s = intent.getStringExtra(EXTRA_SOURCE); val e = intent.getStringExtra(EXTRA_ENTRY)
        val ep = intent.getStringExtra(EXTRA_EPISODE)
        if (s != null && e != null && ep != null) openEpisode.value = EpisodeTarget(s, e, ep)
        else if (s != null && e != null) openEntry.value = EntryTarget(s, e, intent.getStringExtra(EXTRA_TITLE) ?: "", intent.getStringExtra(EXTRA_COVER))
        val ns = intent.getStringExtra(EXTRA_NOVEL_SOURCE); val ne = intent.getStringExtra(EXTRA_NOVEL_ENTRY); val nep = intent.getStringExtra(EXTRA_NOVEL_EPISODE)
        if (ns != null && ne != null && nep != null) openNovel.value = NovelTarget(ns, ne, nep)
        intent.removeExtra(EXTRA_NOVEL_SOURCE); intent.removeExtra(EXTRA_NOVEL_ENTRY); intent.removeExtra(EXTRA_NOVEL_EPISODE)
        intent.removeExtra(EXTRA_TAB); intent.removeExtra(EXTRA_SOURCE); intent.removeExtra(EXTRA_ENTRY); intent.removeExtra(EXTRA_EPISODE)
    }
}

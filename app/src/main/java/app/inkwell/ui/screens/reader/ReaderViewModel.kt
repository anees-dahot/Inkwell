package app.inkwell.ui.screens.reader

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.inkwell.AppContainer
import app.inkwell.data.ReaderDirection
import app.inkwell.data.ReaderMode
import app.inkwell.data.ReaderViewMode
import app.inkwell.data.model.Entry
import app.inkwell.data.model.Episode
import app.inkwell.data.model.Page
import app.inkwell.ui.components.probeAspect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class LoadedChapter(val ep: Episode, val pages: List<Page>)

/** One slot in the continuous reading list: a page, or the transition between two chapters. */
sealed interface RItem {
    val key: String
    class Pg(val ep: Episode, val page: Page, val idx: Int, val total: Int) : RItem {
        override val key = "${ep.id}/${page.index}"
    }
    class Trans(val from: Episode, val to: Episode?, val total: Int) : RItem {
        override val key = "t/${from.id}"
    }
}

class ReaderViewModel(
    private val c: AppContainer,
    private val sourceId: String,
    private val entryId: String,
    private val startEpisodeId: String,
    private val appContext: android.content.Context,
) : ViewModel() {
    var entry by mutableStateOf<Entry?>(null); private set
    var genres by mutableStateOf<List<String>>(emptyList()); private set
    var episodes by mutableStateOf<List<Episode>>(emptyList()); private set
    var chapters by mutableStateOf<List<LoadedChapter>>(emptyList()); private set
    /** Bumped whenever the chapter list is replaced (jump to another chapter) so UI state resets. */
    var generation by mutableIntStateOf(0); private set
    var initialItem by mutableIntStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var nextLoading by mutableStateOf(false); private set
    var nextError by mutableStateOf(false); private set
    var mode by mutableStateOf<ReaderViewMode?>(null); private set

    val items: List<RItem> by derivedStateOf {
        buildList {
            for (ch in chapters) {
                ch.pages.forEachIndexed { i, p -> add(RItem.Pg(ch.ep, p, i, ch.pages.size)) }
                add(RItem.Trans(ch.ep, nextOf(ch.ep), ch.pages.size))
            }
        }
    }

    init { start() }

    fun start() {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val d = c.repo.details(sourceId, entryId)
                entry = d.entry; genres = d.genres
                episodes = c.repo.episodes(sourceId, entryId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load"; loading = false; return@launch }
            open(chapters.firstOrNull()?.ep?.id ?: startEpisodeId)
        }
    }

    fun open(episodeId: String) {
        viewModelScope.launch {
            loading = true; error = null
            try {
                val ep = episodes.firstOrNull { it.id == episodeId } ?: error("Chapter not found")
                val pg = c.repo.pages(sourceId, entryId, episodeId)
                if (pg.isEmpty()) error("This chapter has no pages")
                val prog = c.repo.getProgress(sourceId, entryId, episodeId)
                initialItem = if (prog != null && !prog.read) prog.page.coerceIn(0, pg.lastIndex) else 0
                if (mode == null) mode = resolveMode(pg)
                nextError = false
                chapters = listOf(LoadedChapter(ep, pg))
                generation++
                c.repo.saveProgress(entry!!, ep, initialItem, pg.size, false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not load pages" }
            finally { loading = false }
        }
    }

    private suspend fun resolveMode(pg: List<Page>): ReaderViewMode {
        val r = c.settings.reader
        r.titleMode(sourceId, entryId)?.let { return it }
        r.viewMode.value?.let { return it }
        val s = c.settings
        val rtl = s.readerDirection.value == ReaderDirection.RTL
        val paged = if (rtl) ReaderViewMode.RTL else ReaderViewMode.LTR
        return when (s.readerMode.value) {
            ReaderMode.WEBTOON -> ReaderViewMode.WEBTOON
            ReaderMode.PAGED -> paged
            ReaderMode.AUTO -> {
                val strip = genres.any { it.equals("Long Strip", true) } ||
                    (probeAspect(appContext, pg.first().imageUrl)?.let { it < 1f / 2.5f } == true)
                if (strip) ReaderViewMode.WEBTOON else paged
            }
        }
    }

    /** Persist for this title only. */
    fun setTitleMode(m: ReaderViewMode) { mode = m; c.settings.reader.setTitleMode(sourceId, entryId, m) }
    fun setDefaultMode(m: ReaderViewMode) { mode = m; c.settings.reader.setViewMode(m) }
    fun resetTitleMode() {
        c.settings.reader.setTitleMode(sourceId, entryId, null)
        mode = c.settings.reader.viewMode.value ?: run {
            val rtl = c.settings.readerDirection.value == ReaderDirection.RTL
            when (c.settings.readerMode.value) {
                ReaderMode.WEBTOON -> ReaderViewMode.WEBTOON
                else -> if (rtl) ReaderViewMode.RTL else ReaderViewMode.LTR
            }
        }
    }

    /** Append the chapter after the last loaded one (seamless transition). */
    fun loadNext() {
        if (nextLoading) return
        val last = chapters.lastOrNull() ?: return
        val n = nextOf(last.ep) ?: return
        if (chapters.any { it.ep.id == n.id }) return
        nextLoading = true; nextError = false
        viewModelScope.launch {
            try {
                val pg = c.repo.pages(sourceId, entryId, n.id)
                if (pg.isEmpty()) error("empty")
                chapters = chapters + LoadedChapter(n, pg)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { nextError = true }
            finally { nextLoading = false }
        }
    }

    fun report(first: RItem?, last: RItem?) {
        val en = entry ?: return
        val f = first ?: return
        val (ep, page, total) = when (f) {
            is RItem.Pg -> Triple(f.ep, f.idx, f.total)
            is RItem.Trans -> Triple(f.from, (f.total - 1).coerceAtLeast(0), f.total)
        }
        val read = f is RItem.Trans || page >= total - 1 ||
            (last is RItem.Pg && last.ep.id == ep.id && last.idx >= total - 1) ||
            (last is RItem.Trans && last.from.id == ep.id)
        val earlier = chapters.takeWhile { it.ep.id != ep.id }.filter { markedRead.add(it.ep.id) }
        viewModelScope.launch {
            // chapters scrolled past between two debounced reports still count as read
            for (ch in earlier) c.repo.saveProgress(en, ch.ep, ch.pages.lastIndex, ch.pages.size, true)
            c.repo.saveProgress(en, ep, page, total, read)
        }
    }
    private val markedRead = mutableSetOf<String>()

    /** Index in [episodes] (newest first). */
    fun nextOf(ep: Episode): Episode? = episodes.indexOfFirst { it.id == ep.id }.takeIf { it > 0 }?.let { episodes[it - 1] }
    fun prevOf(ep: Episode): Episode? =
        episodes.indexOfFirst { it.id == ep.id }.takeIf { it >= 0 && it < episodes.lastIndex }?.let { episodes[it + 1] }
}

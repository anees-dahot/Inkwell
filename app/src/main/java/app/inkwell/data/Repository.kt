package app.inkwell.data

import app.inkwell.data.db.*
import app.inkwell.data.model.*
import app.inkwell.data.source.SourceRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class Repository(val registry: SourceRegistry, private val db: InkDatabase, val settings: Settings) {
    private val detailsCache = HashMap<String, EntryDetails>()
    private val episodeCache = HashMap<String, List<Episode>>()

    private fun key(s: String, e: String) = "$s/$e"

    suspend fun details(sourceId: String, entryId: String): EntryDetails =
        detailsCache[key(sourceId, entryId)] ?: registry.get(sourceId).details(entryId)
            .also { detailsCache[key(sourceId, entryId)] = it }

    suspend fun episodes(sourceId: String, entryId: String, force: Boolean = false): List<Episode> {
        if (!force) episodeCache[key(sourceId, entryId)]?.let { return it }
        val eps = registry.get(sourceId).episodes(entryId)
        episodeCache[key(sourceId, entryId)] = eps
        return eps
    }

    // library
    fun library(): Flow<List<LibraryItem>> = db.library().observeAll()
    fun unread(): Flow<List<UnreadRow>> = db.library().observeUnread()
    fun isInLibrary(s: String, e: String) = db.library().observeIn(s, e)
    suspend fun addToLibrary(entry: Entry) {
        db.library().insert(LibraryItem(entry.sourceId, entry.id, entry.title, entry.coverUrl, entry.type.name, System.currentTimeMillis()))
        runCatching { cacheEpisodes(entry.sourceId, entry.id, episodes(entry.sourceId, entry.id)) }
    }
    suspend fun removeFromLibrary(s: String, e: String) = db.library().delete(s, e)

    suspend fun cacheEpisodes(s: String, e: String, eps: List<Episode>) =
        db.episodeCache().replace(s, e, eps.map { CachedEpisode(s, e, it.id, it.name, it.number, it.dateMillis) })

    suspend fun isLibraryEntry(s: String, e: String) = db.library().all().any { it.sourceId == s && it.entryId == e }

    // updates
    fun updates(): Flow<List<UpdateRow>> = db.episodeCache().observeUpdates()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    suspend fun refreshUpdates() {
        if (_refreshing.value) return
        _refreshing.value = true
        try {
            for (item in db.library().all()) {
                runCatching {
                    val eps = episodes(item.sourceId, item.entryId, force = true)
                    cacheEpisodes(item.sourceId, item.entryId, eps)
                }
            }
        } finally { _refreshing.value = false }
    }

    // progress / history
    fun progress(s: String, e: String) = db.progress().observeEntry(s, e)
    fun historyFor(s: String, e: String) = db.history().observeEntry(s, e)
    suspend fun getProgress(s: String, e: String, ep: String) = db.progress().get(s, e, ep)

    suspend fun saveProgress(entry: Entry, ep: Episode, page: Int, total: Int, read: Boolean) {
        val prev = db.progress().get(entry.sourceId, entry.id, ep.id)
        db.progress().upsert(ProgressItem(entry.sourceId, entry.id, ep.id, page, total, read || prev?.read == true, System.currentTimeMillis()))
        db.history().upsert(HistoryItem(entry.sourceId, entry.id, entry.title, entry.coverUrl, entry.type.name, ep.id, ep.name, System.currentTimeMillis()))
    }

    fun history(): Flow<List<HistoryItem>> = db.history().observeAll()
    suspend fun deleteHistory(s: String, e: String) = db.history().delete(s, e)
    suspend fun restoreHistory(h: HistoryItem) = db.history().upsert(h)
}

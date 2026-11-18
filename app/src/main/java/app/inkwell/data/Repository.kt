package app.inkwell.data

import app.inkwell.data.db.*
import app.inkwell.data.model.*
import app.inkwell.data.source.SourceRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NewChapters(val item: LibraryItem, val episodes: List<Episode>)

class Repository(val registry: SourceRegistry, private val db: InkDatabase, val settings: Settings) {
    private val detailsCache = ConcurrentHashMap<String, EntryDetails>()
    private val episodeCache = ConcurrentHashMap<String, List<Episode>>()

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

    /** Replaces the cache + release estimate; returns episodes not seen before (empty on first cache). */
    suspend fun cacheEpisodes(s: String, e: String, eps: List<Episode>): List<Episode> {
        val prev = db.episodeCache().ids(s, e).toSet()
        db.episodeCache().replace(s, e, eps.map { CachedEpisode(s, e, it.id, it.name, it.number, it.dateMillis) })
        val est = ReleaseEstimator.estimate(eps.map { it.number to it.dateMillis }, System.currentTimeMillis())
        db.library().updateStats(s, e, est.lastDate, est.lastNumber, est.expectedNext)
        return if (prev.isEmpty()) emptyList() else eps.filter { it.id !in prev }
    }

    suspend fun isLibraryEntry(s: String, e: String) = db.library().all().any { it.sourceId == s && it.entryId == e }

    // updates
    fun updates(): Flow<List<UpdateRow>> = db.episodeCache().observeUpdates()
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    suspend fun refreshUpdates() { refreshLibrary() }

    /** Refreshes every library entry (3 at a time, per-entry errors isolated); returns entries with new chapters. */
    suspend fun refreshLibrary(): List<NewChapters> {
        if (_refreshing.value) return emptyList()
        _refreshing.value = true
        try {
            val sem = Semaphore(3)
            return coroutineScope {
                db.library().all().map { item ->
                    async {
                        sem.withPermit {
                            try {
                                val eps = episodes(item.sourceId, item.entryId, force = true)
                                val fresh = cacheEpisodes(item.sourceId, item.entryId, eps)
                                if (fresh.isEmpty()) null else NewChapters(item, fresh)
                            } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } finally { _refreshing.value = false }
    }

    // reminders
    fun observeRemind(s: String, e: String) = db.library().observeRemind(s, e)
    suspend fun setRemind(s: String, e: String, on: Boolean) = db.library().setRemind(s, e, on)
    fun reminders(): Flow<List<CustomReminder>> = db.reminders().observeAll()
    suspend fun allReminders() = db.reminders().all()
    suspend fun getReminder(id: String) = db.reminders().get(id)
    suspend fun saveReminder(r: CustomReminder) = db.reminders().upsert(r)
    suspend fun deleteReminder(id: String) = db.reminders().delete(id)
    suspend fun remindItems() = db.library().all().filter { it.remind }
    suspend fun libraryItem(s: String, e: String) = db.library().get(s, e)

    suspend fun markAllRead(s: String, e: String, eps: List<Episode>) {
        val now = System.currentTimeMillis()
        for (ep in eps) {
            val prev = db.progress().get(s, e, ep.id)
            if (prev?.read == true) continue
            db.progress().upsert(ProgressItem(s, e, ep.id, prev?.page ?: 0, prev?.totalPages ?: 0, true, now))
        }
    }

    // backup
    suspend fun exportBackup(): String = BackupCodec.encode(
        Backup(
            createdAt = System.currentTimeMillis(),
            library = db.library().all(), progress = db.progress().all(),
            history = db.history().all(), reminders = db.reminders().all(),
        )
    )

    /** Merges a backup into the DB; existing data wins unless the backup is newer. */
    suspend fun importBackup(text: String): BackupStats {
        val b = BackupCodec.decode(text)
        var lib = 0; var prog = 0; var hist = 0
        for (l in b.library) if (db.library().get(l.sourceId, l.entryId) == null) { db.library().insertIgnore(l); lib++ }
        for (p in b.progress) {
            val cur = db.progress().get(p.sourceId, p.entryId, p.episodeId)
            if (cur == null || p.updatedAt > cur.updatedAt || (p.read && !cur.read)) {
                db.progress().upsert(p.copy(read = p.read || cur?.read == true)); prog++
            }
        }
        for (h in b.history) {
            val cur = db.history().get(h.sourceId, h.entryId)
            if (cur == null || h.timestamp > cur.timestamp) { db.history().upsert(h); hist++ }
        }
        for (r in b.reminders) db.reminders().upsert(r)
        return BackupStats(lib, prog, hist, b.reminders.size)
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

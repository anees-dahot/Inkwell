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
import android.content.Context
import okhttp3.OkHttpClient
import java.io.File

data class NewChapters(val item: LibraryItem, val episodes: List<Episode>)

/**
 * Canonical chapter order for the whole app: newest first. Sources disagree (ao3 is oldest-first),
 * so numbers decide when present, else the date trend; otherwise the source order is kept.
 */
fun normalizeEpisodes(eps: List<Episode>): List<Episode> {
    if (eps.size < 2) return eps
    val nums = eps.map { it.number ?: nameNumber(it.name) }
    if (nums.all { it != null } && nums.toSet().size > 1) {
        return eps.withIndex().sortedByDescending { nums[it.index] }.map { it.value }
    }
    val a = nums.firstOrNull { it != null }
    val b = nums.lastOrNull { it != null }
    if (a != null && b != null && a != b) return if (a < b) eps.asReversed() else eps
    val da = eps.firstNotNullOfOrNull { it.dateMillis }
    val db = eps.lastNotNullOfOrNull { it.dateMillis }
    if (da != null && db != null && da != db) return if (da < db) eps.asReversed() else eps
    return eps
}

private val NAME_NUM = Regex("^\\s*(?:chapter|ch\\.?|episode|ep\\.?)?\\s*(\\d+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE)
private fun nameNumber(name: String): Float? = NAME_NUM.find(name)?.groupValues?.get(1)?.toFloatOrNull()

private inline fun <T, R : Any> List<T>.lastNotNullOfOrNull(f: (T) -> R?): R? {
    for (i in indices.reversed()) f(this[i])?.let { return it }
    return null
}

data class MigrateResult(val mappedRead: Int, val totalChapters: Int)

class Repository(
    val registry: SourceRegistry, private val db: InkDatabase, val settings: Settings,
    private val appContext: Context, http: OkHttpClient, images: OkHttpClient = http,
) {
    val prefs = LibraryPrefs(appContext)
    val store = DownloadStore(File(appContext.filesDir, "downloads")).also { runCatching { it.cleanTemp() } }
    val downloader = ChapterDownloader(registry, images, store)
    private val detailsCache = ConcurrentHashMap<String, EntryDetails>()
    private val episodeCache = ConcurrentHashMap<String, List<Episode>>()

    private fun key(s: String, e: String) = "$s/$e"

    /** Chapter text for the novel reader. Single entry point so downloads can serve it offline. */
    suspend fun chapterText(sourceId: String, entryId: String, episodeId: String): List<String> =
        store.readText(sourceId, entryId, episodeId)?.takeIf { it.isNotEmpty() }
            ?: (registry.get(sourceId) as NovelSource).chapterText(episodeId)

    /** Manga pages for the reader. Single entry point so downloads can serve them offline. */
    suspend fun pages(sourceId: String, entryId: String, episodeId: String): List<Page> {
        val files = store.pageFiles(sourceId, entryId, episodeId)
        if (files.isNotEmpty()) return files.mapIndexed { i, f -> Page(i, "file://" + f.absolutePath) }
        // some sites list the same image twice (lazy-load + noscript copies, repeated ad slots)
        return (registry.get(sourceId) as MangaSource).pages(episodeId).cleanPages()
    }

    suspend fun details(sourceId: String, entryId: String): EntryDetails {
        detailsCache[key(sourceId, entryId)]?.let { return it }
        return try {
            registry.get(sourceId).details(entryId).also { detailsCache[key(sourceId, entryId)] = it }
        } catch (c: CancellationException) { throw c } catch (x: Exception) {
            // offline: fall back to what we stored locally
            val src = registry.get(sourceId)
            val lib = db.library().get(sourceId, entryId)
            val dl = db.downloads().forEntry(sourceId, entryId).firstOrNull()
            val title = lib?.title ?: dl?.entryTitle ?: throw x
            EntryDetails(Entry(sourceId, entryId, title, lib?.coverUrl ?: dl?.coverUrl, src.type), null, emptyList(), emptyList(), null)
        }
    }

    suspend fun episodes(sourceId: String, entryId: String, force: Boolean = false): List<Episode> {
        if (!force) episodeCache[key(sourceId, entryId)]?.let { return it }
        val eps = try {
            normalizeEpisodes(registry.get(sourceId).episodes(entryId))
        } catch (c: CancellationException) { throw c } catch (x: Exception) {
            offlineEpisodes(sourceId, entryId).takeIf { it.isNotEmpty() } ?: throw x
        }
        episodeCache[key(sourceId, entryId)] = eps
        return eps
    }

    /** Cached (library) or downloaded chapters, newest first when numbered. */
    private suspend fun offlineEpisodes(s: String, e: String): List<Episode> {
        val cached = db.episodeCache().forEntry(s, e).map { Episode(it.episodeId, it.name, it.number, it.dateMillis) }
        val list = cached.ifEmpty { db.downloads().forEntry(s, e).filter { it.status == DlStatus.DONE }.map { Episode(it.episodeId, it.episodeName, it.number, null) } }
        return if (list.all { it.number != null }) list.sortedByDescending { it.number } else list
    }

    // library
    fun library(): Flow<List<LibraryItem>> = db.library().observeAll()
    fun unread(): Flow<List<UnreadRow>> = db.library().observeUnread()
    fun isInLibrary(s: String, e: String) = db.library().observeIn(s, e)
    suspend fun addToLibrary(entry: Entry, categoryId: Long = 0) {
        db.library().insert(LibraryItem(entry.sourceId, entry.id, entry.title, entry.coverUrl, entry.type.name, System.currentTimeMillis(), categoryId = categoryId))
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

    suspend fun markAllRead(s: String, e: String, eps: List<Episode>) = markRead(s, e, eps, true)

    suspend fun markRead(s: String, e: String, eps: List<Episode>, read: Boolean) {
        val now = System.currentTimeMillis()
        for (ep in eps) {
            val prev = db.progress().get(s, e, ep.id)
            if ((prev?.read == true) == read && (prev != null || !read)) continue
            db.progress().upsert(ProgressItem(s, e, ep.id, if (read) prev?.page ?: 0 else 0, prev?.totalPages ?: 0, read, now))
        }
    }

    /**
     * Moves a library entry to another source: category, bell, added date, read progress (by chapter number
     * or name) and history carry over. [keepOld] leaves the old library entry in place. Throws before any
     * change if the target's chapters cannot be loaded.
     */
    suspend fun migrate(oldS: String, oldE: String, to: Entry, keepOld: Boolean): MigrateResult {
        val old = db.library().get(oldS, oldE)
        val newEps = episodes(to.sourceId, to.id, force = true)
        // old chapter id -> (number, name): cache first, then downloads, then (maybe dead) source
        val oldInfo = HashMap<String, Pair<Float?, String>>()
        db.downloads().forEntry(oldS, oldE).forEach { oldInfo[it.episodeId] = it.number to it.episodeName }
        val oldCached = db.episodeCache().forEntry(oldS, oldE)
        if (oldCached.isEmpty()) runCatching { episodes(oldS, oldE) }.getOrNull()?.forEach { oldInfo[it.id] = it.number to it.name }
        oldCached.forEach { oldInfo[it.episodeId] = it.number to it.name }
        fun match(id: String): Episode? {
            val (n, name) = oldInfo[id] ?: return null
            if (n != null) newEps.firstOrNull { it.number != null && kotlin.math.abs(it.number - n) < 0.001f }?.let { return it }
            return newEps.firstOrNull { it.name.equals(name, true) }
        }
        if (db.library().get(to.sourceId, to.id) == null) {
            db.library().insert(LibraryItem(
                to.sourceId, to.id, to.title, to.coverUrl, to.type.name, old?.addedAt ?: System.currentTimeMillis(),
                remind = old?.remind ?: false, categoryId = old?.categoryId ?: 0,
            ))
        }
        cacheEpisodes(to.sourceId, to.id, newEps)
        var mapped = 0
        for (p in db.progress().all().filter { it.sourceId == oldS && it.entryId == oldE }) {
            val t = match(p.episodeId) ?: continue
            val cur = db.progress().get(to.sourceId, to.id, t.id)
            if (cur != null && (cur.read || cur.updatedAt >= p.updatedAt)) continue
            db.progress().upsert(p.copy(sourceId = to.sourceId, entryId = to.id, episodeId = t.id))
            if (p.read) mapped++
        }
        db.history().get(oldS, oldE)?.let { h ->
            match(h.episodeId)?.let { t ->
                if (db.history().get(to.sourceId, to.id) == null)
                    db.history().upsert(HistoryItem(to.sourceId, to.id, to.title, to.coverUrl, to.type.name, t.id, t.name, h.timestamp))
            }
        }
        if (!keepOld) {
            db.reminders().all().filter { it.sourceId == oldS && it.entryId == oldE }.forEach {
                db.reminders().upsert(it.copy(sourceId = to.sourceId, entryId = to.id, title = to.title, coverUrl = to.coverUrl))
            }
            db.library().delete(oldS, oldE)
            db.episodeCache().clear(oldS, oldE)
            db.history().delete(oldS, oldE)
        }
        runCatching { app.inkwell.work.Scheduler.syncExpected(appContext) }
        return MigrateResult(mapped, newEps.size)
    }

    // library: categories + bulk ops
    fun categories(): Flow<List<LibraryCategory>> = db.categories().observeAll()
    suspend fun categoryList() = db.categories().all()
    suspend fun addCategory(name: String): Long {
        val next = (db.categories().all().maxOfOrNull { it.sortOrder } ?: -1) + 1
        return db.categories().insert(LibraryCategory(name = name.trim(), sortOrder = next))
    }
    suspend fun renameCategory(c: LibraryCategory, name: String) = db.categories().update(c.copy(name = name.trim()))
    suspend fun deleteCategory(id: Long) { db.library().clearCategory(id); db.categories().delete(id) }
    suspend fun moveCategory(id: Long, delta: Int) {
        val list = db.categories().all().toMutableList()
        val i = list.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        list.forEachIndexed { idx, c -> if (c.sortOrder != idx) db.categories().update(c.copy(sortOrder = idx)) }
    }
    suspend fun setCategory(keys: Collection<Pair<String, String>>, categoryId: Long) {
        for ((s, e) in keys) db.library().setCategory(s, e, categoryId)
    }
    suspend fun removeFromLibrary(keys: Collection<Pair<String, String>>) { for ((s, e) in keys) db.library().delete(s, e) }

    /** Cached chapters of a library entry in reading order (oldest first). */
    private suspend fun cachedReading(s: String, e: String): List<Episode> {
        val eps = db.episodeCache().forEntry(s, e).map { Episode(it.episodeId, it.name, it.number, it.dateMillis) }
        return if (eps.all { it.number != null }) eps.sortedBy { it.number } else eps.reversed()
    }
    suspend fun markLibraryRead(keys: Collection<Pair<String, String>>, read: Boolean) {
        for ((s, e) in keys) markRead(s, e, cachedReading(s, e), read)
    }
    /** Queues every unread cached chapter of the given library entries; returns the number queued. */
    suspend fun downloadUnread(keys: Collection<Pair<String, String>>): Int {
        var n = 0
        for ((s, e) in keys) {
            val item = db.library().get(s, e) ?: continue
            if (item.type == ContentType.VIDEO.name) continue
            val read = db.progress().all().filter { it.sourceId == s && it.entryId == e && it.read }.map { it.episodeId }.toSet()
            val eps = cachedReading(s, e).filter { it.id !in read }
            n += enqueueDownloads(Entry(s, e, item.title, item.coverUrl, ContentType.valueOf(item.type)), eps)
        }
        return n
    }

    // downloads
    fun downloads(): Flow<List<DownloadItem>> = db.downloads().observeAll()
    fun downloadsFor(s: String, e: String): Flow<List<DownloadItem>> = db.downloads().observeEntry(s, e)

    /** Queues chapters (pass in reading order); skips already-downloaded ones. Returns how many were queued. */
    suspend fun enqueueDownloads(entry: Entry, eps: List<Episode>): Int {
        if (entry.type == ContentType.VIDEO) return 0
        val todo = eps.filter { !store.has(entry.sourceId, entry.id, it.id) }
        if (todo.isEmpty()) return 0
        val now = System.currentTimeMillis()
        db.downloads().insertIgnore(todo.mapIndexed { i, ep ->
            DownloadItem(entry.sourceId, entry.id, ep.id, entry.title, entry.coverUrl, entry.type.name, ep.name, ep.number, DlStatus.QUEUED, 0, 0, 0, null, now + i)
        })
        for (ep in todo) db.downloads().requeue(entry.sourceId, entry.id, ep.id)
        app.inkwell.work.Downloads.start(appContext)
        return todo.size
    }
    suspend fun claimNextDownload(): DownloadItem? {
        while (true) {
            val n = db.downloads().nextQueued() ?: return null
            if (db.downloads().claim(n.sourceId, n.entryId, n.episodeId) > 0) return n
        }
    }
    suspend fun downloadProgress(i: DownloadItem, done: Int, total: Int) = db.downloads().setProgress(i.sourceId, i.entryId, i.episodeId, done, total)
    suspend fun downloadDone(i: DownloadItem, bytes: Long) = db.downloads().setDone(i.sourceId, i.entryId, i.episodeId, bytes)
    suspend fun downloadFailed(i: DownloadItem, msg: String?) = db.downloads().setError(i.sourceId, i.entryId, i.episodeId, msg)
    suspend fun resetStuckDownloads() = db.downloads().resetStuck()
    suspend fun hasQueuedDownloads() = db.downloads().queuedCount() > 0
    suspend fun pendingDownloads() = db.downloads().pendingCount()
    suspend fun pauseDownloads() { db.downloads().pauseAll(); app.inkwell.work.Downloads.stop(appContext) }
    suspend fun resumeDownloads() { db.downloads().resumeAll(); app.inkwell.work.Downloads.start(appContext) }
    suspend fun cancelDownload(i: DownloadItem) {
        db.downloads().delete(i.sourceId, i.entryId, i.episodeId)
        app.inkwell.work.Downloads.cancelActive(i)
        store.delete(i.sourceId, i.entryId, i.episodeId)
    }
    suspend fun clearDownloadQueue() {
        app.inkwell.work.Downloads.stop(appContext)
        db.downloads().clearQueue()
        store.cleanTemp(0)
    }
    suspend fun deleteDownloads(items: Collection<DownloadItem>) { for (i in items) cancelDownload(i) }
    suspend fun deleteEntryDownloads(s: String, e: String) {
        db.downloads().forEntry(s, e).forEach { app.inkwell.work.Downloads.cancelActive(it) }
        db.downloads().deleteEntry(s, e); store.deleteEntry(s, e)
    }

    /** Auto-download hook for the library update worker. */
    suspend fun autoDownload(fresh: List<NewChapters>) {
        for (n in fresh) {
            if (n.item.type == ContentType.VIDEO.name) continue
            val entry = Entry(n.item.sourceId, n.item.entryId, n.item.title, n.item.coverUrl, ContentType.valueOf(n.item.type))
            enqueueDownloads(entry, n.episodes.sortedBy { it.number ?: 0f })
        }
    }

    // backup
    suspend fun exportBackup(): String = BackupCodec.encode(
        Backup(
            createdAt = System.currentTimeMillis(),
            library = db.library().all(), progress = db.progress().all(),
            history = db.history().all(), reminders = db.reminders().all(),
            categories = db.categories().all().map { BackupCategory(it.id, it.name, it.sortOrder) },
        )
    )

    /** Merges a backup into the DB; existing data wins unless the backup is newer. */
    suspend fun importBackup(text: String): BackupStats {
        val b = BackupCodec.decode(text)
        var lib = 0; var prog = 0; var hist = 0
        // categories are matched by name; backup ids are remapped to local ids
        val local = db.categories().all().toMutableList()
        val idMap = HashMap<Long, Long>()
        for (bc in b.categories.sortedBy { it.sortOrder }) {
            val found = local.firstOrNull { it.name.equals(bc.name, true) }
            idMap[bc.id] = found?.id ?: db.categories().insert(LibraryCategory(name = bc.name, sortOrder = local.size)).also {
                local.add(LibraryCategory(it, bc.name, local.size))
            }
        }
        for (l in b.library) if (db.library().get(l.sourceId, l.entryId) == null) {
            db.library().insertIgnore(l.copy(categoryId = idMap[l.categoryId] ?: 0)); lib++
        }
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
        return BackupStats(lib, prog, hist, b.reminders.size, b.categories.size)
    }

    // progress / history
    fun progress(s: String, e: String) = db.progress().observeEntry(s, e)
    fun historyFor(s: String, e: String) = db.history().observeEntry(s, e)
    suspend fun getProgress(s: String, e: String, ep: String) = db.progress().get(s, e, ep)

    suspend fun saveProgress(entry: Entry, ep: Episode, page: Int, total: Int, read: Boolean) {
        if (settings.incognito.value) return
        val prev = db.progress().get(entry.sourceId, entry.id, ep.id)
        db.progress().upsert(ProgressItem(entry.sourceId, entry.id, ep.id, page, total, read || prev?.read == true, System.currentTimeMillis()))
        db.history().upsert(HistoryItem(entry.sourceId, entry.id, entry.title, entry.coverUrl, entry.type.name, ep.id, ep.name, System.currentTimeMillis()))
    }

    fun history(): Flow<List<HistoryItem>> = db.history().observeAll()
    suspend fun deleteHistory(s: String, e: String) = db.history().delete(s, e)
    suspend fun clearHistory() = db.history().clear()
    suspend fun restoreHistory(h: HistoryItem) = db.history().upsert(h)
}

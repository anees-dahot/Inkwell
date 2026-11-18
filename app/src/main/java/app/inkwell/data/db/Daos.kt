package app.inkwell.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LibraryDao {
    @Query("SELECT * FROM LibraryItem ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<LibraryItem>>

    @Query("SELECT * FROM LibraryItem")
    suspend fun all(): List<LibraryItem>

    @Query("SELECT EXISTS(SELECT 1 FROM LibraryItem WHERE sourceId=:s AND entryId=:e)")
    fun observeIn(s: String, e: String): Flow<Boolean>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: LibraryItem)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: LibraryItem)

    @Query("SELECT * FROM LibraryItem WHERE sourceId=:s AND entryId=:e")
    suspend fun get(s: String, e: String): LibraryItem?

    @Query("UPDATE LibraryItem SET lastChapterDate=:last, lastChapterNumber=:num, expectedNext=:next WHERE sourceId=:s AND entryId=:e")
    suspend fun updateStats(s: String, e: String, last: Long?, num: Float?, next: Long?)

    @Query("UPDATE LibraryItem SET remind=:on WHERE sourceId=:s AND entryId=:e")
    suspend fun setRemind(s: String, e: String, on: Boolean)

    @Query("SELECT EXISTS(SELECT 1 FROM LibraryItem WHERE sourceId=:s AND entryId=:e AND remind=1)")
    fun observeRemind(s: String, e: String): Flow<Boolean>

    @Query("UPDATE LibraryItem SET categoryId=:cat WHERE sourceId=:s AND entryId=:e")
    suspend fun setCategory(s: String, e: String, cat: Long)

    @Query("UPDATE LibraryItem SET categoryId=0 WHERE categoryId=:cat")
    suspend fun clearCategory(cat: Long)

    @Query("DELETE FROM LibraryItem WHERE sourceId=:s AND entryId=:e")
    suspend fun delete(s: String, e: String)

    @Query("""SELECT l.sourceId AS sourceId, l.entryId AS entryId,
        (SELECT COUNT(*) FROM CachedEpisode c WHERE c.sourceId=l.sourceId AND c.entryId=l.entryId AND NOT EXISTS
          (SELECT 1 FROM ProgressItem p WHERE p.sourceId=c.sourceId AND p.entryId=c.entryId AND p.episodeId=c.episodeId AND p.read=1)) AS unread
        FROM LibraryItem l""")
    fun observeUnread(): Flow<List<UnreadRow>>
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM ProgressItem WHERE sourceId=:s AND entryId=:e")
    fun observeEntry(s: String, e: String): Flow<List<ProgressItem>>

    @Query("SELECT * FROM ProgressItem WHERE sourceId=:s AND entryId=:e AND episodeId=:ep")
    suspend fun get(s: String, e: String, ep: String): ProgressItem?

    @Query("SELECT * FROM ProgressItem")
    suspend fun all(): List<ProgressItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProgressItem)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM HistoryItem ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<HistoryItem>>

    @Query("SELECT * FROM HistoryItem WHERE sourceId=:s AND entryId=:e")
    fun observeEntry(s: String, e: String): Flow<HistoryItem?>

    @Query("SELECT * FROM HistoryItem ORDER BY timestamp DESC LIMIT :n")
    suspend fun recent(n: Int): List<HistoryItem>

    @Query("SELECT * FROM HistoryItem")
    suspend fun all(): List<HistoryItem>

    @Query("SELECT * FROM HistoryItem WHERE sourceId=:s AND entryId=:e")
    suspend fun get(s: String, e: String): HistoryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: HistoryItem)

    @Query("DELETE FROM HistoryItem WHERE sourceId=:s AND entryId=:e")
    suspend fun delete(s: String, e: String)

    @Query("DELETE FROM HistoryItem")
    suspend fun clear()
}

@Dao
interface EpisodeCacheDao {
    @Query("SELECT episodeId FROM CachedEpisode WHERE sourceId=:s AND entryId=:e")
    suspend fun ids(s: String, e: String): List<String>

    @Query("SELECT * FROM CachedEpisode WHERE sourceId=:s AND entryId=:e")
    suspend fun forEntry(s: String, e: String): List<CachedEpisode>

    @Query("DELETE FROM CachedEpisode WHERE sourceId=:s AND entryId=:e")
    suspend fun clear(s: String, e: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedEpisode>)

    @Transaction
    suspend fun replace(s: String, e: String, items: List<CachedEpisode>) {
        clear(s, e); insertAll(items)
    }

    @Query("""SELECT c.sourceId AS sourceId, c.entryId AS entryId, c.episodeId AS episodeId, c.name AS name,
        c.dateMillis AS dateMillis, l.title AS entryTitle, l.coverUrl AS coverUrl, l.type AS type,
        EXISTS(SELECT 1 FROM ProgressItem p WHERE p.sourceId=c.sourceId AND p.entryId=c.entryId AND p.episodeId=c.episodeId AND p.read=1) AS isRead
        FROM CachedEpisode c INNER JOIN LibraryItem l ON l.sourceId=c.sourceId AND l.entryId=c.entryId
        ORDER BY c.dateMillis DESC LIMIT 200""")
    fun observeUpdates(): Flow<List<UpdateRow>>
}

@Dao
interface CustomChannelDao {
    @Query("SELECT * FROM CustomChannel")
    suspend fun all(): List<CustomChannel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: CustomChannel)

    @Query("DELETE FROM CustomChannel WHERE id=:id")
    suspend fun delete(id: String)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM CustomReminder ORDER BY weekday, hour, minute")
    fun observeAll(): Flow<List<CustomReminder>>

    @Query("SELECT * FROM CustomReminder")
    suspend fun all(): List<CustomReminder>

    @Query("SELECT * FROM CustomReminder WHERE id=:id")
    suspend fun get(id: String): CustomReminder?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(r: CustomReminder)

    @Query("DELETE FROM CustomReminder WHERE id=:id")
    suspend fun delete(id: String)
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM LibraryCategory ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<LibraryCategory>>

    @Query("SELECT * FROM LibraryCategory ORDER BY sortOrder, id")
    suspend fun all(): List<LibraryCategory>

    @Insert
    suspend fun insert(c: LibraryCategory): Long

    @Update
    suspend fun update(c: LibraryCategory)

    @Query("DELETE FROM LibraryCategory WHERE id=:id")
    suspend fun delete(id: Long)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM DownloadItem ORDER BY createdAt")
    fun observeAll(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM DownloadItem WHERE sourceId=:s AND entryId=:e")
    fun observeEntry(s: String, e: String): Flow<List<DownloadItem>>

    @Query("SELECT * FROM DownloadItem WHERE sourceId=:s AND entryId=:e AND episodeId=:ep")
    suspend fun get(s: String, e: String, ep: String): DownloadItem?

    @Query("SELECT * FROM DownloadItem WHERE status='DONE'")
    suspend fun done(): List<DownloadItem>

    @Query("SELECT * FROM DownloadItem WHERE sourceId=:s AND entryId=:e")
    suspend fun forEntry(s: String, e: String): List<DownloadItem>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<DownloadItem>)

    @Query("UPDATE DownloadItem SET status='QUEUED', progress=0, error=NULL WHERE sourceId=:s AND entryId=:e AND episodeId=:ep AND status IN ('ERROR','PAUSED')")
    suspend fun requeue(s: String, e: String, ep: String)

    @Query("SELECT * FROM DownloadItem WHERE status='QUEUED' ORDER BY createdAt LIMIT 1")
    suspend fun nextQueued(): DownloadItem?

    @Query("UPDATE DownloadItem SET status='DOWNLOADING', progress=0, error=NULL WHERE sourceId=:s AND entryId=:e AND episodeId=:ep AND status='QUEUED'")
    suspend fun claim(s: String, e: String, ep: String): Int

    @Query("UPDATE DownloadItem SET progress=:p, total=:t WHERE sourceId=:s AND entryId=:e AND episodeId=:ep AND status='DOWNLOADING'")
    suspend fun setProgress(s: String, e: String, ep: String, p: Int, t: Int)

    @Query("UPDATE DownloadItem SET status='DONE', progress=total, bytes=:bytes, error=NULL WHERE sourceId=:s AND entryId=:e AND episodeId=:ep")
    suspend fun setDone(s: String, e: String, ep: String, bytes: Long)

    @Query("UPDATE DownloadItem SET status='ERROR', error=:err WHERE sourceId=:s AND entryId=:e AND episodeId=:ep AND status='DOWNLOADING'")
    suspend fun setError(s: String, e: String, ep: String, err: String?)

    @Query("UPDATE DownloadItem SET status='QUEUED' WHERE status='DOWNLOADING'")
    suspend fun resetStuck()

    @Query("UPDATE DownloadItem SET status='PAUSED' WHERE status IN ('QUEUED','DOWNLOADING')")
    suspend fun pauseAll()

    @Query("UPDATE DownloadItem SET status='QUEUED', progress=0, error=NULL WHERE status IN ('PAUSED','ERROR')")
    suspend fun resumeAll()

    @Query("SELECT COUNT(*) FROM DownloadItem WHERE status IN ('QUEUED','DOWNLOADING')")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM DownloadItem WHERE status='QUEUED'")
    suspend fun queuedCount(): Int

    @Query("DELETE FROM DownloadItem WHERE sourceId=:s AND entryId=:e AND episodeId=:ep")
    suspend fun delete(s: String, e: String, ep: String)

    @Query("DELETE FROM DownloadItem WHERE sourceId=:s AND entryId=:e")
    suspend fun deleteEntry(s: String, e: String)

    @Query("DELETE FROM DownloadItem WHERE status!='DONE'")
    suspend fun clearQueue()
}

@Dao
interface StatsDao {
    @Insert
    suspend fun insert(s: ReadingSession)

    @Query("SELECT * FROM ReadingSession ORDER BY startedAt")
    fun observeSessions(): Flow<List<ReadingSession>>

    @Query("SELECT * FROM ProgressItem WHERE read=1")
    fun observeRead(): Flow<List<ProgressItem>>

    @Query("SELECT sourceId, entryId, title FROM LibraryItem")
    fun libraryTitles(): Flow<List<TitleRow>>

    @Query("SELECT sourceId, entryId, entryTitle AS title FROM HistoryItem")
    fun historyTitles(): Flow<List<TitleRow>>

    @Query("DELETE FROM ReadingSession")
    suspend fun clearSessions()
}

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

    @Query("SELECT * FROM HistoryItem")
    suspend fun all(): List<HistoryItem>

    @Query("SELECT * FROM HistoryItem WHERE sourceId=:s AND entryId=:e")
    suspend fun get(s: String, e: String): HistoryItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: HistoryItem)

    @Query("DELETE FROM HistoryItem WHERE sourceId=:s AND entryId=:e")
    suspend fun delete(s: String, e: String)
}

@Dao
interface EpisodeCacheDao {
    @Query("SELECT episodeId FROM CachedEpisode WHERE sourceId=:s AND entryId=:e")
    suspend fun ids(s: String, e: String): List<String>

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

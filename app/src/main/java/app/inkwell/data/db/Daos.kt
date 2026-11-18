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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProgressItem)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM HistoryItem ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<HistoryItem>>

    @Query("SELECT * FROM HistoryItem WHERE sourceId=:s AND entryId=:e")
    fun observeEntry(s: String, e: String): Flow<HistoryItem?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(h: HistoryItem)

    @Query("DELETE FROM HistoryItem WHERE sourceId=:s AND entryId=:e")
    suspend fun delete(s: String, e: String)
}

@Dao
interface EpisodeCacheDao {
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

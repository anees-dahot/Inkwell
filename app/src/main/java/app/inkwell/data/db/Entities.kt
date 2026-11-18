package app.inkwell.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(primaryKeys = ["sourceId", "entryId"])
data class LibraryItem(
    val sourceId: String,
    val entryId: String,
    val title: String,
    val coverUrl: String?,
    val type: String,
    val addedAt: Long,
)

/** Per chapter/episode progress. */
@Entity(primaryKeys = ["sourceId", "entryId", "episodeId"])
data class ProgressItem(
    val sourceId: String,
    val entryId: String,
    val episodeId: String,
    val page: Int,
    val totalPages: Int,
    val read: Boolean,
    val updatedAt: Long,
)

/** Latest activity per entry (Mihon-style history). */
@Entity(primaryKeys = ["sourceId", "entryId"])
data class HistoryItem(
    val sourceId: String,
    val entryId: String,
    val entryTitle: String,
    val coverUrl: String?,
    val type: String,
    val episodeId: String,
    val episodeName: String,
    val timestamp: Long,
)

/** Episode cache for library entries (drives Updates + unread badges). */
@Entity(primaryKeys = ["sourceId", "entryId", "episodeId"])
data class CachedEpisode(
    val sourceId: String,
    val entryId: String,
    val episodeId: String,
    val name: String,
    val number: Float?,
    val dateMillis: Long?,
)

@Entity
data class CustomChannel(
    @PrimaryKey val id: String,
    val title: String,
    val coverUrl: String?,
)

data class UnreadRow(val sourceId: String, val entryId: String, val unread: Int)

data class UpdateRow(
    val sourceId: String,
    val entryId: String,
    val episodeId: String,
    val name: String,
    val dateMillis: Long?,
    val entryTitle: String,
    val coverUrl: String?,
    val type: String,
    val isRead: Boolean,
)

package app.inkwell.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(primaryKeys = ["sourceId", "entryId"])
data class LibraryItem(
    val sourceId: String,
    val entryId: String,
    val title: String,
    val coverUrl: String?,
    val type: String,
    val addedAt: Long,
    /** Date of the newest known chapter. */
    val lastChapterDate: Long? = null,
    val lastChapterNumber: Float? = null,
    /** Estimated next release (local midnight millis); null = unknown. */
    val expectedNext: Long? = null,
    /** Bell toggle: notify on new chapters + on the expected release day. */
    @ColumnInfo(defaultValue = "0") val remind: Boolean = false,
    /** [LibraryCategory.id]; 0 = uncategorized. */
    @ColumnInfo(defaultValue = "0") val categoryId: Long = 0,
)

/** Per chapter/episode progress. */
@Serializable
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
@Serializable
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

/** User-set weekly reminder. [weekday] uses java.util.Calendar constants (1=Sunday..7=Saturday). */
@Serializable
@Entity
data class CustomReminder(
    @PrimaryKey val id: String,
    val sourceId: String,
    val entryId: String,
    val title: String,
    val coverUrl: String?,
    val weekday: Int,
    val hour: Int,
    val minute: Int,
    val enabled: Boolean,
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

/** User-defined library category. */
@Entity
data class LibraryCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
)

object DlStatus {
    const val QUEUED = "QUEUED"
    const val DOWNLOADING = "DOWNLOADING"
    const val DONE = "DONE"
    const val PAUSED = "PAUSED"
    const val ERROR = "ERROR"
}

/** A chapter in the offline download queue / the downloaded set. */
@Entity(primaryKeys = ["sourceId", "entryId", "episodeId"])
data class DownloadItem(
    val sourceId: String,
    val entryId: String,
    val episodeId: String,
    val entryTitle: String,
    val coverUrl: String?,
    val type: String,
    val episodeName: String,
    val number: Float?,
    val status: String,
    val progress: Int,
    val total: Int,
    val bytes: Long,
    val error: String?,
    val createdAt: Long,
)

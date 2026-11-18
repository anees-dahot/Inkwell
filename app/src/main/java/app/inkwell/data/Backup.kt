package app.inkwell.data

import app.inkwell.data.db.CustomReminder
import app.inkwell.data.db.HistoryItem
import app.inkwell.data.db.LibraryItem
import app.inkwell.data.db.ProgressItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Backup(
    val version: Int = 1,
    val createdAt: Long,
    val library: List<LibraryItem> = emptyList(),
    /** Reserved: the app has no categories yet. */
    val categories: List<String> = emptyList(),
    val progress: List<ProgressItem> = emptyList(),
    val history: List<HistoryItem> = emptyList(),
    val reminders: List<CustomReminder> = emptyList(),
)

data class BackupStats(val library: Int, val progress: Int, val history: Int, val reminders: Int)

object BackupCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    fun encode(b: Backup): String = json.encodeToString(Backup.serializer(), b)
    fun decode(s: String): Backup = json.decodeFromString(Backup.serializer(), s)
}

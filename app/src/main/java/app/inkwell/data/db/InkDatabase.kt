package app.inkwell.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [LibraryItem::class, ProgressItem::class, HistoryItem::class, CachedEpisode::class, CustomChannel::class, CustomReminder::class, LibraryCategory::class, DownloadItem::class],
    version = 3,
    exportSchema = false,
)
abstract class InkDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun progress(): ProgressDao
    abstract fun history(): HistoryDao
    abstract fun episodeCache(): EpisodeCacheDao
    abstract fun customChannels(): CustomChannelDao
    abstract fun reminders(): ReminderDao
    abstract fun categories(): CategoryDao
    abstract fun downloads(): DownloadDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE LibraryItem ADD COLUMN lastChapterDate INTEGER")
                db.execSQL("ALTER TABLE LibraryItem ADD COLUMN lastChapterNumber REAL")
                db.execSQL("ALTER TABLE LibraryItem ADD COLUMN expectedNext INTEGER")
                db.execSQL("ALTER TABLE LibraryItem ADD COLUMN remind INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS CustomReminder (id TEXT NOT NULL, sourceId TEXT NOT NULL, entryId TEXT NOT NULL, " +
                        "title TEXT NOT NULL, coverUrl TEXT, weekday INTEGER NOT NULL, hour INTEGER NOT NULL, minute INTEGER NOT NULL, " +
                        "enabled INTEGER NOT NULL, PRIMARY KEY(id))"
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE LibraryItem ADD COLUMN categoryId INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS LibraryCategory (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, sortOrder INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS DownloadItem (sourceId TEXT NOT NULL, entryId TEXT NOT NULL, episodeId TEXT NOT NULL, " +
                        "entryTitle TEXT NOT NULL, coverUrl TEXT, type TEXT NOT NULL, episodeName TEXT NOT NULL, number REAL, " +
                        "status TEXT NOT NULL, progress INTEGER NOT NULL, total INTEGER NOT NULL, bytes INTEGER NOT NULL, " +
                        "error TEXT, createdAt INTEGER NOT NULL, PRIMARY KEY(sourceId, entryId, episodeId))"
                )
            }
        }

        fun create(context: Context) =
            Room.databaseBuilder(context, InkDatabase::class.java, "inkwell.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}

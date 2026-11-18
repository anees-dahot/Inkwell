package app.inkwell.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LibraryItem::class, ProgressItem::class, HistoryItem::class, CachedEpisode::class, CustomChannel::class],
    version = 1,
    exportSchema = false,
)
abstract class InkDatabase : RoomDatabase() {
    abstract fun library(): LibraryDao
    abstract fun progress(): ProgressDao
    abstract fun history(): HistoryDao
    abstract fun episodeCache(): EpisodeCacheDao
    abstract fun customChannels(): CustomChannelDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, InkDatabase::class.java, "inkwell.db")
                .fallbackToDestructiveMigration(true).build()
    }
}

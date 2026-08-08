package com.andreassamitsch.ilauncher.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [
        TmdbMappingEntity::class,
        TmdbMediaEntity::class,
        TmdbEpisodeEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class ILauncherDatabase : RoomDatabase() {
    abstract fun tmdbDao(): TmdbDao

    companion object {
        private val MIGRATION_1_2 = Migration(1, 2) { db ->
            db.execSQL("ALTER TABLE tmdb_media ADD COLUMN trailerYoutubeId TEXT")
            db.execSQL("ALTER TABLE tmdb_media ADD COLUMN videoLookupComplete INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tmdb_episodes ADD COLUMN trailerYoutubeId TEXT")
            db.execSQL("ALTER TABLE tmdb_episodes ADD COLUMN videoLookupComplete INTEGER NOT NULL DEFAULT 0")
        }

        @Volatile
        private var instance: ILauncherDatabase? = null

        fun get(context: Context): ILauncherDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ILauncherDatabase::class.java,
                    "i-launcher.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}

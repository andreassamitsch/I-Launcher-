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
        EpgChannelMappingEntity::class,
        EpgProgramEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class ILauncherDatabase : RoomDatabase() {
    abstract fun tmdbDao(): TmdbDao
    abstract fun epgDao(): EpgDao

    companion object {
        private val MIGRATION_1_2 = Migration(1, 2) { db ->
            db.execSQL("ALTER TABLE tmdb_media ADD COLUMN trailerYoutubeId TEXT")
            db.execSQL("ALTER TABLE tmdb_media ADD COLUMN videoLookupComplete INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE tmdb_episodes ADD COLUMN trailerYoutubeId TEXT")
            db.execSQL("ALTER TABLE tmdb_episodes ADD COLUMN videoLookupComplete INTEGER NOT NULL DEFAULT 0")
        }

        private val MIGRATION_2_3 = Migration(2, 3) { db ->
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_channel_mappings` (" +
                    "`serviceReference` TEXT NOT NULL, " +
                    "`xmltvChannelId` TEXT NOT NULL, " +
                    "`matchMethod` TEXT NOT NULL, " +
                    "`confidence` REAL NOT NULL, " +
                    "`updatedAtUtcMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`serviceReference`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_epg_channel_mappings_xmltvChannelId` " +
                    "ON `epg_channel_mappings` (`xmltvChannelId`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `epg_programs` (" +
                    "`programKey` TEXT NOT NULL, " +
                    "`xmltvChannelId` TEXT NOT NULL, " +
                    "`startUtcMillis` INTEGER NOT NULL, " +
                    "`stopUtcMillis` INTEGER NOT NULL, " +
                    "`title` TEXT NOT NULL, " +
                    "`subtitle` TEXT, " +
                    "`description` TEXT, " +
                    "`categories` TEXT, " +
                    "`seasonNumber` INTEGER, " +
                    "`episodeNumber` INTEGER, " +
                    "`releaseYear` INTEGER, " +
                    "`imageUri` TEXT, " +
                    "`updatedAtUtcMillis` INTEGER NOT NULL, " +
                    "PRIMARY KEY(`programKey`))",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_epg_programs_xmltvChannelId_startUtcMillis` " +
                    "ON `epg_programs` (`xmltvChannelId`, `startUtcMillis`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_epg_programs_startUtcMillis_stopUtcMillis` " +
                    "ON `epg_programs` (`startUtcMillis`, `stopUtcMillis`)",
            )
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
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}

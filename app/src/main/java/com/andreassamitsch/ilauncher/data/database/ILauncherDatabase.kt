package com.andreassamitsch.ilauncher.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TmdbMappingEntity::class,
        TmdbMediaEntity::class,
        TmdbEpisodeEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ILauncherDatabase : RoomDatabase() {
    abstract fun tmdbDao(): TmdbDao

    companion object {
        @Volatile
        private var instance: ILauncherDatabase? = null

        fun get(context: Context): ILauncherDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ILauncherDatabase::class.java,
                    "i-launcher.db",
                ).build().also { instance = it }
            }
    }
}

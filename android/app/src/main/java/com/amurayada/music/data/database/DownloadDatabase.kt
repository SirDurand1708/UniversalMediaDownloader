package com.amurayada.music.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DownloadEntity::class, DownloadQueueEntity::class],
    version = 4,
    exportSchema = false
)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun downloadQueueDao(): DownloadQueueDao

    companion object {
        @Volatile
        private var INSTANCE: DownloadDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `download_queue` (
                        `processId` TEXT NOT NULL,
                        `url` TEXT NOT NULL,
                        `normalizedUrl` TEXT NOT NULL,
                        `formatName` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `status` TEXT NOT NULL,
                        `errorReason` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`processId`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getDatabase(context: Context): DownloadDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DownloadDatabase::class.java,
                    "download_database"
                )
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigrationFrom(1, 2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

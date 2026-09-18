package com.workoutlab.track.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, PointEntity::class],
    version = 4,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE sessions ADD COLUMN recordedElapsedMs INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE points ADD COLUMN startsSegment INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sessions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `startedAtEpochMs` INTEGER NOT NULL,
                        `stoppedAtEpochMs` INTEGER,
                        `workoutDistanceMeters` REAL NOT NULL,
                        `pointCount` INTEGER NOT NULL,
                        `excludedSampleCount` INTEGER NOT NULL,
                        `recordedElapsedMs` INTEGER NOT NULL,
                        `status` TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO `sessions_new` (
                        `id`, `startedAtEpochMs`, `stoppedAtEpochMs`,
                        `workoutDistanceMeters`, `pointCount`, `excludedSampleCount`,
                        `recordedElapsedMs`, `status`
                    )
                    SELECT
                        `id`, `startedAtEpochMs`, `stoppedAtEpochMs`,
                        `workoutDistanceMeters`, `pointCount`, `excludedSampleCount`,
                        `recordedElapsedMs`, `status`
                    FROM `sessions`
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE `sessions`")
                db.execSQL("ALTER TABLE `sessions_new` RENAME TO `sessions`")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "workout_lab_track.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
    }
}

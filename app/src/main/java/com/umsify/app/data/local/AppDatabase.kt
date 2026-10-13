package com.umsify.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.data.model.User

import com.umsify.app.data.model.SyncMetadataEntity

/**
 * UMSify local Room database.
 * Stores the authenticated user's profile, timetable, and exam data locally
 * so the app works offline after the first sync.
 */
@Database(
    entities = [User::class, TimetableEntry::class, ExamEntry::class, SyncMetadataEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun userDao(): UserDao
    abstract fun timetableDao(): TimetableDao
    abstract fun examDao(): ExamDao
    abstract fun syncMetadataDao(): SyncMetadataDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "umsify_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

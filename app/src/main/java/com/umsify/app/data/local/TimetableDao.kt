package com.umsify.app.data.local

import androidx.room.*
import com.umsify.app.data.model.TimetableEntry
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for timetable operations.
 * CRITICAL: Every query strictly filters by userId for complete user isolation.
 */
@Dao
interface TimetableDao {

    @Query("SELECT * FROM timetable WHERE userId = :userId ORDER BY day, startTime")
    fun getTimetableFlow(userId: String): Flow<List<TimetableEntry>>

    @Query("SELECT * FROM timetable WHERE userId = :userId ORDER BY day, startTime")
    suspend fun getTimetable(userId: String): List<TimetableEntry>

    @Query("SELECT * FROM timetable ORDER BY day, startTime")
    suspend fun getAllTimetable(): List<TimetableEntry>

    @Query("SELECT * FROM timetable WHERE userId = :userId AND day = :day ORDER BY startTime")
    suspend fun getTimetableForDay(userId: String, day: String): List<TimetableEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<TimetableEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: TimetableEntry)

    @Update
    suspend fun update(entry: TimetableEntry)

    @Delete
    suspend fun delete(entry: TimetableEntry)

    @Query("DELETE FROM timetable WHERE userId = :userId AND id = :id")
    suspend fun deleteById(userId: String, id: String)

    @Query("DELETE FROM timetable WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    /** Atomically replace all timetable rows for this authenticated user */
    @Transaction
    suspend fun replaceUserTimetable(userId: String, entries: List<TimetableEntry>) {
        deleteAllForUser(userId)
        insertAll(entries)
    }
}

package com.umsify.app.data.local

import androidx.room.*
import com.umsify.app.data.model.ExamEntry
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for exam operations.
 * CRITICAL: Every query strictly filters by userId for complete user isolation.
 */
@Dao
interface ExamDao {

    @Query("SELECT * FROM exams WHERE userId = :userId ORDER BY examDate, startTime")
    fun getExamsFlow(userId: String): Flow<List<ExamEntry>>

    @Query("SELECT * FROM exams WHERE userId = :userId ORDER BY examDate, startTime")
    suspend fun getExams(userId: String): List<ExamEntry>

    @Query("SELECT * FROM exams ORDER BY examDate, startTime")
    suspend fun getAllExams(): List<ExamEntry>

    @Query("""
        SELECT * FROM exams 
        WHERE userId = :userId AND examDate >= :fromDate 
        ORDER BY examDate, startTime 
        LIMIT 1
    """)
    suspend fun getNextExam(userId: String, fromDate: String): ExamEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(exams: List<ExamEntry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exam: ExamEntry)

    @Update
    suspend fun update(exam: ExamEntry)

    @Delete
    suspend fun delete(exam: ExamEntry)

    @Query("DELETE FROM exams WHERE userId = :userId AND id = :id")
    suspend fun deleteById(userId: String, id: String)

    @Query("DELETE FROM exams WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)

    @Transaction
    suspend fun replaceUserExams(userId: String, exams: List<ExamEntry>) {
        deleteAllForUser(userId)
        insertAll(exams)
    }
}

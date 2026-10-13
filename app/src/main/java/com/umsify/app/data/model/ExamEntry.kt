package com.umsify.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.umsify.app.utils.HashUtils
import java.util.UUID

/**
 * Represents a single examination record for a user.
 * Matches section 7 and 9 of UMSify specification.
 */
@Entity(tableName = "exams")
data class ExamEntry(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val courseCode: String,
    val courseName: String = "",
    val examDate: String,                 // "YYYY-MM-DD" (ISO format)
    val startTime: String,                // "09:30" (24-hour HH:mm)
    val endTime: String,                  // "12:30" (24-hour HH:mm)
    val room: String = "",
    val examType: String = "Theory End Term",
    val updatedAt: String = "",
    val recordHash: String = ""
) : java.io.Serializable {
    /**
     * Calculates the deterministic record hash:
     * SHA-256(userId + courseCode + examDate + startTime + examType)
     */
    fun computeHash(): String {
        return HashUtils.calculateExamRecordHash(
            userId = userId,
            courseCode = courseCode,
            examDate = examDate,
            startTime = startTime,
            examType = examType
        )
    }

    /**
     * Returns a copy with calculated recordHash and normalized fields.
     */
    fun normalized(): ExamEntry {
        val normDate = HashUtils.normalizeDate(examDate)
        val normStart = HashUtils.normalizeTime(startTime)
        val normEnd = HashUtils.normalizeTime(endTime)
        val hash = HashUtils.calculateExamRecordHash(
            userId = userId,
            courseCode = courseCode,
            examDate = normDate,
            startTime = normStart,
            examType = examType
        )
        return copy(
            examDate = normDate,
            startTime = normStart,
            endTime = normEnd,
            recordHash = hash
        )
    }
}

typealias ExamEntity = ExamEntry

package com.umsify.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import com.umsify.app.utils.HashUtils
import java.util.UUID

/**
 * Represents a single class slot in a user's timetable.
 * Matches section 6 and 9 of UMSify specification.
 */
@Entity(tableName = "timetable")
data class TimetableEntry(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val day: String,                     // "Monday", "Tuesday", etc.
    val date: String? = null,            // null for weekly recurring schedules, or "YYYY-MM-DD"
    val startTime: String,               // "09:20" (24-hour HH:mm)
    val endTime: String,                 // "10:10" (24-hour HH:mm)
    val courseCode: String,
    val courseName: String = "",
    @SerializedName("type")
    val type: String = "LEC",            // "LEC", "PRAC", "TUT"
    val room: String = "",
    val faculty: String = "",
    val group: String = "",
    val section: String = "",
    val updatedAt: String = "",
    val recordHash: String = ""
) : java.io.Serializable {
    // Backward-compatibility alias for existing UI adapters
    val classType: String
        get() = type

    /**
     * Calculates the deterministic record hash:
     * SHA-256(userId + day + date + startTime + endTime + courseCode + type + section)
     */
    fun computeHash(): String {
        return HashUtils.calculateTimetableRecordHash(
            userId = userId,
            day = day,
            date = date,
            startTime = startTime,
            endTime = endTime,
            courseCode = courseCode,
            type = type,
            section = section
        )
    }

    /**
     * Returns a copy with calculated recordHash and normalized fields.
     */
    fun normalized(): TimetableEntry {
        val normDay = HashUtils.normalizeDay(day)
        val normStart = HashUtils.normalizeTime(startTime)
        val normEnd = HashUtils.normalizeTime(endTime)
        val normDate = date?.let { HashUtils.normalizeDate(it) }
        val normType = type.ifBlank { "LEC" }
        val hash = HashUtils.calculateTimetableRecordHash(
            userId = userId,
            day = normDay,
            date = normDate,
            startTime = normStart,
            endTime = normEnd,
            courseCode = courseCode,
            type = normType,
            section = section
        )
        return copy(
            day = normDay,
            date = normDate,
            startTime = normStart,
            endTime = normEnd,
            type = normType,
            recordHash = hash
        )
    }
}

typealias TimetableEntity = TimetableEntry

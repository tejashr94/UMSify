package com.umsify.app.utils

import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Utility for cryptographic hashing and academic data normalization.
 * Follows exact specifications in sections 15, 16, 17, 30, and 31.
 */
object HashUtils {

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * Deterministic identity hash for a timetable entry:
     * SHA-256(userId + day + (date ?: "") + startTime + endTime + courseCode + type + section)
     */
    fun calculateTimetableRecordHash(
        userId: String,
        day: String,
        date: String?,
        startTime: String,
        endTime: String,
        courseCode: String,
        type: String,
        section: String
    ): String {
        val normalized = listOf(
            userId.trim(),
            normalizeDay(day).trim(),
            (date?.trim() ?: ""),
            normalizeTime(startTime).trim(),
            normalizeTime(endTime).trim(),
            courseCode.trim().uppercase(Locale.US),
            type.trim().uppercase(Locale.US),
            section.trim().uppercase(Locale.US)
        ).joinToString("|")
        return sha256(normalized)
    }

    /**
     * Deterministic identity hash for an exam record:
     * SHA-256(userId + courseCode + examDate + startTime + examType)
     */
    fun calculateExamRecordHash(
        userId: String,
        courseCode: String,
        examDate: String,
        startTime: String,
        examType: String
    ): String {
        val normalized = listOf(
            userId.trim(),
            courseCode.trim().uppercase(Locale.US),
            normalizeDate(examDate).trim(),
            normalizeTime(startTime).trim(),
            examType.trim().uppercase(Locale.US)
        ).joinToString("|")
        return sha256(normalized)
    }

    /**
     * Normalizes day strings like "MON", "Mon", "monday" into standard capitalized day name.
     */
    fun normalizeDay(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val clean = raw.trim().lowercase(Locale.US)
        return when {
            clean.startsWith("mon") -> "Monday"
            clean.startsWith("tue") -> "Tuesday"
            clean.startsWith("wed") -> "Wednesday"
            clean.startsWith("thu") -> "Thursday"
            clean.startsWith("fri") -> "Friday"
            clean.startsWith("sat") -> "Saturday"
            clean.startsWith("sun") -> "Sunday"
            else -> raw.trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
        }
    }

    /**
     * Normalizes time into 24-hour "HH:mm" format.
     * Accurately parses Google Sheets 1899 UTC ISO timestamps (e.g. 1899-12-30T03:38:50.000Z)
     * converting to IST (Asia/Kolkata), resulting in exact original times like 09:00.
     */
    fun normalizeTime(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()

        if (trimmed.contains("T")) {
            try {
                val cleanIso = trimmed.substringBefore(".").removeSuffix("Z")
                val sdfUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdfUtc.parse(cleanIso)
                if (parsed != null) {
                    val outFormat = SimpleDateFormat("HH:mm", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                    }
                    return outFormat.format(parsed)
                }
            } catch (_: Exception) {}

            val timePart = trimmed.substringAfter("T").substringBefore(".").substringBefore("Z")
            if (timePart.length >= 5) {
                return timePart.take(5)
            }
        }

        // Test 12-hour with AM/PM first
        val formats = arrayOf(
            "hh:mm a",
            "h:mm a",
            "hh:mma",
            "h:mma",
            "HH:mm",
            "H:mm"
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val pos = ParsePosition(0)
                val parsed = sdf.parse(trimmed, pos)
                if (parsed != null && pos.index == trimmed.length) {
                    return SimpleDateFormat("HH:mm", Locale.US).format(parsed)
                }
            } catch (_: Exception) { }
        }
        return trimmed
    }

    /**
     * Normalizes date to "YYYY-MM-DD".
     * Accurately parses Google Sheets UTC ISO timestamps (e.g. 2026-11-14T18:30:00.000Z)
     * converting to IST (Asia/Kolkata), preventing 1-day timezone offset shifts.
     */
    fun normalizeDate(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        val trimmed = raw.trim()

        if (trimmed.contains("T")) {
            try {
                val cleanIso = trimmed.substringBefore(".").removeSuffix("Z")
                val sdfUtc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val parsed = sdfUtc.parse(cleanIso)
                if (parsed != null) {
                    val outFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("Asia/Kolkata")
                    }
                    return outFormat.format(parsed)
                }
            } catch (_: Exception) {}

            val datePart = trimmed.substringBefore("T")
            if (datePart.length >= 10) {
                return datePart.take(10)
            }
        }

        val formats = arrayOf(
            "yyyy-MM-dd",
            "dd-MM-yyyy",
            "dd/MM/yyyy",
            "yyyy/MM/dd",
            "dd MMM yyyy",
            "d MMM yyyy",
            "dd MMMM yyyy",
            "d MMMM yyyy"
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
                val pos = ParsePosition(0)
                val parsed = sdf.parse(trimmed, pos)
                if (parsed != null && pos.index == trimmed.length) {
                    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(parsed)
                }
            } catch (_: Exception) { }
        }
        return trimmed
    }
}

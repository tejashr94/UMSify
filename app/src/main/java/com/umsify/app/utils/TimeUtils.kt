package com.umsify.app.utils

import com.umsify.app.data.model.TimetableEntry
import java.text.SimpleDateFormat
import java.util.*

/**
 * Time-related utilities for current class detection, countdown, etc.
 */
object TimeUtils {

    private val TIME_FORMAT_24H = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val DATE_FORMAT_ISO = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    enum class ClassState { UPCOMING, CURRENT, COMPLETED }

    /**
     * Returns the Android DayOfWeek name matching Google Sheet "day" column
     */
    fun getTodayName(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Monday"
            Calendar.TUESDAY -> "Tuesday"
            Calendar.WEDNESDAY -> "Wednesday"
            Calendar.THURSDAY -> "Thursday"
            Calendar.FRIDAY -> "Friday"
            Calendar.SATURDAY -> "Saturday"
            Calendar.SUNDAY -> "Sunday"
            else -> ""
        }
    }

    fun getTodayIso(): String {
        return DATE_FORMAT_ISO.format(Date())
    }

    /** Parse "HH:mm" into minutes-since-midnight for comparison */
    fun parseTimeToMinutes(time: String): Int {
        return try {
            val parts = time.split(":")
            parts[0].toInt() * 60 + parts[1].toInt()
        } catch (e: Exception) {
            -1
        }
    }

    fun getCurrentTimeMinutes(): Int {
        val cal = Calendar.getInstance()
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    fun getClassState(entry: TimetableEntry, forDay: String = ""): ClassState {
        val cal = Calendar.getInstance()
        val todayName = SimpleDateFormat("EEEE", Locale.US).format(cal.time)
        val targetDay = if (forDay.isNotBlank()) forDay else entry.day

        // If this class is NOT scheduled for today, it is strictly UPCOMING — NEVER COMPLETED!
        if (!targetDay.equals(todayName, ignoreCase = true)) {
            return ClassState.UPCOMING
        }

        // On today: only mark COMPLETED when current time has actually passed endTime!
        val now = getCurrentTimeMinutes()
        val start = parseTimeToMinutes(entry.startTime)
        val end = parseTimeToMinutes(entry.endTime)
        return when {
            now < start -> ClassState.UPCOMING
            now in start until end -> ClassState.CURRENT
            else -> ClassState.COMPLETED
        }
    }

    /** Format "HH:mm" 24h to "hh:mm AM/PM" 12h display */
    fun formatTo12h(time24: String): String {
        return try {
            val clean = HashUtils.normalizeTime(time24)
            val date = TIME_FORMAT_24H.parse(clean) ?: return clean
            SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            time24
        }
    }

    /** Days between today and examDate (ISO string) */
    fun daysUntil(isoDate: String): Int {
        return try {
            val clean = HashUtils.normalizeDate(isoDate)
            val exam = DATE_FORMAT_ISO.parse(clean) ?: return -1
            val today = DATE_FORMAT_ISO.parse(getTodayIso()) ?: return -1
            val diff = exam.time - today.time
            (diff / (1000 * 60 * 60 * 24)).toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /** Human-readable relative time since last sync */
    fun relativeTime(timestampMs: Long): String {
        if (timestampMs == 0L) return "Never"
        val diffMs = System.currentTimeMillis() - timestampMs
        val minutes = diffMs / 60_000
        return when {
            minutes < 1 -> "Just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 1440 -> "${minutes / 60} hr ago"
            else -> "${minutes / 1440} days ago"
        }
    }

    /** Greeting based on current hour */
    fun getGreeting(): String {
        return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..20 -> "Good evening"
            else -> "Good night"
        }
    }

    fun formatExamDate(isoDate: String): String {
        return try {
            val date = DATE_FORMAT_ISO.parse(isoDate) ?: return isoDate
            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(date)
        } catch (e: Exception) {
            isoDate
        }
    }

    /**
     * Merges duplicate entries and consecutive class slots for the same course on the same day.
     * Prevents classes (such as 2-hour practicals or consecutive lecture hours) from being
     * shown twice in the app, combining them into a single continuous time span.
     */
    fun mergeAndDeduplicateTimetable(entries: List<TimetableEntry>): List<TimetableEntry> {
        if (entries.isEmpty()) return emptyList()

        val dayOrder = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        // 1. Group by normalized day and uppercase course code
        val grouped = entries.groupBy {
            val normDay = it.day.trim().lowercase().replaceFirstChar { c -> c.uppercase() }
            val normCode = it.courseCode.trim().uppercase()
            "$normDay|$normCode"
        }

        val mergedList = mutableListOf<TimetableEntry>()

        for ((_, group) in grouped) {
            if (group.isEmpty()) continue

            // Sort by start time in minutes
            val sorted = group.sortedBy {
                val mins = parseTimeToMinutes(it.startTime)
                if (mins < 0) 0 else mins
            }

            var current = sorted[0]

            for (i in 1 until sorted.size) {
                val next = sorted[i]
                val curStart = parseTimeToMinutes(current.startTime)
                val curEnd = parseTimeToMinutes(current.endTime)
                val nextStart = parseTimeToMinutes(next.startTime)
                val nextEnd = parseTimeToMinutes(next.endTime)

                // Overlapping or consecutive back-to-back classes (e.g., 2-hour lab or contiguous slots, gap <= 15 min)
                val isOverlapping = curStart in 0..curEnd && nextStart in 0..nextEnd && (nextStart <= curEnd)
                val isConsecutive = curStart in 0..curEnd && nextStart in 0..nextEnd && (nextStart <= curEnd + 15)

                if (isOverlapping || isConsecutive) {
                    val mergedStart = if (curStart in 0..nextStart) current.startTime else next.startTime
                    val mergedEnd = if (curEnd >= nextEnd) current.endTime else next.endTime

                    val mergedRoom = when {
                        current.room.isNotBlank() -> current.room
                        next.room.isNotBlank() -> next.room
                        else -> ""
                    }
                    val defaultName = com.umsify.app.data.local.parser.LpuTimetableParser.DEFAULT_COURSE_CATALOG[current.courseCode.uppercase()]
                    val mergedName = when {
                        defaultName != null -> defaultName
                        current.courseName.isNotBlank() && current.courseName != current.courseCode && !current.courseName.contains("(") -> current.courseName
                        next.courseName.isNotBlank() && next.courseName != next.courseCode && !next.courseName.contains("(") -> next.courseName
                        else -> current.courseName.ifBlank { next.courseName }
                    }
                    val mergedFaculty = current.faculty.ifBlank { next.faculty }
                    val mergedSection = current.section.ifBlank { next.section }
                    val mergedGroup = current.group.ifBlank { next.group }
                    val mergedType = when {
                        current.type in listOf("PRA", "PRACTICAL", "LAB", "TUT") -> current.type
                        next.type in listOf("PRA", "PRACTICAL", "LAB", "TUT") -> next.type
                        else -> current.type.ifBlank { next.type }
                    }

                    current = current.copy(
                        startTime = mergedStart,
                        endTime = mergedEnd,
                        room = mergedRoom,
                        courseName = mergedName,
                        faculty = mergedFaculty,
                        section = mergedSection,
                        group = mergedGroup,
                        type = mergedType
                    ).normalized()
                } else {
                    mergedList.add(current)
                    current = next
                }
            }
            mergedList.add(current)
        }

        // 2. Filter out non-academic or placeholder entries
        val validAcademicList = mergedList.filter { entry ->
            val code = entry.courseCode.uppercase()
            val isBogus = code.startsWith("K2") || code.startsWith("9R") || code.startsWith("KM") ||
                    code.startsWith("KO") || code.startsWith("KK") || code.startsWith("CH") ||
                    code.startsWith("WW") || code.startsWith("AUG") || code.startsWith("SEP") ||
                    code.startsWith("OCT") || code.startsWith("NOV") || code.startsWith("DEC") ||
                    code.startsWith("JAN") || code.startsWith("FEB") || code.startsWith("MAR") ||
                    code.startsWith("APR") || code.startsWith("MAY") || code.startsWith("JUN") ||
                    code.startsWith("JUL") || code.contains("20240") || code.contains("PROJECT") ||
                    entry.courseName.contains("Project Work", ignoreCase = true) ||
                    entry.courseName.contains("Weekly Activities", ignoreCase = true) ||
                    entry.courseName.contains("Check Schedule", ignoreCase = true)
            !isBogus
        }

        // 3. Resolve conflicting classes at the exact same start time on the same day
        // Ensures only one authentic class exists per time slot
        val resolvedByDay = validAcademicList.groupBy {
            it.day.trim().lowercase().replaceFirstChar { c -> c.uppercase() }
        }
        val finalCleanList = mutableListOf<TimetableEntry>()

        for ((_, dayClasses) in resolvedByDay) {
            val byStartTime = dayClasses.groupBy { it.startTime }
            for ((_, timeGroup) in byStartTime) {
                if (timeGroup.size == 1) {
                    val single = timeGroup[0]
                    val cleanTitle = com.umsify.app.data.local.parser.LpuTimetableParser.DEFAULT_COURSE_CATALOG[single.courseCode.uppercase()]
                    finalCleanList.add(if (cleanTitle != null && single.courseName != cleanTitle) single.copy(courseName = cleanTitle) else single)
                } else {
                    val best = timeGroup.maxByOrNull { entry ->
                        var score = 0
                        val upperCode = entry.courseCode.uppercase()
                        if (com.umsify.app.data.local.parser.LpuTimetableParser.DEFAULT_COURSE_CATALOG.containsKey(upperCode)) score += 50
                        if (entry.courseName.isNotBlank() && entry.courseName != entry.courseCode && !entry.courseName.contains("(")) score += 20
                        if (entry.room.isNotBlank()) score += 15
                        if (entry.courseCode.length in 5..8) score += 5
                        score
                    } ?: timeGroup[0]
                    val cleanTitle = com.umsify.app.data.local.parser.LpuTimetableParser.DEFAULT_COURSE_CATALOG[best.courseCode.uppercase()]
                    finalCleanList.add(if (cleanTitle != null && best.courseName != cleanTitle) best.copy(courseName = cleanTitle) else best)
                }
            }
        }

        // 4. Sort chronologically by day of week, then by start time
        return finalCleanList.sortedWith(compareBy(
            { dayOrder.indexOfFirst { d -> d.equals(it.day, ignoreCase = true) }.let { idx -> if (idx < 0) 99 else idx } },
            { parseTimeToMinutes(it.startTime).let { m -> if (m < 0) 0 else m } }
        ))
    }
}

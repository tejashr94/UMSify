package com.umsify.app.data.local.parser

import com.umsify.app.data.model.ExamEntry
import com.umsify.app.utils.HashUtils
import com.umsify.app.utils.TimeUtils
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * Rule-based parser for LPU Examination Hall Ticket / Admit Card documents.
 * Completely local — NO generative AI.
 */
object LpuExamParser {

    private val EXAM_ROW_REGEX = Regex(
        """(?:^|\n)\s*(?:\d+\s+)?([A-Z]{2,5}[0-9]{3}[A-Z0-9]*)\s+(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4})\s+(\d{1,2}:\d{2}\s*[-–]\s*\d{1,2}:\d{2})\s*(?:([0-9]{2,3}-[0-9]{3}[A-Za-z0-9]*)\s+)?(.*)""",
        RegexOption.IGNORE_CASE
    )

    private val KNOWN_COURSE_NAMES = mapOf(
        "PEA308" to "ADVANCED ANALYTICAL SKILLS-II",
        "CSE408" to "DESIGN AND ANALYSIS OF ALGORITHMS",
        "CSE224" to "FUNDAMENTALS OF ANDROID",
        "INT252" to "WEB APP DEVELOPMENT WITH REACTJS",
        "INT257" to "MODERN WEB APPLICATION DEVELOPMENT",
        "CSE443" to "SEMINAR ON SUMMER TRAINING",
        "PEAS01" to "ANALYTICAL - I",
        "PES390" to "SOFT SKILLS",
        "PETV128" to "ANDROID FOR BEGINNERS: BUILD YOUR FIRST APP",
        "PEVS01" to "VERBAL - I"
    )

    fun parse(document: ExtractedDocument, userId: String): ParseResult {
        val text = document.combinedText
            .replace(Regex("""-\s+"""), "-")
            .replace(Regex("""\s+-"""), "-")

        val regNo = Regex("""Registration\s*Number\s*:\s*(\d+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1) ?: ""

        val studentName = Regex("""Student\s*Name\s*:\s*([^\n\r]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim() ?: ""

        val program = Regex("""Program\s*:\s*([^\n]+)""", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.get(1)?.trim() ?: ""

        val exams = mutableListOf<ExamEntry>()

        // 1. Scan with full row regex
        val matches = EXAM_ROW_REGEX.findAll(text)
        for (m in matches) {
            val courseCode = m.groupValues[1].uppercase().trim()
            val rawDate = m.groupValues[2].trim()
            val rawTime = m.groupValues[3].trim()
            val room = m.groupValues[4].trim()
            var desc = m.groupValues[5].trim()

            // Clean description of instructions if caught in line
            desc = desc.replace(Regex("""Important Instructions.*$""", RegexOption.IGNORE_CASE), "").trim()

            val isoDate = normalizeDateToIso(rawDate)
            val (startTime, endTime) = parseExamTiming(rawTime)
            val courseName = KNOWN_COURSE_NAMES[courseCode] ?: courseCode
            val examType = if (desc.isNotBlank()) desc else "Theory End Term"

            exams.add(
                ExamEntry(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    courseCode = courseCode,
                    courseName = courseName,
                    examDate = isoDate,
                    startTime = startTime,
                    endTime = endTime,
                    room = room,
                    examType = examType
                ).normalized()
            )
        }

        // 2. Line-by-line fallback if table headers split lines across OCR blocks
        if (exams.isEmpty()) {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
            for (line in lines) {
                val lineMatch = Regex(
                    """\b([A-Z]{2,5}[0-9]{3}[A-Z0-9]*)\b.*\b(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4})\b.*\b(\d{1,2}:\d{2}\s*[-–]\s*\d{1,2}:\d{2})\b""",
                    RegexOption.IGNORE_CASE
                ).find(line)

                if (lineMatch != null) {
                    val courseCode = lineMatch.groupValues[1].uppercase()
                    val rawDate = lineMatch.groupValues[2]
                    val rawTime = lineMatch.groupValues[3]

                    val roomMatch = Regex("""(?:Room\s*(?:No\.?)?\s*[:\s]*|\b)([0-9]{2,3}-[0-9]{3}[A-Za-z0-9]*)\b""", RegexOption.IGNORE_CASE).find(line)
                    val room = (if ((roomMatch?.groupValues?.size ?: 0) > 1) roomMatch?.groupValues?.get(1) else roomMatch?.value)?.trim() ?: ""

                    val descMatch = Regex("""\b(Objective Type Mid Term|Theory End Term|Mid Term|End Term|Practical|Theory)\b""", RegexOption.IGNORE_CASE).find(line)
                    val examType = descMatch?.value ?: "End Term"

                    val isoDate = normalizeDateToIso(rawDate)
                    val (startTime, endTime) = parseExamTiming(rawTime)

                    exams.add(
                        ExamEntry(
                            id = UUID.randomUUID().toString(),
                            userId = userId,
                            courseCode = courseCode,
                            courseName = KNOWN_COURSE_NAMES[courseCode] ?: courseCode,
                            examDate = isoDate,
                            startTime = startTime,
                            endTime = endTime,
                            room = room,
                            examType = examType
                        ).normalized()
                    )
                }
            }
        }

        // 3. Columnar / Structural Extraction:
        // When ML Kit OCR groups table columns into separate vertical blocks
        if (exams.isEmpty()) {
            val codeRegex = Regex("""\b([A-Z]{2,5}[0-9]{3}[A-Z0-9]*)\b""")
            val courseCodes = codeRegex.findAll(text)
                .map { it.groupValues[1].uppercase().trim() }
                .filter { code ->
                    code !in listOf("P132", "P133", "P134", "P135", "LPU", "VID", "DATE", "TIME", "ROOM", "MID", "END", "TYPE") &&
                    !code.startsWith("K2")
                }
                .toList()

            val dateRegex = Regex("""\b(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4})\b""", RegexOption.IGNORE_CASE)
            val dates = dateRegex.findAll(text)
                .map { it.groupValues[1].trim() }
                .toList()

            val timeRegex = Regex("""\b(\d{1,2}:\d{2}\s*[-–]\s*\d{1,2}:\d{2})\b""")
            val timings = timeRegex.findAll(text)
                .map { it.groupValues[1].trim() }
                .toList()

            val roomRegex = Regex("""\b([0-9]{2,3}-[0-9]{3}[A-Za-z0-9]*)\b""")
            val rawRooms = roomRegex.findAll(text)
                .map { it.groupValues[1].trim() }
                .filter { it !in listOf("P132") }
                .distinct()
                .toList()

            val descRegex = Regex("""\b(Objective Type Mid Term|Theory End Term|Mid Term|End Term|Practical|Theory)\b""", RegexOption.IGNORE_CASE)
            val descriptions = descRegex.findAll(text)
                .map { it.groupValues[1].trim() }
                .toList()

            val rowCount = minOf(courseCodes.size, dates.size, timings.size)
            if (rowCount > 0) {
                for (i in 0 until rowCount) {
                    val cCode = courseCodes[i]
                    val rawDate = dates[i]
                    val rawTime = timings[i]
                    val cRoom = if (i < rawRooms.size) rawRooms[i] else ""
                    val cDesc = if (i < descriptions.size) descriptions[i] else "Theory End Term"

                    val isoDate = normalizeDateToIso(rawDate)
                    val (startTime, endTime) = parseExamTiming(rawTime)
                    val courseName = KNOWN_COURSE_NAMES[cCode] ?: cCode

                    exams.add(
                        ExamEntry(
                            id = UUID.randomUUID().toString(),
                            userId = userId,
                            courseCode = cCode,
                            courseName = courseName,
                            examDate = isoDate,
                            startTime = startTime,
                            endTime = endTime,
                            room = cRoom,
                            examType = cDesc
                        ).normalized()
                    )
                }
            }
        }

        // 4. Sliding Window Extraction:
        // Find course code, then find closest subsequent date and timing within 300 chars
        if (exams.isEmpty()) {
            val codeRegex = Regex("""\b([A-Z]{2,5}[0-9]{3}[A-Z0-9]*)\b""")
            val dateRegex = Regex("""\b(\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4})\b""", RegexOption.IGNORE_CASE)
            val timeRegex = Regex("""\b(\d{1,2}:\d{2}\s*[-–]\s*\d{1,2}:\d{2})\b""")
            val roomRegex = Regex("""\b([0-9]{2,3}-[0-9]{3}[A-Za-z0-9]*)\b""")

            val codeMatches = codeRegex.findAll(text).toList()
            for (cm in codeMatches) {
                val code = cm.groupValues[1].uppercase().trim()
                if (code in listOf("P132", "P133", "P134", "P135", "LPU", "VID", "DATE", "TIME") || code.startsWith("K2")) continue

                val startIndex = cm.range.first
                val window = text.substring(startIndex, minOf(text.length, startIndex + 300))

                val dMatch = dateRegex.find(window)
                val tMatch = timeRegex.find(window)
                if (dMatch != null && tMatch != null) {
                    val rMatch = roomRegex.find(window)
                    val room = rMatch?.groupValues?.get(1)?.trim() ?: ""

                    val isoDate = normalizeDateToIso(dMatch.groupValues[1])
                    val (startTime, endTime) = parseExamTiming(tMatch.groupValues[1])
                    val courseName = KNOWN_COURSE_NAMES[code] ?: code
                    val descMatch = Regex("""\b(Objective Type Mid Term|Theory End Term|Mid Term|End Term)\b""", RegexOption.IGNORE_CASE).find(window)
                    val examType = descMatch?.value ?: "End Term"

                    exams.add(
                        ExamEntry(
                            id = UUID.randomUUID().toString(),
                            userId = userId,
                            courseCode = code,
                            courseName = courseName,
                            examDate = isoDate,
                            startTime = startTime,
                            endTime = endTime,
                            room = room,
                            examType = examType
                        ).normalized()
                    )
                }
            }
        }

        if (exams.isEmpty()) {
            return ParseResult.Error("Could not recognize exam date sheet format")
        }

        val uniqueExams = exams.distinctBy { "${it.courseCode}|${it.examDate}|${it.startTime}" }

        return ParseResult.ExamParsed(
            exams = uniqueExams,
            registrationNumber = regNo,
            studentName = studentName,
            program = program
        )
    }

    private fun normalizeDateToIso(rawDate: String): String {
        val formats = listOf(
            "dd MMM yyyy",
            "d MMM yyyy",
            "dd MMMM yyyy",
            "dd-MM-yyyy",
            "dd/MM/yyyy",
            "yyyy-MM-dd"
        )
        for (pattern in formats) {
            try {
                val inFormat = SimpleDateFormat(pattern, Locale.ENGLISH)
                val d = inFormat.parse(rawDate.trim())
                if (d != null) {
                    return SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(d)
                }
            } catch (_: Exception) {
            }
        }
        return rawDate
    }

    private fun parseExamTiming(rawTime: String): Pair<String, String> {
        val parts = rawTime.split(Regex("""[-–]"""))
        if (parts.size != 2) return Pair("09:30 AM", "12:30 PM")

        val start = parts[0].trim()
        val end = parts[1].trim()

        return Pair(formatTo12Hour(start), formatTo12Hour(end))
    }

    private fun formatTo12Hour(time: String): String {
        return try {
            val pieces = time.split(":")
            if (pieces.size != 2) return time
            var hour = pieces[0].toInt()
            val min = pieces[1].padStart(2, '0')
            val ampm = if (hour >= 12) "PM" else "AM"
            if (hour > 12) hour -= 12
            if (hour == 0) hour = 12
            String.format("%02d:%s %s", hour, min, ampm)
        } catch (_: Exception) {
            time
        }
    }
}

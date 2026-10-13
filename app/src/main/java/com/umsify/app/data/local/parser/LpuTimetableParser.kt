package com.umsify.app.data.local.parser

import android.graphics.Rect
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.utils.HashUtils
import com.umsify.app.utils.TimeUtils
import java.util.UUID
import kotlin.math.abs

/**
 * Robust, rule-based parser for LPU academic timetables.
 * Uses 2D layout geometry (column/row bounding boxes) and deterministic regex patterns.
 * Completely local — NO generative AI.
 */
object LpuTimetableParser {

    private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

    private val TIME_SLOT_REGEX = Regex(
        """(\d{1,2}:\d{2})\s*[-–]\s*(\d{1,2}:\d{2})\s*(AM|PM)?""",
        RegexOption.IGNORE_CASE
    )

    private val COURSE_CODE_REGEX = Regex(
        """\b([A-Z]{2,5})\s*([0-9]{2,4}[A-Z0-9]*)\b""",
        RegexOption.IGNORE_CASE
    )

    private val ROOM_REGEX = Regex(
        """(?:R:\s*|\bRoom\s*[:\s]*|\b)([0-9]{2,3}\s*-\s*[0-9]{3}[A-Za-z0-9]*)""",
        RegexOption.IGNORE_CASE
    )

    private val TYPE_REGEX = Regex(
        """\b(Lecture|Practical|Tutorial|LEC|PRA|TUT|LAB|Seminar|Makeup|Workshop)\b""",
        RegexOption.IGNORE_CASE
    )

    // Pre-seeded known LPU courses as safety fallback if "My Course" table is truncated
    val DEFAULT_COURSE_CATALOG = mapOf(
        "CSE224" to "FUNDAMENTALS OF ANDROID",
        "CSE408" to "DESIGN AND ANALYSIS OF ALGORITHMS",
        "CSE443" to "SEMINAR ON SUMMER TRAINING",
        "INT252" to "WEB APP DEVELOPMENT WITH REACTJS",
        "INT257" to "MODERN WEB APPLICATION DEVELOPMENT",
        "PEA308" to "ADVANCED ANALYTICAL SKILLS-II",
        "PEAS01" to "ANALYTICAL - I",
        "PES390" to "SOFT SKILLS",
        "PETV128" to "ANDROID FOR BEGINNERS: BUILD YOUR FIRST APP",
        "PEVS01" to "VERBAL - I"
    )

    fun parse(document: ExtractedDocument, userId: String): ParseResult {
        val courseCatalog = parseCourseCatalog(document.combinedText).toMutableMap()

        val vid = parseStudentVid(document.combinedText)
        val homeSection = parseHomeSection(document.combinedText)

        val entries = mutableListOf<TimetableEntry>()

        var inheritedDays: List<DayColumn> = emptyList()

        for (page in document.pages) {
            val (pageEntries, foundDays) = parsePageWithGrid(page, userId, homeSection, courseCatalog, inheritedDays)
            if (foundDays.isNotEmpty()) {
                inheritedDays = foundDays
            }
            entries.addAll(pageEntries)

            // If a page has class definitions but grid didn't extract any, fallback textually for that page
            if (pageEntries.isEmpty() && page.fullText.contains(Regex("""\b(?:Lecture|Practical|Tutorial|C:)\b"""))) {
                val fallbackEntries = parseTextualFallback(page.fullText, userId, homeSection, courseCatalog)
                entries.addAll(fallbackEntries)
            }
        }

        // If geometric parsing produced no entries at all across all pages, fallback to textual parser
        if (entries.isEmpty()) {
            val fallbackEntries = parseTextualFallback(document.combinedText, userId, homeSection, courseCatalog)
            entries.addAll(fallbackEntries)
        }

        if (entries.isEmpty()) {
            return ParseResult.Error("Could not recognize timetable format")
        }

        // Deduplicate identical class sessions and merge consecutive/split slots (e.g. 2-hour labs)
        val mergedEntries = TimeUtils.mergeAndDeduplicateTimetable(entries)

        return ParseResult.TimetableParsed(
            entries = mergedEntries,
            studentVid = vid,
            homeSection = homeSection,
            courseCatalog = courseCatalog
        )
    }

    private fun parseStudentVid(text: String): String {
        val m = Regex("""Time\s*Table\s*for\s*VID\s*:\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun parseHomeSection(text: String): String {
        val m = Regex("""Home\s*Section\s*[:\s]+([A-Z0-9]+)""", RegexOption.IGNORE_CASE).find(text)
        return m?.groupValues?.get(1) ?: ""
    }

    /**
     * Extracts courses and faculty from the "My Course" table at bottom of page.
     */
    fun parseCourseCatalog(text: String): Map<String, CourseInfo> {
        val catalog = mutableMapOf<String, CourseInfo>()
        for ((code, title) in DEFAULT_COURSE_CATALOG) {
            catalog[code] = CourseInfo(code = code, title = title)
        }

        val lines = text.lines()
        var insideCourseSection = false

        for (line in lines) {
            if (line.contains("My Course", ignoreCase = true) || line.contains("Course Title", ignoreCase = true)) {
                insideCourseSection = true
                continue
            }

            if (insideCourseSection) {
                // Match lines starting with course code e.g. CSE224 OM FUNDAMENTALS OF ANDROID ... or PEAS01 PE ANALYTICAL - I ...
                val m = Regex("""^([A-Z]{2,5}\s*[0-9]{2,4}[A-Z0-9]*)\s+([A-Z]{2})\s+(.+?)(?:\s+\d+\s+\d+\s+\d+\s+\d+|\s*$)""").find(line.trim())
                if (m != null) {
                    val code = m.groupValues[1].replace(" ", "").uppercase()
                    val type = m.groupValues[2]
                    var title = m.groupValues[3].trim()
                    title = title.replace(Regex("""\s*\(.*?\)"""), "") // Remove faculty/cabin parentheses
                    title = title.replace(Regex("""\s+\d+\s+\d+\s+\d+.*$"""), "") // Remove credit counts
                    title = title.replace(Regex("""\s+Last Updated.*$""", RegexOption.IGNORE_CASE), "").trim()

                    val cleanTitle = DEFAULT_COURSE_CATALOG[code] ?: title

                    if (!isSectionCode(code) && !isCabinOrDateCode(code)) {
                        catalog[code] = CourseInfo(
                            code = code,
                            title = cleanTitle,
                            type = type
                        )
                    }
                }
            }
        }
        return catalog
    }

    /**
     * Geometric 2D Grid Parser:
     * Identifies Column Headers (Days) along X-axis and Row Headers (Time slots) along Y-axis.
     * Supports inheriting Column Headers from earlier pages for multi-page PDF documents.
     */
    private fun parsePageWithGrid(
        page: ExtractedPage,
        userId: String,
        defaultSection: String,
        catalog: Map<String, CourseInfo>,
        inheritedDays: List<DayColumn>
    ): Pair<List<TimetableEntry>, List<DayColumn>> {
        val dayColumns = mutableListOf<DayColumn>()
        val timeRows = mutableListOf<TimeRow>()
        val classCandidateBlocks = mutableListOf<ExtractedBlock>()

        // Boundary check: NEVER parse blocks at or below "My Course" table as timetable grid blocks!
        val myCourseBlock = page.blocks.firstOrNull {
            it.text.contains("My Course", ignoreCase = true) ||
            it.text.contains("Course Type :", ignoreCase = true) ||
            it.text.contains("Course Code Type", ignoreCase = true)
        }
        val myCourseTop = myCourseBlock?.effTop ?: Int.MAX_VALUE

        for (block in page.blocks) {
            val blockText = block.text.trim()

            // Skip any block at or below "My Course" table
            if (block.effTop >= myCourseTop - 15) continue

            // 1. Detect Day Column Headers (including merged lines like "Timing Monday Tuesday ... Saturday Sunday")
            if (block.effTop < page.height * 0.40) {
                var foundDayInBlock = false
                for (day in DAYS) {
                    val match = Regex("""\b$day\b""", RegexOption.IGNORE_CASE).find(blockText)
                    if (match != null) {
                        foundDayInBlock = true
                        val charStart = match.range.first
                        val charEnd = match.range.last + 1
                        val charMid = (charStart + charEnd) / 2.0
                        val blockWidth = (block.effRight - block.effLeft).coerceAtLeast(1)
                        val relativeX = if (blockText.isNotEmpty()) (charMid / blockText.length.toDouble()) * blockWidth else 0.0
                        val estimatedCenterX = (block.effLeft + relativeX).toInt()
                        dayColumns.add(
                            DayColumn(
                                dayName = day,
                                centerX = estimatedCenterX,
                                bounds = block.bounds,
                                left = block.effLeft,
                                top = block.effTop,
                                right = block.effRight,
                                bottom = block.effBottom
                            )
                        )
                    }
                }
                if (foundDayInBlock) continue
            }

            // 2. Check if this block is a time slot header
            val timeMatch = TIME_SLOT_REGEX.find(blockText)
            if (timeMatch != null && block.centerX < page.width * 0.35) {
                val raw = timeMatch.value
                val start = normalizeTime(timeMatch.groupValues[1], timeMatch.groupValues[3])
                val end = normalizeTime(timeMatch.groupValues[2], timeMatch.groupValues[3])
                timeRows.add(
                    TimeRow(
                        rawText = raw,
                        startTime = start,
                        endTime = end,
                        centerY = block.centerY,
                        bounds = block.bounds,
                        left = block.effLeft,
                        top = block.effTop,
                        right = block.effRight,
                        bottom = block.effBottom
                    )
                )
                continue
            }

            // 3. Check if this block contains a class definition
            if (isClassBlock(blockText)) {
                classCandidateBlocks.add(block)
            }
        }

        // 4. Extrapolate Complete 7-Day Grid (Monday through Sunday)
        val uniqueDays = dayColumns.distinctBy { it.dayName }.sortedBy { it.centerX }
        val finalDays = mutableListOf<DayColumn>()
        finalDays.addAll(uniqueDays)

        if (finalDays.size in 2..6) {
            val mondayCol = finalDays.firstOrNull { it.dayName == "Monday" } ?: finalDays.first()
            val mondayIdx = DAYS.indexOf(mondayCol.dayName).coerceAtLeast(0)
            val otherCol = finalDays.last()
            val otherIdx = DAYS.indexOf(otherCol.dayName).coerceAtLeast(1)
            val step = if (otherIdx > mondayIdx) {
                (otherCol.centerX - mondayCol.centerX).toDouble() / (otherIdx - mondayIdx)
            } else {
                (page.width * 0.11).toDouble()
            }

            for (i in 0 until 7) {
                val dName = DAYS[i]
                if (finalDays.none { it.dayName.equals(dName, ignoreCase = true) }) {
                    val estimatedX = (mondayCol.centerX + (i - mondayIdx) * step).toInt()
                    finalDays.add(
                        DayColumn(
                            dayName = dName,
                            centerX = estimatedX,
                            bounds = Rect(estimatedX - 40, mondayCol.top, estimatedX + 40, mondayCol.bottom),
                            left = estimatedX - 40,
                            top = mondayCol.top,
                            right = estimatedX + 40,
                            bottom = mondayCol.bottom
                        )
                    )
                }
            }
        } else if (finalDays.isEmpty() && inheritedDays.isNotEmpty()) {
            finalDays.addAll(inheritedDays)
        } else if (finalDays.isEmpty()) {
            // Synthesize uniform 7-column grid across table width (Monday to Sunday)
            val tableLeft = (page.width * 0.18).toInt()
            val tableRight = (page.width * 0.96).toInt()
            val colWidth = (tableRight - tableLeft) / 7.0
            for (i in 0 until 7) {
                val cX = (tableLeft + (i + 0.5) * colWidth).toInt()
                finalDays.add(
                    DayColumn(
                        dayName = DAYS[i],
                        centerX = cX,
                        bounds = Rect(cX - 40, 0, cX + 40, 100),
                        left = cX - 40,
                        top = 0,
                        right = cX + 40,
                        bottom = 100
                    )
                )
            }
        }

        val sortedDays = finalDays.sortedBy { it.centerX }
        val sortedTimes = timeRows.sortedBy { it.centerY }

        val entries = mutableListOf<TimetableEntry>()

        if (sortedDays.isNotEmpty() && sortedTimes.isNotEmpty()) {
            for (block in classCandidateBlocks) {
                // Find nearest time row by Y coordinate
                val timeRow = sortedTimes.minByOrNull { abs(it.centerY - block.centerY) } ?: continue

                val parsedClasses = parseClassBlocksInText(
                    block = block,
                    sortedDays = sortedDays,
                    timeRow = timeRow,
                    userId = userId,
                    defaultSection = defaultSection,
                    catalog = catalog
                )
                entries.addAll(parsedClasses)
            }
        }

        return Pair(entries, sortedDays)
    }

    private fun isSectionCode(code: String): Boolean {
        val upper = code.uppercase()
        return upper.startsWith("K2") || upper.startsWith("9R") || upper.startsWith("KM") ||
               upper.startsWith("KO") || upper.startsWith("KK") || upper.startsWith("S:") ||
               upper.startsWith("SEC") || upper.startsWith("SECTION") || upper.contains("20240")
    }

    private fun isCabinOrDateCode(code: String): Boolean {
        val upper = code.uppercase()
        return upper.startsWith("CH") || upper.startsWith("WW") || upper.startsWith("AUG") ||
               upper.startsWith("SEP") || upper.startsWith("OCT") || upper.startsWith("NOV") ||
               upper.startsWith("DEC") || upper.startsWith("JAN") || upper.startsWith("FEB") ||
               upper.startsWith("MAR") || upper.startsWith("APR") || upper.startsWith("MAY") ||
               upper.startsWith("JUN") || upper.startsWith("JUL")
    }

    private fun isClassBlock(text: String): Boolean {
        if (text.contains("Project Work", ignoreCase = true) ||
            text.contains("Weekly Activities", ignoreCase = true) ||
            text.contains("Check Schedule", ignoreCase = true) ||
            text.contains("Schedule Below", ignoreCase = true) ||
            text.contains("Other Weekly", ignoreCase = true) ||
            text.contains("My Course", ignoreCase = true) ||
            text.contains("Time Table for VID", ignoreCase = true) ||
            text.contains("Course Title", ignoreCase = true) ||
            text.contains("Course Type", ignoreCase = true) ||
            text.contains("Legends :", ignoreCase = true) ||
            text.contains("Lovely Infotech", ignoreCase = true) ||
            text.contains("Printed On", ignoreCase = true) ||
            text.contains("Page No", ignoreCase = true) ||
            text.contains("Home Section", ignoreCase = true) ||
            text.contains("Last Updated", ignoreCase = true)
        ) {
            return false
        }

        val hasType = text.contains("Lecture", ignoreCase = true) ||
                text.contains("Practical", ignoreCase = true) ||
                text.contains("Tutorial", ignoreCase = true) ||
                text.contains("Seminar", ignoreCase = true) ||
                text.contains("Makeup", ignoreCase = true) ||
                text.contains("Workshop", ignoreCase = true) ||
                text.contains("Lab", ignoreCase = true)

        val hasCode = text.contains("C:", ignoreCase = true) ||
                COURSE_CODE_REGEX.containsMatchIn(text)

        val hasRoom = text.contains("R:", ignoreCase = true) ||
                ROOM_REGEX.containsMatchIn(text)

        val hasGroup = text.contains("G:", ignoreCase = true)
        val hasSection = text.contains("S:", ignoreCase = true)
        val hasTime = TIME_SLOT_REGEX.containsMatchIn(text)

        return (hasType && hasCode) || (hasCode && hasRoom) || (hasCode && (hasGroup || hasSection)) || (hasCode && hasTime)
    }

    /**
     * Parses all class entries within a block (handles blocks with multiple classes e.g. across Saturday morning).
     */
    private fun parseClassBlocksInText(
        block: ExtractedBlock,
        sortedDays: List<DayColumn>,
        timeRow: TimeRow,
        userId: String,
        defaultSection: String,
        catalog: Map<String, CourseInfo>
    ): List<TimetableEntry> {
        val entries = mutableListOf<TimetableEntry>()
        val blockText = block.text

        // Reject placeholder blocks
        if (blockText.contains("Project Work", ignoreCase = true) ||
            blockText.contains("Weekly Activities", ignoreCase = true) ||
            blockText.contains("Check Schedule", ignoreCase = true)) {
            return emptyList()
        }

        // Check if block explicitly mentions a day name
        val explicitDay = DAYS.firstOrNull { d ->
            Regex("""\b$d\b""", RegexOption.IGNORE_CASE).containsMatchIn(blockText)
        }

        // Split text if multiple classes exist in the same block
        val splitRegex = Regex("""(?=\b(?:Lecture|Practical|Tutorial|Seminar|Makeup|Workshop|Lab)\s*/|\bC:\s*[A-Z0-9]+)""", RegexOption.IGNORE_CASE)
        val parts = blockText.split(splitRegex).filter { it.isNotBlank() }

        if (parts.size > 1) {
            for (part in parts) {
                if (isClassBlock(part)) {
                    val partDay = if (explicitDay != null) {
                        explicitDay
                    } else {
                        val partStartIdx = blockText.indexOf(part).coerceAtLeast(0)
                        val partMidIdx = partStartIdx + part.length / 2.0
                        val relativeRatio = if (blockText.isNotEmpty()) partMidIdx / blockText.length.toDouble() else 0.5
                        val partCenterX = (block.effLeft + relativeRatio * block.width).toInt()
                        sortedDays.minByOrNull { abs(it.centerX - partCenterX) }?.dayName ?: "Monday"
                    }

                    val entry = parseSingleClass(part, partDay, timeRow.startTime, timeRow.endTime, userId, defaultSection, catalog)
                    if (entry != null) entries.add(entry)
                }
            }
        } else {
            val day = explicitDay ?: sortedDays.minByOrNull { abs(it.centerX - block.centerX) }?.dayName ?: "Monday"
            val entry = parseSingleClass(blockText, day, timeRow.startTime, timeRow.endTime, userId, defaultSection, catalog)
            if (entry != null) entries.add(entry)
        }

        return entries
    }

    private fun parseSingleClass(
        blockText: String,
        day: String,
        startTime: String,
        endTime: String,
        userId: String,
        defaultSection: String,
        catalog: Map<String, CourseInfo>
    ): TimetableEntry? {
        val singleLine = blockText.replace("\n", " ")
            .replace(Regex("""-\s+"""), "-")
            .replace(Regex("""\s+-"""), "-")
            .replace(Regex("""\s+"""), " ")
            .trim()

        // Reject placeholder lines
        if (singleLine.contains("Project Work", ignoreCase = true) ||
            singleLine.contains("Weekly Activities", ignoreCase = true) ||
            singleLine.contains("Check Schedule", ignoreCase = true)) {
            return null
        }

        // Extract Course Code (handles C:PEVS 01, C:PEAS 01, C:CSE 408, C:INT 252, C:CSE408, PEVS 01, etc.)
        val cPrefixMatch = Regex("""C:\s*([A-Z]{2,5})\s*([0-9]{2,4}[A-Z0-9]*)""", RegexOption.IGNORE_CASE).find(singleLine)
        val genericCMatch = Regex("""C:\s*([A-Z0-9]+)""", RegexOption.IGNORE_CASE).find(singleLine)
        val plainCodeMatch = COURSE_CODE_REGEX.find(singleLine)

        val courseCode = when {
            cPrefixMatch != null -> {
                val g1 = cPrefixMatch.groupValues[1].uppercase()
                val g2 = cPrefixMatch.groupValues[2].uppercase()
                g1 + g2
            }
            genericCMatch != null -> genericCMatch.groupValues[1].uppercase()
            plainCodeMatch != null -> {
                val candidate = (if (plainCodeMatch.groupValues.size >= 3 && plainCodeMatch.groupValues[1].isNotEmpty() && plainCodeMatch.groupValues[2].isNotEmpty()) {
                    plainCodeMatch.groupValues[1] + plainCodeMatch.groupValues[2]
                } else {
                    plainCodeMatch.groupValues[0].replace(" ", "")
                }).uppercase()

                // Reject sections, cabins, dates, and non-academic codes
                if (isSectionCode(candidate) || isCabinOrDateCode(candidate)) {
                    null
                } else if (candidate.length in 4..10 && Regex("""^[A-Z]{2,5}[0-9]{2,4}[A-Z0-9]*$""").matches(candidate)) {
                    // Valid academic course code across all departments (CSE, INT, PEA, BIO, MGT, LAW, ENG, etc.)
                    candidate
                } else {
                    null
                }
            }
            else -> return null
        } ?: return null

        if (isSectionCode(courseCode) || isCabinOrDateCode(courseCode)) {
            return null
        }

        // Extract Room (handles R: 33- 502, R: 34- 504, 38-906, etc.)
        val roomMatch = ROOM_REGEX.find(singleLine)
            ?: Regex("""(?:R:\s*|\bRoom\s*[:\s]*)([0-9]{2,3}\s*-\s*[0-9]{3}[A-Za-z0-9]*)""", RegexOption.IGNORE_CASE).find(singleLine)
            ?: Regex("""\b([0-9]{2,3}\s*-\s*[0-9]{3}[A-Za-z0-9]*)\b""").find(singleLine)
        val rawRoom = (if ((roomMatch?.groupValues?.size ?: 0) > 1) roomMatch?.groupValues?.get(1) else roomMatch?.value)?.trim() ?: ""
        val room = rawRoom.replace(" ", "")

        // Extract Class Type
        val typeMatch = TYPE_REGEX.find(singleLine)
        val type = when (typeMatch?.value?.uppercase()) {
            "LECTURE" -> "LEC"
            "PRACTICAL" -> "PRA"
            "TUTORIAL" -> "TUT"
            "LAB" -> "LAB"
            "SEMINAR" -> "SEM"
            "MAKEUP" -> "LEC"
            else -> "LEC"
        }

        // Extract Group
        val groupMatch = Regex("""G:\s*([^\s/]+)""", RegexOption.IGNORE_CASE).find(singleLine)
        val group = groupMatch?.groupValues?.get(1)?.trim() ?: ""

        // Extract Section (handles S:K 2 P 24 MC, S: 9 R 006, etc.)
        val sectionMatch = Regex("""S:\s*([A-Z0-9\s]+?)(?=\s*(?:/|\(|$))""", RegexOption.IGNORE_CASE).find(singleLine)
            ?: Regex("""S:\s*([^\s/]+)""", RegexOption.IGNORE_CASE).find(singleLine)
        val section = sectionMatch?.groupValues?.get(1)?.replace(" ", "")?.trim()?.ifBlank { null } ?: defaultSection

        // Check if block has embedded time slot (e.g. 09:20-10:10 AM)
        val embeddedTime = TIME_SLOT_REGEX.find(singleLine)
        val effStart = if (embeddedTime != null) normalizeTime(embeddedTime.groupValues[1], embeddedTime.groupValues[3]) else startTime
        val effEnd = if (embeddedTime != null) normalizeTime(embeddedTime.groupValues[2], embeddedTime.groupValues[3]) else endTime

        val courseInfo = catalog[courseCode]
        val courseName = DEFAULT_COURSE_CATALOG[courseCode]
            ?: courseInfo?.title?.takeIf { it.isNotBlank() && it != courseCode }
            ?: courseCode
        val faculty = courseInfo?.faculty ?: ""

        return TimetableEntry(
            id = UUID.randomUUID().toString(),
            userId = userId,
            courseCode = courseCode,
            courseName = courseName,
            day = day,
            startTime = effStart,
            endTime = effEnd,
            type = type,
            room = room,
            section = section,
            faculty = faculty,
            group = group
        ).normalized()
    }

    private fun parseTextualFallback(
        text: String,
        userId: String,
        defaultSection: String,
        catalog: Map<String, CourseInfo>
    ): List<TimetableEntry> {
        val entries = mutableListOf<TimetableEntry>()
        var currentDay = "Monday"
        var currentStartTime = "09:20"
        var currentEndTime = "10:10"

        // Exclude everything at or below the "My Course" catalog table
        val cleanTimetableText = text.substringBefore("My Course").substringBefore("Course Type :")

        val cleanText = cleanTimetableText.replace(Regex("""-\s+"""), "-").replace(Regex("""\s+-"""), "-")
        val blocks = cleanText.split(Regex("""(?=\b(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\b|\b\d{1,2}:\d{2}\s*[-–]\s*\d{1,2}:\d{2})"""))

        for (block in blocks) {
            val dayMatch = DAYS.firstOrNull { block.startsWith(it, ignoreCase = true) }
            if (dayMatch != null) {
                currentDay = dayMatch
            }

            val timeMatch = TIME_SLOT_REGEX.find(block)
            if (timeMatch != null) {
                currentStartTime = normalizeTime(timeMatch.groupValues[1], timeMatch.groupValues[3])
                currentEndTime = normalizeTime(timeMatch.groupValues[2], timeMatch.groupValues[3])
            }

            if (isClassBlock(block)) {
                val explicitDay = DAYS.firstOrNull { d ->
                    Regex("""\b$d\b""", RegexOption.IGNORE_CASE).containsMatchIn(block)
                } ?: currentDay

                val splitRegex = Regex("""(?=\b(?:Lecture|Practical|Tutorial|Seminar|Makeup|Workshop|Lab)\s*/|\bC:\s*[A-Z0-9]+)""", RegexOption.IGNORE_CASE)
                val parts = block.split(splitRegex).filter { it.isNotBlank() }
                if (parts.size > 1) {
                    for (part in parts) {
                        if (isClassBlock(part)) {
                            val entry = parseSingleClass(part, explicitDay, currentStartTime, currentEndTime, userId, defaultSection, catalog)
                            if (entry != null) entries.add(entry)
                        }
                    }
                } else {
                    val entry = parseSingleClass(block, explicitDay, currentStartTime, currentEndTime, userId, defaultSection, catalog)
                    if (entry != null) entries.add(entry)
                }
            }
        }
        return entries
    }

    private fun normalizeTime(raw: String, ampmHint: String = ""): String {
        val parts = raw.split(":")
        if (parts.size != 2) return raw
        var h = parts[0].toIntOrNull() ?: return raw
        val m = parts[1].padStart(2, '0')

        if (ampmHint.equals("PM", ignoreCase = true) && h < 12) {
            h += 12
        } else if (ampmHint.equals("AM", ignoreCase = true) && h == 12) {
            h = 0
        }
        return String.format("%02d:%s", h, m)
    }
}

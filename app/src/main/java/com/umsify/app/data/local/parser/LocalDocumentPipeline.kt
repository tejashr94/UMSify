package com.umsify.app.data.local.parser

import android.content.Context
import android.net.Uri
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.utils.TimeUtils

/**
 * End-to-end Local Document Pipeline with Multi-Pass OCR Convergence.
 * Zero Generative AI. 100% On-Device Rule-Based.
 *
 * Implements user requirement:
 * Reads document thrice with varying rendering resolutions.
 * If readings vary across the initial three passes, continues reading (adaptive passes)
 * until stability/convergence is reached.
 * Unites all detected classes across passes so NOT A SINGLE CLASS IS MISSED.
 */
class LocalDocumentPipeline(private val context: Context) {

    private val extractor = LocalDocumentExtractor(context)

    suspend fun processDocument(
        uri: Uri,
        mimeType: String,
        fileName: String,
        userId: String,
        hintType: String? = null
    ): ParseResult {
        val scales = listOf(1.0f, 1.3f, 1.15f)
        val passesResults = mutableListOf<ParseResult>()

        for (scale in scales) {
            val doc = try {
                extractor.extractDocument(uri, mimeType, fileName, scale)
            } catch (e: Exception) {
                null
            }
            if (doc != null) {
                passesResults.add(parseExtractedDocument(doc, userId, hintType))
            }
        }

        return convergeResults(
            passesResults = passesResults,
            userId = userId,
            hintType = hintType,
            fetchNextPass = { scale ->
                try {
                    val doc = extractor.extractDocument(uri, mimeType, fileName, scale)
                    parseExtractedDocument(doc, userId, hintType)
                } catch (_: Exception) {
                    null
                }
            }
        )
    }

    suspend fun processDocumentBytes(
        bytes: ByteArray,
        mimeType: String,
        fileName: String,
        userId: String,
        hintType: String? = null
    ): ParseResult {
        // Read document thrice with multi-resolution passes
        val initialScales = listOf(1.0f, 1.3f, 1.15f)
        val passesResults = mutableListOf<ParseResult>()

        for (scale in initialScales) {
            val doc = try {
                extractor.extractDocumentFromBytes(bytes, mimeType, fileName, scale)
            } catch (e: Exception) {
                null
            }
            if (doc != null) {
                passesResults.add(parseExtractedDocument(doc, userId, hintType))
            }
        }

        return convergeResults(
            passesResults = passesResults,
            userId = userId,
            hintType = hintType,
            fetchNextPass = { scale ->
                try {
                    val doc = extractor.extractDocumentFromBytes(bytes, mimeType, fileName, scale)
                    parseExtractedDocument(doc, userId, hintType)
                } catch (_: Exception) {
                    null
                }
            }
        )
    }

    /**
     * Converges multi-pass results:
     * - Compares passes to check if readings vary.
     * - If readings vary, executes additional reading passes (up to 5 max) until data stabilizes.
     * - Unites all unique discovered entries across all passes to ensure 100% completeness.
     */
    private suspend fun convergeResults(
        passesResults: MutableList<ParseResult>,
        userId: String,
        hintType: String?,
        fetchNextPass: suspend (Float) -> ParseResult?
    ): ParseResult {
        if (passesResults.isEmpty()) {
            return ParseResult.Error("Failed to extract document text. Please upload a clear document.")
        }

        // Determine if primary target is Timetable or Exam
        val isTimetable = passesResults.any { it is ParseResult.TimetableParsed && it.entries.isNotEmpty() } ||
                hintType.equals("TIMETABLE", ignoreCase = true)
        val isExam = !isTimetable && (passesResults.any { it is ParseResult.ExamParsed && it.exams.isNotEmpty() } ||
                hintType.equals("EXAM", ignoreCase = true))

        if (isTimetable) {
            val additionalScales = listOf(1.4f, 1.25f)
            var currentCandidateCount = passesResults.mapNotNull { (it as? ParseResult.TimetableParsed)?.entries?.size }.maxOrNull() ?: 0

            // If readings vary across the initial 3 passes, continue reading until consensus/stability
            val initialSizes = passesResults.mapNotNull { (it as? ParseResult.TimetableParsed)?.entries?.size }
            val readingsVary = initialSizes.distinct().size > 1

            if (readingsVary) {
                for (extraScale in additionalScales) {
                    val extraResult = fetchNextPass(extraScale)
                    if (extraResult != null) {
                        passesResults.add(extraResult)
                        val newMax = passesResults.mapNotNull { (it as? ParseResult.TimetableParsed)?.entries?.size }.maxOrNull() ?: 0
                        if (newMax == currentCandidateCount) {
                            // Stabilized, no more classes found
                            break
                        }
                        currentCandidateCount = newMax
                    }
                }
            }

            // Union all timetable entries across ALL passes
            val allTimetableEntries = mutableListOf<TimetableEntry>()
            var bestVid = ""
            var bestHomeSection = ""
            val combinedCatalog = mutableMapOf<String, CourseInfo>()

            for (res in passesResults) {
                if (res is ParseResult.TimetableParsed) {
                    allTimetableEntries.addAll(res.entries)
                    if (res.studentVid.isNotBlank() && bestVid.isBlank()) {
                        bestVid = res.studentVid
                    }
                    if (res.homeSection.isNotBlank() && bestHomeSection.isBlank()) {
                        bestHomeSection = res.homeSection
                    }
                    combinedCatalog.putAll(res.courseCatalog)
                }
            }

            if (allTimetableEntries.isNotEmpty()) {
                val merged = TimeUtils.mergeAndDeduplicateTimetable(allTimetableEntries)
                return ParseResult.TimetableParsed(
                    entries = merged,
                    studentVid = bestVid,
                    homeSection = bestHomeSection,
                    courseCatalog = combinedCatalog
                )
            }
        } else if (isExam) {
            val additionalScales = listOf(1.4f, 1.25f)
            var currentCandidateCount = passesResults.mapNotNull { (it as? ParseResult.ExamParsed)?.exams?.size }.maxOrNull() ?: 0

            val initialSizes = passesResults.mapNotNull { (it as? ParseResult.ExamParsed)?.exams?.size }
            val readingsVary = initialSizes.distinct().size > 1

            if (readingsVary) {
                for (extraScale in additionalScales) {
                    val extraResult = fetchNextPass(extraScale)
                    if (extraResult != null) {
                        passesResults.add(extraResult)
                        val newMax = passesResults.mapNotNull { (it as? ParseResult.ExamParsed)?.exams?.size }.maxOrNull() ?: 0
                        if (newMax == currentCandidateCount) {
                            break
                        }
                        currentCandidateCount = newMax
                    }
                }
            }

            val allExamEntries = mutableListOf<ExamEntry>()
            var bestRegNo = ""
            var bestStudentName = ""

            for (res in passesResults) {
                if (res is ParseResult.ExamParsed) {
                    allExamEntries.addAll(res.exams)
                    if (res.registrationNumber.isNotBlank() && bestRegNo.isBlank()) {
                        bestRegNo = res.registrationNumber
                    }
                    if (res.studentName.isNotBlank() && bestStudentName.isBlank()) {
                        bestStudentName = res.studentName
                    }
                }
            }

            if (allExamEntries.isNotEmpty()) {
                val uniqueExams = allExamEntries.distinctBy {
                    "${it.courseCode.uppercase()}|${it.examDate}|${it.startTime}"
                }
                return ParseResult.ExamParsed(
                    exams = uniqueExams,
                    registrationNumber = bestRegNo,
                    studentName = bestStudentName
                )
            }
        }

        // Fallback: Return best non-error result or first error
        return passesResults.firstOrNull { it !is ParseResult.Error }
            ?: passesResults.firstOrNull()
            ?: ParseResult.Error("Unable to recognize this document format.")
    }

    private fun parseExtractedDocument(
        doc: ExtractedDocument,
        userId: String,
        hintType: String? = null
    ): ParseResult {
        // If user explicitly chose Exam Date Sheet:
        if (hintType.equals("EXAM", ignoreCase = true)) {
            val examRes = LpuExamParser.parse(doc, userId)
            if (examRes is ParseResult.ExamParsed && examRes.exams.isNotEmpty()) {
                return examRes
            }
        } else if (hintType.equals("TIMETABLE", ignoreCase = true)) {
            val ttRes = LpuTimetableParser.parse(doc, userId)
            if (ttRes is ParseResult.TimetableParsed && ttRes.entries.isNotEmpty()) {
                return ttRes
            }
        }

        val classification = DocumentClassifier.classify(doc)

        return when (classification) {
            DocumentClassification.TIMETABLE -> {
                val ttAttempt = LpuTimetableParser.parse(doc, userId)
                if (ttAttempt is ParseResult.TimetableParsed && ttAttempt.entries.isNotEmpty()) {
                    ttAttempt
                } else {
                    LpuExamParser.parse(doc, userId)
                }
            }
            DocumentClassification.EXAM_DATE_SHEET -> {
                val examAttempt = LpuExamParser.parse(doc, userId)
                if (examAttempt is ParseResult.ExamParsed && examAttempt.exams.isNotEmpty()) {
                    examAttempt
                } else {
                    LpuTimetableParser.parse(doc, userId)
                }
            }
            DocumentClassification.UNKNOWN -> {
                val timetableAttempt = LpuTimetableParser.parse(doc, userId)
                if (timetableAttempt is ParseResult.TimetableParsed && timetableAttempt.entries.isNotEmpty()) {
                    timetableAttempt
                } else {
                    val examAttempt = LpuExamParser.parse(doc, userId)
                    if (examAttempt is ParseResult.ExamParsed && examAttempt.exams.isNotEmpty()) {
                        examAttempt
                    } else {
                        ParseResult.Error("Unable to recognize this document format.")
                    }
                }
            }
        }
    }
}

package com.umsify.app.data.local.parser

/**
 * Deterministic, rule-based document classifier.
 * Never uses AI or hallucinations.
 */
object DocumentClassifier {

    fun classify(document: ExtractedDocument): DocumentClassification {
        val text = document.combinedText

        val timetableKeywords = listOf(
            "time table",
            "home section",
            "my course",
            "timing",
            "monday",
            "tuesday",
            "wednesday",
            "thursday",
            "friday",
            "lecture",
            "practical",
            "tutorial"
        )

        val examKeywords = listOf(
            "examination hall ticket",
            "admit card",
            "exam timing",
            "exam date",
            "exam description",
            "hall ticket",
            "mid term",
            "end term",
            "controller of examination",
            "objective type mid term",
            "theory end term"
        )

        var timetableScore = 0
        for (kw in timetableKeywords) {
            if (text.contains(kw, ignoreCase = true)) {
                timetableScore++
            }
        }

        var examScore = 0
        for (kw in examKeywords) {
            if (text.contains(kw, ignoreCase = true)) {
                examScore++
            }
        }

        return when {
            examScore >= 2 && examScore > timetableScore -> DocumentClassification.EXAM_DATE_SHEET
            timetableScore >= 2 -> DocumentClassification.TIMETABLE
            examScore >= 1 -> DocumentClassification.EXAM_DATE_SHEET
            else -> DocumentClassification.UNKNOWN
        }
    }
}

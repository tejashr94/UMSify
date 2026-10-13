package com.umsify.app.data.local.parser

import android.graphics.Rect
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.data.model.TimetableEntry

enum class DocumentClassification {
    TIMETABLE,
    EXAM_DATE_SHEET,
    UNKNOWN
}

data class ExtractedBlock(
    val text: String,
    val bounds: Rect = Rect(),
    val pageIndex: Int = 0,
    val left: Int = bounds.left,
    val top: Int = bounds.top,
    val right: Int = bounds.right,
    val bottom: Int = bounds.bottom
) {
    val effLeft: Int get() = if (right > left) left else bounds.left
    val effTop: Int get() = if (bottom > top) top else bounds.top
    val effRight: Int get() = if (right > left) right else bounds.right
    val effBottom: Int get() = if (bottom > top) bottom else bounds.bottom

    val centerX: Int get() = (effLeft + effRight) / 2
    val centerY: Int get() = (effTop + effBottom) / 2
    val width: Int get() = effRight - effLeft
    val height: Int get() = effBottom - effTop

    constructor(text: String, left: Int, top: Int, right: Int, bottom: Int, pageIndex: Int = 0) : this(
        text = text,
        bounds = Rect(left, top, right, bottom),
        pageIndex = pageIndex,
        left = left,
        top = top,
        right = right,
        bottom = bottom
    )
}

data class ExtractedPage(
    val pageIndex: Int,
    val fullText: String,
    val blocks: List<ExtractedBlock>,
    val width: Int,
    val height: Int
)

data class ExtractedDocument(
    val pages: List<ExtractedPage>,
    val combinedText: String
)

data class CourseInfo(
    val code: String,
    val title: String,
    val type: String = "LEC",
    val faculty: String = "",
    val credits: Int = 0
)

data class DayColumn(
    val dayName: String,
    val centerX: Int,
    val bounds: Rect = Rect(),
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

data class TimeRow(
    val rawText: String,
    val startTime: String,
    val endTime: String,
    val centerY: Int,
    val bounds: Rect = Rect(),
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0
)

sealed class ParseResult {
    data class TimetableParsed(
        val entries: List<TimetableEntry>,
        val studentVid: String = "",
        val homeSection: String = "",
        val courseCatalog: Map<String, CourseInfo> = emptyMap()
    ) : ParseResult()

    data class ExamParsed(
        val exams: List<ExamEntry>,
        val registrationNumber: String = "",
        val studentName: String = "",
        val program: String = ""
    ) : ParseResult()

    data class Error(val message: String) : ParseResult()
}

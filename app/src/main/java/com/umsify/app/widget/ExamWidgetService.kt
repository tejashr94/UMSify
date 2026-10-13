package com.umsify.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.umsify.app.R
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.local.parser.LpuTimetableParser
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * ExamWidgetService — RemoteViewsService for displaying all other exams in sequence below the top highlighted exam.
 */
class ExamWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return ExamRemoteViewsFactory(applicationContext)
    }
}

class ExamRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var displayExams: List<ExamEntry> = emptyList()

    override fun onCreate() {
        loadData()
    }

    override fun onDataSetChanged() {
        loadData()
    }

    private fun loadData() {
        val sessionManager = SessionManager.getInstance(context)
        var userId = sessionManager.getUserId()
        if (userId.isBlank()) {
            userId = sessionManager.getSession()?.userId ?: ""
        }

        displayExams = runBlocking(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance(context)
                var exams = if (userId.isNotBlank()) {
                    db.examDao().getExams(userId)
                } else {
                    emptyList()
                }

                if (exams.isEmpty()) {
                    exams = db.examDao().getAllExams()
                }

                // Deduplicate and sort chronologically by date and start time
                val uniqueExams = exams.distinctBy { "${it.courseCode.uppercase()}|${it.examDate}|${it.startTime}" }
                    .sortedWith(compareBy({ it.examDate }, { it.startTime }))

                if (uniqueExams.size > 1) {
                    val upcoming = uniqueExams.firstOrNull { TimeUtils.daysUntil(it.examDate) >= 0 } ?: uniqueExams.first()
                    // All other exams below the top upcoming exam
                    val remaining = uniqueExams.filterNot {
                        it.courseCode.equals(upcoming.courseCode, ignoreCase = true) &&
                        it.examDate == upcoming.examDate &&
                        it.startTime == upcoming.startTime
                    }
                    if (remaining.isNotEmpty()) remaining else uniqueExams
                } else {
                    uniqueExams
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override fun onDestroy() {
        displayExams = emptyList()
    }

    override fun getCount(): Int = displayExams.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position < 0 || position >= displayExams.size) {
            return RemoteViews(context.packageName, R.layout.item_widget_exam_row)
        }

        val exam = displayExams[position]
        val views = RemoteViews(context.packageName, R.layout.item_widget_exam_row)

        views.setTextViewText(R.id.tvItemExamCourseCode, exam.courseCode)
        val cleanName = if (exam.courseName.isNotBlank() && exam.courseName != exam.courseCode) {
            exam.courseName
        } else {
            LpuTimetableParser.DEFAULT_COURSE_CATALOG[exam.courseCode] ?: exam.courseCode
        }
        views.setTextViewText(R.id.tvItemExamCourseName, cleanName)
        views.setTextViewText(R.id.tvItemExamType, exam.examType.ifBlank { "Theory" })

        val roomDisplay = if (exam.room.isNotBlank()) "🚪 ${exam.room}" else "🚪 —"
        views.setTextViewText(R.id.tvItemExamRoom, roomDisplay)

        views.setTextViewText(R.id.tvItemExamDate, TimeUtils.formatExamDate(exam.examDate))
        views.setTextViewText(
            R.id.tvItemExamTiming,
            "• ${TimeUtils.formatTo12h(exam.startTime)} - ${TimeUtils.formatTo12h(exam.endTime)}"
        )

        val daysLeft = TimeUtils.daysUntil(exam.examDate)
        when {
            daysLeft == 0 -> {
                views.setTextViewText(R.id.tvItemExamCountdown, "TODAY!")
                views.setTextColor(R.id.tvItemExamCountdown, Color.parseColor("#00FF66"))
            }
            daysLeft == 1 -> {
                views.setTextViewText(R.id.tvItemExamCountdown, "1 DAY LEFT")
                views.setTextColor(R.id.tvItemExamCountdown, Color.parseColor("#FFE600"))
            }
            daysLeft > 1 -> {
                views.setTextViewText(R.id.tvItemExamCountdown, "$daysLeft DAYS")
                views.setTextColor(R.id.tvItemExamCountdown, Color.parseColor("#00E5FF"))
            }
            else -> {
                views.setTextViewText(R.id.tvItemExamCountdown, "CONCLUDED")
                views.setTextColor(R.id.tvItemExamCountdown, Color.parseColor("#80FFFFFF"))
            }
        }

        // Fill-in Intent to open ExamActivity when row item is clicked
        val fillInIntent = Intent().apply {
            putExtra("courseCode", exam.courseCode)
            putExtra("examDate", exam.examDate)
        }
        views.setOnClickFillInIntent(R.id.itemWidgetExamRoot, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}

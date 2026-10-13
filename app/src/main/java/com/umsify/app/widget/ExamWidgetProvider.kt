package com.umsify.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.umsify.app.R
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.local.parser.LpuTimetableParser
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.ui.exams.ExamActivity
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * ExamWidgetProvider — Native Android AppWidget.
 * Reads directly from Room cache.
 * Configured for 3x2 app icon size.
 * Displays:
 * 1. Header with total exams count badge
 * 2. Upcoming exam on top highlighted inside a clean bordered card
 * 3. Below it, all other exams in sequence in a scrollable list with item borders
 * 4. Tapping widget or items opens the ExamActivity.
 */
class ExamWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                for (appWidgetId in appWidgetIds) {
                    renderWidget(appContext, appWidgetManager, appWidgetId)
                }
            } catch (_: Exception) {
            } finally {
                try { pendingResult.finish() } catch (_: Exception) {}
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                renderWidget(appContext, appWidgetManager, appWidgetId)
            } catch (_: Exception) {
            } finally {
                try { pendingResult.finish() } catch (_: Exception) {}
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action
        if (action == Intent.ACTION_TIME_TICK ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, javaClass)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            if (appWidgetIds.isNotEmpty()) {
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        for (id in appWidgetIds) {
                            renderWidget(context.applicationContext, appWidgetManager, id)
                        }
                    } catch (_: Exception) {
                    } finally {
                        try { pendingResult.finish() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, ExamWidgetProvider::class.java)
            val allWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
            val intent = Intent(context, ExamWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, allWidgetIds)
            }
            context.sendBroadcast(intent)
        }

        suspend fun renderWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_exam)
            val sessionManager = SessionManager.getInstance(context)
            var userId = sessionManager.getUserId()
            if (userId.isBlank()) {
                userId = sessionManager.getSession()?.userId ?: ""
            }

            // Open ExamActivity on widget header / root / card tap
            val appIntent = Intent(context, ExamActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetExamRoot, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetExamHeader, pendingIntent)
            views.setOnClickPendingIntent(R.id.cardFirstExam, pendingIntent)

            // Connect RemoteViewsService for the scrollable list of all other exams
            val serviceIntent = Intent(context, ExamWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.lvWidgetExams, serviceIntent)
            views.setEmptyView(R.id.lvWidgetExams, R.id.layoutExamEmpty)

            // Set list item click template to open ExamActivity
            val itemClickIntent = Intent(context, ExamActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val itemClickPendingIntent = PendingIntent.getActivity(
                context,
                2,
                itemClickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.lvWidgetExams, itemClickPendingIntent)

            val db = AppDatabase.getInstance(context)
            var allExams = if (userId.isNotBlank()) {
                db.examDao().getExams(userId)
            } else {
                emptyList()
            }

            if (allExams.isEmpty()) {
                val anyExams = db.examDao().getAllExams()
                if (anyExams.isNotEmpty()) {
                    allExams = anyExams
                }
            }

            val uniqueExams = allExams
                .distinctBy { "${it.courseCode.uppercase()}|${it.examDate}|${it.startTime}" }
                .sortedWith(compareBy({ it.examDate }, { it.startTime }))
            val count = uniqueExams.size

            if (count > 0) {
                // Find upcoming exam (earliest with daysUntil >= 0, or first exam)
                val upcomingExam = uniqueExams.firstOrNull { TimeUtils.daysUntil(it.examDate) >= 0 } ?: uniqueExams.first()

                // Highlighted top card for upcoming exam
                views.setViewVisibility(R.id.cardFirstExam, View.VISIBLE)
                views.setTextViewText(R.id.tvFirstExamCode, upcomingExam.courseCode)

                val cleanName = if (upcomingExam.courseName.isNotBlank() && upcomingExam.courseName != upcomingExam.courseCode) {
                    upcomingExam.courseName
                } else {
                    LpuTimetableParser.DEFAULT_COURSE_CATALOG[upcomingExam.courseCode] ?: upcomingExam.courseCode
                }
                views.setTextViewText(R.id.tvFirstExamName, cleanName)

                val roomText = if (upcomingExam.room.isNotBlank()) "🚪 ${upcomingExam.room}" else "🚪 —"
                views.setTextViewText(R.id.tvFirstExamRoom, roomText)

                val daysLeft = TimeUtils.daysUntil(upcomingExam.examDate)
                when {
                    daysLeft == 0 -> {
                        views.setTextViewText(R.id.tvFirstExamBadge, "TODAY 🎯")
                        views.setTextColor(R.id.tvFirstExamBadge, Color.parseColor("#00FF66"))
                        views.setTextViewText(R.id.tvFirstExamCountdown, "TODAY!")
                        views.setTextColor(R.id.tvFirstExamCountdown, Color.parseColor("#00FF66"))
                    }
                    daysLeft == 1 -> {
                        views.setTextViewText(R.id.tvFirstExamBadge, "TOMORROW 🎯")
                        views.setTextColor(R.id.tvFirstExamBadge, Color.parseColor("#FFE600"))
                        views.setTextViewText(R.id.tvFirstExamCountdown, "1 DAY LEFT")
                        views.setTextColor(R.id.tvFirstExamCountdown, Color.parseColor("#FFE600"))
                    }
                    daysLeft > 1 -> {
                        views.setTextViewText(R.id.tvFirstExamBadge, "FIRST EXAM 🎯")
                        views.setTextColor(R.id.tvFirstExamBadge, Color.parseColor("#FFE600"))
                        views.setTextViewText(R.id.tvFirstExamCountdown, "$daysLeft DAYS")
                        views.setTextColor(R.id.tvFirstExamCountdown, Color.parseColor("#00E5FF"))
                    }
                    else -> {
                        views.setTextViewText(R.id.tvFirstExamBadge, "EXAM 🎯")
                        views.setTextColor(R.id.tvFirstExamBadge, Color.parseColor("#00E5FF"))
                        views.setTextViewText(R.id.tvFirstExamCountdown, "DONE")
                        views.setTextColor(R.id.tvFirstExamCountdown, Color.parseColor("#80FFFFFF"))
                    }
                }

                val dateStr = TimeUtils.formatExamDate(upcomingExam.examDate)
                val timeStr = if (upcomingExam.startTime.isNotBlank()) " • ${TimeUtils.formatTo12h(upcomingExam.startTime)}" else ""
                views.setTextViewText(R.id.tvFirstExamDateTime, "🗓️ $dateStr$timeStr")

                views.setViewVisibility(R.id.layoutExamEmpty, View.GONE)
                views.setViewVisibility(R.id.lvWidgetExams, View.VISIBLE)
                views.setTextViewText(R.id.tvExamCountdown, "$count ${if (count == 1) "Exam" else "Exams"} ↕")
            } else {
                views.setViewVisibility(R.id.cardFirstExam, View.GONE)
                views.setViewVisibility(R.id.lvWidgetExams, View.GONE)
                views.setViewVisibility(R.id.layoutExamEmpty, View.VISIBLE)
                views.setTextViewText(R.id.tvExamCountdown, "0 Exams")
            }

            // Notify ListView to refresh items
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lvWidgetExams)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

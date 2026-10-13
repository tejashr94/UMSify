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
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.ui.home.HomeActivity
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * TimetableWidgetProvider — Native Android AppWidget.
 * Reads directly from Room cache.
 * Configured for 3x2 app icon size.
 * Displays:
 * 1. Current Date & total count in header
 * 2. Upcoming / Ongoing class highlighted on top inside a clean bordered card
 * 3. Below it, the whole timetable in a scrollable list with item borders
 * 4. Tapping widget or items opens the UMSify app.
 */
open class TimetableWidgetProvider : AppWidgetProvider() {

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
                pendingResult.finish()
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
            val thisWidget = ComponentName(context, TimetableWidgetProvider::class.java)
            val legacyWidget = ComponentName(context, TimetableWidget::class.java)
            val allWidgetIds = (appWidgetManager.getAppWidgetIds(thisWidget) + appWidgetManager.getAppWidgetIds(legacyWidget)).distinct().toIntArray()

            val intent = Intent(context, TimetableWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, allWidgetIds)
            }
            context.sendBroadcast(intent)
        }

        suspend fun renderWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_timetable)
            val sessionManager = SessionManager.getInstance(context)
            var userId = sessionManager.getUserId()
            if (userId.isBlank()) {
                userId = sessionManager.getSession()?.userId ?: ""
            }

            // Open app on header / root tap / card tap
            val appIntent = Intent(context, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetHeader, pendingIntent)
            views.setOnClickPendingIntent(R.id.cardOngoingClass, pendingIntent)

            // Current date formatting
            val now = Calendar.getInstance()
            val dayFormat = SimpleDateFormat("EEEE", Locale.US)
            val todayDayName = dayFormat.format(now.time)
            val dateFormat = SimpleDateFormat("EEEE, dd MMM", Locale.US)
            views.setTextViewText(R.id.tvWidgetDate, dateFormat.format(now.time))

            // Connect RemoteViewsService for the scrollable list of classes
            val serviceIntent = Intent(context, TimetableWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.lvWidgetClasses, serviceIntent)
            views.setEmptyView(R.id.lvWidgetClasses, R.id.layoutWidgetEmpty)

            // Set list item click template to open app
            val itemClickIntent = Intent(context, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val itemClickPendingIntent = PendingIntent.getActivity(
                context,
                1,
                itemClickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            views.setPendingIntentTemplate(R.id.lvWidgetClasses, itemClickPendingIntent)

            val db = AppDatabase.getInstance(context)
            var allUserClasses = if (userId.isNotBlank()) {
                db.timetableDao().getTimetable(userId)
            } else {
                emptyList()
            }

            if (allUserClasses.isEmpty()) {
                val anyClasses = db.timetableDao().getAllTimetable()
                if (anyClasses.isNotEmpty()) {
                    allUserClasses = anyClasses
                }
            }

            allUserClasses = TimeUtils.mergeAndDeduplicateTimetable(allUserClasses)

            if (allUserClasses.isEmpty()) {
                views.setViewVisibility(R.id.cardOngoingClass, View.GONE)
                views.setViewVisibility(R.id.lvWidgetClasses, View.GONE)
                views.setViewVisibility(R.id.layoutWidgetEmpty, View.VISIBLE)
                views.setTextViewText(R.id.tvEmptyTitle, "NO TIMETABLE YET")
                views.setTextViewText(R.id.tvEmptySubtitle, "Import your timetable in UMSify.")
                views.setTextViewText(R.id.tvWidgetClassCount, "0 Classes")
                appWidgetManager.updateAppWidget(appWidgetId, views)
                return
            } else {
                views.setViewVisibility(R.id.layoutWidgetEmpty, View.GONE)
            }

            val currentTimeStr = SimpleDateFormat("HH:mm", Locale.US).format(now.time)
            val todayClasses = allUserClasses
                .filter { it.day.equals(todayDayName, ignoreCase = true) }
                .sortedBy { it.startTime }

            // Find ongoing or next upcoming class
            var topClass: TimetableEntry? = null
            var badgeText = "UPCOMING ⏳"
            var badgeColor = "#00E5FF"

            // 1. Is there a class currently ongoing today?
            val ongoingToday = todayClasses.firstOrNull {
                currentTimeStr >= it.startTime && currentTimeStr <= it.endTime
            }
            if (ongoingToday != null) {
                topClass = ongoingToday
                badgeText = "NOW 🟢"
                badgeColor = "#00FF66"
            } else {
                // 2. Is there an upcoming class later today?
                val nextToday = todayClasses.firstOrNull { it.startTime > currentTimeStr }
                if (nextToday != null) {
                    topClass = nextToday
                    badgeText = "UPCOMING ⏳"
                    badgeColor = "#00E5FF"
                } else {
                    // 3. Find next class in upcoming days
                    val dayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                    val todayIndex = dayNames.indexOfFirst { it.equals(todayDayName, ignoreCase = true) }.let { if (it == -1) 0 else it }
                    for (offset in 1..7) {
                        val targetDay = dayNames[(todayIndex + offset) % 7]
                        val dayClasses = allUserClasses.filter { it.day.equals(targetDay, ignoreCase = true) }.sortedBy { it.startTime }
                        if (dayClasses.isNotEmpty()) {
                            topClass = dayClasses.first()
                            val shortDay = targetDay.take(3).uppercase()
                            badgeText = "$shortDay ⏳"
                            badgeColor = "#FFE600"
                            break
                        }
                    }
                    if (topClass == null) {
                        topClass = allUserClasses.firstOrNull()
                    }
                }
            }

            // Populate top highlighted card with clean borders
            if (topClass != null) {
                views.setViewVisibility(R.id.cardOngoingClass, View.VISIBLE)
                views.setTextViewText(R.id.tvOngoingBadge, badgeText)
                views.setTextColor(R.id.tvOngoingBadge, Color.parseColor(badgeColor))
                views.setTextViewText(R.id.tvCurrentCourseCode, topClass.courseCode)
                val cleanName = if (topClass.courseName.isNotBlank() && topClass.courseName != topClass.courseCode) {
                    topClass.courseName
                } else {
                    LpuTimetableParser.DEFAULT_COURSE_CATALOG[topClass.courseCode] ?: topClass.courseCode
                }
                views.setTextViewText(R.id.tvCurrentCourseName, cleanName)
                val roomText = if (topClass.room.isNotBlank()) "🚪 ${topClass.room}" else "🚪 —"
                views.setTextViewText(R.id.tvCurrentRoom, roomText)

                val timingText = if (topClass.day.equals(todayDayName, ignoreCase = true)) {
                    "${TimeUtils.formatTo12h(topClass.startTime)} - ${TimeUtils.formatTo12h(topClass.endTime)}"
                } else {
                    "${topClass.day.take(3)} • ${TimeUtils.formatTo12h(topClass.startTime)} - ${TimeUtils.formatTo12h(topClass.endTime)}"
                }
                views.setTextViewText(R.id.tvCurrentTime, timingText)
            } else {
                views.setViewVisibility(R.id.cardOngoingClass, View.GONE)
            }

            // Header badge showing class count
            val totalCount = allUserClasses.size
            val todayCount = todayClasses.size
            if (todayCount > 0) {
                views.setTextViewText(R.id.tvWidgetClassCount, "$todayCount Today • $totalCount ↕")
            } else {
                views.setTextViewText(R.id.tvWidgetClassCount, "$totalCount Classes ↕")
            }

            views.setViewVisibility(R.id.lvWidgetClasses, View.VISIBLE)

            // Notify ListView to refresh items
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.lvWidgetClasses)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}

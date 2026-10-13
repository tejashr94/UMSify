package com.umsify.app.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.umsify.app.R
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.local.parser.LpuTimetableParser
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class TimetableWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        return TimetableRemoteViewsFactory(applicationContext)
    }
}

class TimetableRemoteViewsFactory(
    private val context: Context
) : RemoteViewsService.RemoteViewsFactory {

    private var scheduleItems: List<TimetableEntry> = emptyList()
    private var todayDayName: String = ""

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

        todayDayName = SimpleDateFormat("EEEE", Locale.US).format(Calendar.getInstance().time)

        scheduleItems = runBlocking(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance(context)
                var all = if (userId.isNotBlank()) {
                    db.timetableDao().getTimetable(userId)
                } else {
                    emptyList()
                }

                if (all.isEmpty()) {
                    all = db.timetableDao().getAllTimetable()
                }

                val merged = TimeUtils.mergeAndDeduplicateTimetable(all)

                // Sort weekly schedule starting with Today's classes, followed by subsequent days
                val dayOrder = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
                val todayIdx = dayOrder.indexOfFirst { it.equals(todayDayName, ignoreCase = true) }.let { if (it == -1) 0 else it }

                // Group by day and sort starting from today
                val sortedDays = (0 until 7).map { dayOrder[(todayIdx + it) % 7] }

                val orderedList = mutableListOf<TimetableEntry>()
                for (day in sortedDays) {
                    val classesForDay = merged.filter { it.day.equals(day, ignoreCase = true) }
                        .sortedBy { it.startTime }
                    orderedList.addAll(classesForDay)
                }

                if (orderedList.isNotEmpty()) orderedList else merged
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    override fun onDestroy() {
        scheduleItems = emptyList()
    }

    override fun getCount(): Int = scheduleItems.size

    override fun getViewAt(position: Int): RemoteViews {
        if (position < 0 || position >= scheduleItems.size) {
            return RemoteViews(context.packageName, R.layout.item_widget_timetable_row)
        }

        val cls = scheduleItems[position]
        val views = RemoteViews(context.packageName, R.layout.item_widget_timetable_row)

        views.setTextViewText(R.id.tvItemCourseCode, cls.courseCode)
        val cleanName = if (cls.courseName.isNotBlank() && cls.courseName != cls.courseCode) {
            cls.courseName
        } else {
            LpuTimetableParser.DEFAULT_COURSE_CATALOG[cls.courseCode] ?: cls.courseCode
        }
        views.setTextViewText(R.id.tvItemCourseName, cleanName)

        val roomDisplay = if (cls.room.isNotBlank()) "🚪 ${cls.room}" else "🚪 —"
        views.setTextViewText(R.id.tvItemRoom, roomDisplay)

        val isToday = cls.day.equals(todayDayName, ignoreCase = true)
        val timeFormatted = "${TimeUtils.formatTo12h(cls.startTime)} - ${TimeUtils.formatTo12h(cls.endTime)}"

        val currentTime = SimpleDateFormat("HH:mm", Locale.US).format(Calendar.getInstance().time)

        if (isToday) {
            views.setTextViewText(R.id.tvItemTime, timeFormatted)
            when {
                currentTime > cls.endTime -> {
                    views.setTextViewText(R.id.tvItemStatusBadge, "DONE ✓")
                    views.setTextColor(R.id.tvItemStatusBadge, Color.parseColor("#80FFFFFF"))
                }
                currentTime >= cls.startTime && currentTime <= cls.endTime -> {
                    views.setTextViewText(R.id.tvItemStatusBadge, "NOW 🟢")
                    views.setTextColor(R.id.tvItemStatusBadge, Color.parseColor("#00FF66"))
                }
                else -> {
                    views.setTextViewText(R.id.tvItemStatusBadge, "UPCOMING ⏳")
                    views.setTextColor(R.id.tvItemStatusBadge, Color.parseColor("#00E5FF"))
                }
            }
        } else {
            val shortDay = cls.day.take(3).uppercase()
            views.setTextViewText(R.id.tvItemTime, "${cls.day.take(3)} • $timeFormatted")
            views.setTextViewText(R.id.tvItemStatusBadge, shortDay)
            views.setTextColor(R.id.tvItemStatusBadge, Color.parseColor("#FFE600"))
        }

        // Fill-in Intent to open app on item click
        val fillInIntent = Intent().apply {
            putExtra("courseCode", cls.courseCode)
            putExtra("day", cls.day)
        }
        views.setOnClickFillInIntent(R.id.itemWidgetRoot, fillInIntent)

        return views
    }

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewTypeCount(): Int = 1

    override fun getItemId(position: Int): Long = position.toLong()

    override fun hasStableIds(): Boolean = true
}

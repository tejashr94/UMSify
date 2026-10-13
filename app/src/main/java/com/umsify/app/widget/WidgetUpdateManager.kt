package com.umsify.app.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.utils.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * WidgetUpdateManager — Manages widget refreshes and background synchronization
 * matching Section 22 and 26.
 */
object WidgetUpdateManager {

    private const val WORK_NAME_PERIODIC_SYNC = "umsify_periodic_sync_work"

    /**
     * Refreshes both Timetable and Exam widgets immediately.
     */
    fun updateAllWidgets(context: Context) {
        TimetableWidgetProvider.updateAll(context)
        ExamWidgetProvider.updateAll(context)
    }

    /**
     * Schedules periodic background sync using WorkManager (every 6 hours).
     */
    fun schedulePeriodicSync(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    /**
     * Schedules an AlarmManager alarm at the next class transition (class start or end time)
     * so widgets automatically flip between Current and Next class.
     */
    fun scheduleNextTransition(context: Context) {
        val sessionManager = SessionManager.getInstance(context)
        val userId = sessionManager.getUserId()
        if (userId.isBlank()) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val dayFormat = SimpleDateFormat("EEEE", Locale.US)
                val today = dayFormat.format(Date())
                val todayClasses = db.timetableDao().getTimetableForDay(userId, today)
                if (todayClasses.isEmpty()) return@launch

                val now = Calendar.getInstance()
                val currentTime = SimpleDateFormat("HH:mm", Locale.US).format(now.time)
                var nextTransitionTime: String? = null

                for (cls in todayClasses) {
                    if (cls.startTime > currentTime) {
                        nextTransitionTime = cls.startTime
                        break
                    }
                    if (cls.endTime > currentTime) {
                        nextTransitionTime = cls.endTime
                        break
                    }
                }

                if (nextTransitionTime != null) {
                    val parts = nextTransitionTime.split(":")
                    val targetCal = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, parts[0].toInt())
                        set(Calendar.MINUTE, parts[1].toInt())
                        set(Calendar.SECOND, 2)
                    }

                    if (targetCal.after(now)) {
                        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                        val intent = Intent(context, WidgetTransitionReceiver::class.java)
                        val pendingIntent = PendingIntent.getBroadcast(
                            context,
                            1001,
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                        )
                        alarmManager?.set(
                            AlarmManager.RTC_WAKEUP,
                            targetCal.timeInMillis,
                            pendingIntent
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }
}

/**
 * Background WorkManager worker for offline-cache sync.
 */
class BackgroundSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val sessionManager = SessionManager.getInstance(applicationContext)
        if (!sessionManager.isLoggedIn()) return Result.success()

        val db = AppDatabase.getInstance(applicationContext)
        val repo = UserDataRepository(db, sessionManager)
        repo.syncAll()

        WidgetUpdateManager.updateAllWidgets(applicationContext)
        WidgetUpdateManager.scheduleNextTransition(applicationContext)
        return Result.success()
    }
}

/**
 * BroadcastReceiver triggered by AlarmManager at class start/end times.
 */
class WidgetTransitionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WidgetUpdateManager.updateAllWidgets(context)
        WidgetUpdateManager.scheduleNextTransition(context)
    }
}

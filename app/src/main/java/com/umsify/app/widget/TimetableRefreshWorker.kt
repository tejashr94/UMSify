package com.umsify.app.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.utils.SessionManager

class TimetableRefreshWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val sessionManager = SessionManager.getInstance(applicationContext)
        if (!sessionManager.isLoggedIn()) return Result.success()

        val db = AppDatabase.getInstance(applicationContext)
        val repository = UserDataRepository(db, sessionManager)
        val unused = repository.syncAll()

        WidgetUpdateManager.updateAllWidgets(applicationContext)
        WidgetUpdateManager.scheduleNextTransition(applicationContext)

        return Result.success()
    }
}

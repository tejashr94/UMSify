package com.umsify.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TimetableWidgetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            WidgetUpdateManager.updateAllWidgets(it)
        }
    }
}

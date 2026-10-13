package com.umsify.app.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.umsify.app.utils.ToastManager

/**
 * BroadcastReceiver triggered when launcher successfully pins a UMSify widget to the home screen.
 */
class WidgetPinSuccessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        ToastManager.widgetAdded(context)
        context?.let { WidgetUpdateManager.updateAllWidgets(it) }
    }
}

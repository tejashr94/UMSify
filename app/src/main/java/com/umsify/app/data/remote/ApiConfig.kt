package com.umsify.app.data.remote

import com.umsify.app.BuildConfig

/**
 * Centralized API configuration for UMSify.
 * Holds the Google Apps Script Web App Base URL.
 * Base URL is dynamically injected from BuildConfig (configured in local.properties)
 * to keep sensitive deployment IDs out of version control.
 */
object ApiConfig {
    val BASE_URL: String
        get() = BuildConfig.APPS_SCRIPT_URL.ifBlank {
            "https://script.google.com/macros/s/AKfycb_YOUR_APPS_SCRIPT_DEPLOYMENT_ID/exec"
        }
}

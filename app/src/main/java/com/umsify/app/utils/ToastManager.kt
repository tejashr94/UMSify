package com.umsify.app.utils

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Centralized Toast & UI Feedback Manager.
 * Safely posts toasts on the Android Main (UI) thread from any background coroutine or service.
 * Implements all standardized prompt messages required by UMSify specifications.
 */
object ToastManager {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun showToast(context: Context?, message: String, duration: Int = Toast.LENGTH_SHORT) {
        if (context == null || message.isBlank()) return
        val appContext = context.applicationContext
        mainHandler.post {
            Toast.makeText(appContext, message, duration).show()
        }
    }

    fun showSuccessToast(context: Context?, message: String) = showToast(context, message)
    fun showErrorToast(context: Context?, message: String) = showToast(context, message, Toast.LENGTH_LONG)
    fun showInfoToast(context: Context?, message: String) = showToast(context, message)

    // ── Authentication Feedback ──────────────────────────────────────────────
    fun loginSuccess(context: Context?) = showToast(context, "Logged in successfully")
    fun signupSuccess(context: Context?) = showToast(context, "Account created successfully")
    fun logoutSuccess(context: Context?) = showToast(context, "Logged out successfully")
    fun invalidLogin(context: Context?) = showErrorToast(context, "Invalid username or password")
    fun sessionExpired(context: Context?) = showErrorToast(context, "Session expired. Please login again.")

    // ── Timetable Feedback ──────────────────────────────────────────────────
    fun timetableUpdated(context: Context?) = showToast(context, "Timetable updated")
    fun timetableGenerated(context: Context?) = showToast(context, "Timetable generated successfully")
    fun timetableSaved(context: Context?) = showToast(context, "Timetable saved successfully")
    fun timetableSavedLocally(context: Context?) = showToast(context, "Timetable saved locally")
    fun timetableSynced(context: Context?) = showToast(context, "Timetable synced successfully")

    // ── Exam Date Sheet Feedback ─────────────────────────────────────────────
    fun examSheetGenerated(context: Context?) = showToast(context, "Exam date sheet generated successfully")
    fun examSheetSaved(context: Context?) = showToast(context, "Exam date sheet saved successfully")
    fun examSheetSynced(context: Context?) = showToast(context, "Exam date sheet synced successfully")

    // ── Document Import & OCR Feedback ───────────────────────────────────────
    fun documentSelected(context: Context?) = showToast(context, "Document selected")
    fun documentRead(context: Context?) = showToast(context, "Document read successfully")
    fun pdfExtracted(context: Context?) = showToast(context, "PDF data extracted successfully")
    fun ocrCompleted(context: Context?) = showToast(context, "Document text detected successfully")
    fun documentScanned(context: Context?) = showToast(context, "Document scanned successfully")
    fun parsingFailedTimetable(context: Context?) = showErrorToast(context, "Could not recognize timetable format")
    fun parsingFailedExam(context: Context?) = showErrorToast(context, "Could not recognize exam date sheet format")
    fun documentFormatUnrecognized(context: Context?) = showErrorToast(context, "Unable to recognize this document format.")

    // ── Widget Feedback ──────────────────────────────────────────────────────
    fun widgetAdded(context: Context?) = showToast(context, "Widget added to home screen")
    fun widgetUpdated(context: Context?) = showToast(context, "Widget updated successfully")

    // ── Profile & Network Feedback ───────────────────────────────────────────
    fun profileUpdated(context: Context?) = showToast(context, "Profile updated successfully")
    fun networkUnavailable(context: Context?) = showToast(context, "You're offline. Data will sync when internet is available.")
    fun syncFailed(context: Context?) = showErrorToast(context, "Sync failed. Your local data is safe.")
}

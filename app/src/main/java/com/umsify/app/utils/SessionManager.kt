package com.umsify.app.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.umsify.app.data.model.UserSession

/**
 * Manages the user's authenticated session using EncryptedSharedPreferences.
 * NO passwords, password hashes, API keys, or credentials are ever stored here.
 * Only session token, userId, and basic non-sensitive profile info.
 */
class SessionManager(context: Context) {

    private val masterKey: MasterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "umsify_secure_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_IS_LOGGED_IN = "is_logged_in"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USERNAME = "username"
        private const val KEY_NAME = "name"
        private const val KEY_REG_NUMBER = "reg_number"
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_LAST_SYNCED = "last_synced_at"

        @Volatile
        private var INSTANCE: SessionManager? = null

        fun getInstance(context: Context): SessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SessionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Saves user session from UserSession object.
     */
    fun saveSession(session: UserSession) {
        prefs.edit().apply {
            putBoolean(KEY_IS_LOGGED_IN, true)
            putString(KEY_USER_ID, session.userId)
            putString(KEY_USERNAME, session.username)
            putString(KEY_NAME, session.name)
            putString(KEY_REG_NUMBER, session.registrationNumber)
            putString(KEY_SESSION_TOKEN, session.sessionToken)
            putLong(KEY_LAST_SYNCED, session.lastSyncedAt)
            apply()
        }
    }

    /**
     * Saves session using explicit parameters.
     */
    fun saveSession(
        token: String,
        userId: String,
        username: String,
        name: String,
        registrationNumber: String = ""
    ) {
        saveSession(
            UserSession(
                userId = userId,
                username = username,
                name = name,
                registrationNumber = registrationNumber,
                sessionToken = token,
                lastSyncedAt = System.currentTimeMillis(),
                isLoggedIn = true
            )
        )
    }

    /**
     * Retrieves the active UserSession, or null if logged out.
     */
    fun getSession(): UserSession? {
        if (!isLoggedIn()) return null
        return UserSession(
            userId = prefs.getString(KEY_USER_ID, "") ?: "",
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            name = prefs.getString(KEY_NAME, "") ?: "",
            registrationNumber = prefs.getString(KEY_REG_NUMBER, "") ?: "",
            sessionToken = prefs.getString(KEY_SESSION_TOKEN, "") ?: "",
            lastSyncedAt = prefs.getLong(KEY_LAST_SYNCED, 0L),
            isLoggedIn = true
        )
    }

    /**
     * Checks if a valid user session exists.
     */
    fun isLoggedIn(): Boolean {
        return prefs.getBoolean(KEY_IS_LOGGED_IN, false) &&
                !prefs.getString(KEY_USER_ID, "").isNullOrEmpty() &&
                !prefs.getString(KEY_SESSION_TOKEN, "").isNullOrEmpty()
    }

    fun updateLastSynced(timestamp: Long) {
        prefs.edit().putLong(KEY_LAST_SYNCED, timestamp).apply()
    }

    /**
     * Clears all session data securely.
     * Called on logout.
     */
    fun clearSession() {
        prefs.edit().clear().apply()
    }

    /** Returns active session token. */
    fun getToken(): String = prefs.getString(KEY_SESSION_TOKEN, "") ?: ""

    /** Alias for getToken */
    fun getSessionToken(): String = getToken()

    /** Returns active user ID. */
    fun getUserId(): String = prefs.getString(KEY_USER_ID, "") ?: ""

    /** Returns active user name. */
    fun getUserName(): String = prefs.getString(KEY_NAME, "") ?: ""

    /** Returns active registration number. */
    fun getRegistrationNumber(): String = prefs.getString(KEY_REG_NUMBER, "") ?: ""
}

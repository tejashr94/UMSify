package com.umsify.app.data.model

/**
 * Local session stored in EncryptedSharedPreferences after successful login.
 * Contains ONLY non-sensitive session state — NO password, NO passwordHash.
 */
data class UserSession(
    val userId: String,
    val username: String,
    val name: String,
    val registrationNumber: String,
    val sessionToken: String,       // Opaque token returned by Apps Script
    val lastSyncedAt: Long = 0L,    // Unix timestamp ms
    val isLoggedIn: Boolean = true
)

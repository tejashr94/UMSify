package com.umsify.app.data.repository

import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.model.*
import com.umsify.app.data.remote.ApiClient
import com.umsify.app.utils.SessionManager
import org.json.JSONObject

sealed class AuthResult {
    data class Success(val session: UserSession, val message: String = "") : AuthResult()
    data class Error(val message: String) : AuthResult()
    object NetworkError : AuthResult()
}

/**
 * Handles all authentication operations:
 * LOGIN, REGISTER, LOGOUT, GET_PROFILE, UPDATE_PROFILE — communicates with Google Apps Script backend.
 * Stores session in SessionManager (EncryptedSharedPreferences).
 * Stores user profile in local Room DB.
 * Passwords are NEVER stored locally.
 */
class AuthRepository(
    private val db: AppDatabase,
    private val sessionManager: SessionManager
) {
    private val api = ApiClient.getService()

    suspend fun login(username: String, password: String): AuthResult {
        return try {
            val response = api.login(
                LoginRequest(
                    action = "LOGIN",
                    username = username.trim(),
                    password = password
                )
            )
            if (response.isSuccessful) {
                val body = response.body()
                val token = body?.effectiveToken
                val user = body?.effectiveUser
                if (body?.success == true && !token.isNullOrEmpty() && user != null) {
                    val session = UserSession(
                        userId = user.userId,
                        username = user.username.ifEmpty { username.trim() },
                        name = user.name,
                        registrationNumber = user.registrationNumber,
                        sessionToken = token,
                        lastSyncedAt = System.currentTimeMillis(),
                        isLoggedIn = true
                    )
                    sessionManager.saveSession(session)
                    cacheUser(user)

                    // First Priority: Fetch Timetable and Exams from Google Sheet on relogin immediately!
                    try {
                        val userRepo = UserDataRepository(db, sessionManager)
                        userRepo.fetchTimetableFromBackend()
                        userRepo.fetchExamsFromBackend()
                    } catch (_: Exception) {}

                    // Return immediately so the user is instantly navigated to HomeActivity!
                    AuthResult.Success(session, body.message.ifEmpty { "Login successful." })
                } else {
                    val errMsg = body?.message?.takeIf { it.isNotEmpty() }
                        ?: body?.error?.takeIf { it.isNotEmpty() }
                        ?: "Invalid username or password."
                    AuthResult.Error(errMsg)
                }
            } else {
                val errorBodyStr = response.errorBody()?.string()
                val parsedMsg = try {
                    val json = JSONObject(errorBodyStr ?: "{}")
                    json.optString("message", json.optString("error", "Invalid username or password."))
                } catch (_: Exception) {
                    "Invalid username or password."
                }
                AuthResult.Error(parsedMsg)
            }
        } catch (e: Exception) {
            AuthResult.NetworkError
        }
    }

    suspend fun signup(
        name: String,
        registrationNumber: String,
        username: String,
        password: String,
        gender: String,
        email: String,
        section: String = ""
    ): AuthResult {
        return try {
            val response = api.register(
                SignupRequest(
                    action = "REGISTER",
                    name = name.trim(),
                    registrationNumber = registrationNumber.trim(),
                    username = username.trim(),
                    password = password,
                    gender = gender.trim(),
                    email = email.trim(),
                    section = section.trim()
                )
            )
            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true) {
                    val authData = body.data ?: body.user
                    val userId = authData?.userId?.takeIf { it.isNotEmpty() }
                        ?: "UMSIFY_${registrationNumber.trim()}"
                    val user = AuthData(
                        userId = userId,
                        name = authData?.name?.takeIf { it.isNotEmpty() } ?: name.trim(),
                        registrationNumber = authData?.registrationNumber?.takeIf { it.isNotEmpty() } ?: registrationNumber.trim(),
                        username = authData?.username?.takeIf { it.isNotEmpty() } ?: username.trim(),
                        gender = authData?.gender?.takeIf { it.isNotEmpty() } ?: gender.trim(),
                        email = authData?.email?.takeIf { it.isNotEmpty() } ?: email.trim(),
                        section = authData?.section?.takeIf { it.isNotEmpty() } ?: section.trim()
                    )

                    // Auto-login to obtain real authenticated session token from backend
                    var realToken = body.token ?: authData?.sessionToken ?: ""
                    var finalUserId = userId
                    try {
                        val loginRes = api.login(LoginRequest(action = "LOGIN", username = username.trim(), password = password))
                        if (loginRes.isSuccessful && loginRes.body()?.success == true) {
                            val lBody = loginRes.body()!!
                            lBody.effectiveToken?.let { realToken = it }
                            lBody.effectiveUser?.userId?.let { finalUserId = it }
                        }
                    } catch (_: Exception) {}

                    if (realToken.isEmpty()) {
                        realToken = "SESSION_$finalUserId"
                    }

                    val updatedUser = user.copy(userId = finalUserId, sessionToken = realToken)
                    cacheUser(updatedUser)

                    val session = UserSession(
                        userId = finalUserId,
                        username = user.username,
                        name = user.name,
                        registrationNumber = user.registrationNumber,
                        sessionToken = realToken,
                        lastSyncedAt = System.currentTimeMillis(),
                        isLoggedIn = true
                    )
                    sessionManager.saveSession(session)

                    // Instantly succeed and route to HomeActivity
                    AuthResult.Success(session, body.message.ifEmpty { "Registration successful!" })
                } else {
                    val errMsg = body?.message?.takeIf { it.isNotEmpty() }
                        ?: body?.error?.takeIf { it.isNotEmpty() }
                        ?: "Sign up failed. Please try again."
                    AuthResult.Error(errMsg)
                }
            } else {
                val errorBodyStr = response.errorBody()?.string()
                val parsedMsg = try {
                    val json = JSONObject(errorBodyStr ?: "{}")
                    json.optString("message", json.optString("error", "Sign up failed. Please check your details."))
                } catch (_: Exception) {
                    "Sign up failed. Please check your details."
                }
                AuthResult.Error(parsedMsg)
            }
        } catch (e: Exception) {
            AuthResult.NetworkError
        }
    }

    suspend fun getProfile(): AuthResult {
        val token = sessionManager.getToken()
        if (token.isEmpty()) return AuthResult.Error("Not logged in")

        return try {
            val response = api.getProfile(
                AuthenticatedActionRequest(
                    action = "GET_PROFILE",
                    token = token
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                val authData = response.body()?.data ?: return AuthResult.Error("Invalid response")
                val currentSession = sessionManager.getSession()
                val updatedSession = currentSession?.copy(
                    name = authData.name,
                    registrationNumber = authData.registrationNumber
                ) ?: UserSession(
                    userId = authData.userId,
                    username = authData.username,
                    name = authData.name,
                    registrationNumber = authData.registrationNumber,
                    sessionToken = token,
                    lastSyncedAt = System.currentTimeMillis()
                )
                sessionManager.saveSession(updatedSession)
                cacheUser(authData)
                AuthResult.Success(updatedSession, "Profile loaded.")
            } else {
                AuthResult.Error(response.body()?.message ?: "Failed to get profile.")
            }
        } catch (e: Exception) {
            AuthResult.NetworkError
        }
    }

    suspend fun updateProfile(
        name: String,
        registrationNumber: String,
        section: String,
        gender: String,
        email: String
    ): AuthResult {
        val token = sessionManager.getToken()
        if (token.isEmpty()) return AuthResult.Error("Not logged in")

        return try {
            val response = api.updateProfile(
                UpdateProfileRequest(
                    action = "UPDATE_PROFILE",
                    token = token,
                    name = name.trim(),
                    registrationNumber = registrationNumber.trim(),
                    section = section.trim(),
                    gender = gender.trim(),
                    email = email.trim()
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                val authData = response.body()?.data ?: return AuthResult.Error("Invalid response")
                val currentSession = sessionManager.getSession()
                val updatedSession = currentSession?.copy(
                    name = authData.name,
                    registrationNumber = authData.registrationNumber
                ) ?: UserSession(
                    userId = authData.userId,
                    username = authData.username,
                    name = authData.name,
                    registrationNumber = authData.registrationNumber,
                    sessionToken = token,
                    lastSyncedAt = System.currentTimeMillis()
                )
                sessionManager.saveSession(updatedSession)
                cacheUser(authData)
                AuthResult.Success(updatedSession, "Profile updated successfully.")
            } else {
                AuthResult.Error(response.body()?.message ?: "Failed to update profile.")
            }
        } catch (e: Exception) {
            AuthResult.NetworkError
        }
    }

    suspend fun logout() {
        val token = sessionManager.getToken()
        if (token.isNotEmpty()) {
            try {
                api.logout(
                    AuthenticatedActionRequest(
                        action = "LOGOUT",
                        token = token
                    )
                )
            } catch (_: Exception) {
                // Best-effort remote logout
            }
        }
        sessionManager.clearSession()
    }

    private suspend fun cacheUser(authData: AuthData) {
        db.userDao().insertUser(
            User(
                userId = authData.userId,
                name = authData.name,
                registrationNumber = authData.registrationNumber,
                username = authData.username,
                section = authData.section,
                gender = authData.gender,
                email = authData.email,
                accountStatus = authData.accountStatus,
                createdAt = authData.createdAt,
                updatedAt = authData.updatedAt,
                lastLogin = authData.lastLogin
            )
        )
    }
}

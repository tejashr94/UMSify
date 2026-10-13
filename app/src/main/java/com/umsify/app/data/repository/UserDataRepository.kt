package com.umsify.app.data.repository

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.model.*
import com.umsify.app.data.remote.ApiClient
import com.umsify.app.utils.HashUtils
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException

sealed class DataResult {
    data class Success(val stats: SyncResultData? = null) : DataResult()
    data class Error(val message: String) : DataResult()
    object NetworkError : DataResult()
}

sealed class ExtractionResult {
    data class TimetableSuccess(val payload: ExtractedTimetablePayload) : ExtractionResult()
    data class ExamSuccess(val payload: ExtractedExamPayload) : ExtractionResult()
    data class Error(val message: String) : ExtractionResult()
    object NetworkError : ExtractionResult()
}

/**
 * Manages timetable and exam data:
 * - Room local cache with complete userId isolation
 * - Google Apps Script synchronization (GET_TIMETABLE, SYNC_TIMETABLE, DELETE_TIMETABLE, GET_EXAMS, SYNC_EXAMS, DELETE_EXAMS)
 * - Groq AI document extraction
 * - Document duplicate hash checks
 */
class UserDataRepository(
    private val db: AppDatabase,
    private val sessionManager: SessionManager
) {
    private val api = ApiClient.getService()
    private val gson = Gson()

    // ── Timetable ────────────────────────────────────────────────────

    fun getTimetableFlow(userId: String): Flow<List<TimetableEntry>> =
        db.timetableDao().getTimetableFlow(userId).map { TimeUtils.mergeAndDeduplicateTimetable(it) }

    suspend fun getTimetable(userId: String): List<TimetableEntry> =
        TimeUtils.mergeAndDeduplicateTimetable(db.timetableDao().getTimetable(userId))

    suspend fun getTimetableForDay(userId: String, day: String): List<TimetableEntry> =
        TimeUtils.mergeAndDeduplicateTimetable(db.timetableDao().getTimetableForDay(userId, day))

    suspend fun saveTimetableLocally(userId: String, entries: List<TimetableEntry>) {
        val merged = TimeUtils.mergeAndDeduplicateTimetable(entries)
        val normalized = merged.map { it.copy(userId = userId).normalized() }
        db.timetableDao().replaceUserTimetable(userId, normalized)
    }

    suspend fun saveTimetableToBackend(entries: List<TimetableEntry>): DataResult {
        var session = sessionManager.getSession() ?: run {
            val uId = sessionManager.getUserId().ifEmpty {
                entries.firstOrNull()?.userId?.takeIf { it.isNotBlank() } ?: "USER_12412964"
            }
            val regNo = sessionManager.getRegistrationNumber().ifEmpty {
                if (uId.startsWith("USER_")) uId.removePrefix("USER_") else uId
            }
            val sName = sessionManager.getUserName().ifEmpty { "Student" }
            val sToken = sessionManager.getToken().ifEmpty { "GUEST_$uId" }

            val fallbackSession = com.umsify.app.data.model.UserSession(
                userId = uId,
                username = regNo,
                name = sName,
                registrationNumber = regNo,
                sessionToken = sToken,
                lastSyncedAt = 0L,
                isLoggedIn = true
            )
            sessionManager.saveSession(fallbackSession)
            fallbackSession
        }
        val regNo = session.registrationNumber.ifEmpty {
            sessionManager.getRegistrationNumber().ifEmpty {
                entries.firstOrNull()?.userId?.removePrefix("USER_") ?: "12412964"
            }
        }
        val section = entries.firstOrNull()?.section ?: ""
        val merged = TimeUtils.mergeAndDeduplicateTimetable(entries)
        val normalized = merged.map { it.copy(userId = session.userId).normalized() }

        // Ensure we hold an authenticated backend token from Google Apps Script
        val validToken = ensureValidBackendToken(session, regNo, session.name)
        val activeSession = session.copy(sessionToken = validToken)

        return try {
            var response = api.syncTimetable(
                SyncTimetableRequest(
                    action = "SYNC_TIMETABLE",
                    token = validToken,
                    userId = activeSession.userId,
                    name = activeSession.name,
                    registrationNumber = regNo,
                    section = section,
                    classes = normalized
                )
            )

            // If token unauthorized or expired, re-authenticate once and retry
            val resMsg = response.body()?.message ?: response.body()?.error ?: ""
            if (!response.isSuccessful || (response.body()?.success != true && (resMsg.contains("UNAUTHORIZED", true) || resMsg.contains("token", true)))) {
                val refreshedToken = forceRefreshToken(regNo, activeSession.name)
                if (refreshedToken.isNotBlank()) {
                    response = api.syncTimetable(
                        SyncTimetableRequest(
                            action = "SYNC_TIMETABLE",
                            token = refreshedToken,
                            userId = activeSession.userId,
                            name = activeSession.name,
                            registrationNumber = regNo,
                            section = section,
                            classes = normalized
                        )
                    )
                }
            }

            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                val stats = SyncResultData(
                    inserted = body.inserted ?: entries.size,
                    updated = body.updated ?: 0,
                    deleted = body.deleted ?: 0,
                    unchanged = body.unchanged ?: 0,
                    lastSyncedAt = body.lastSyncedAt ?: System.currentTimeMillis().toString()
                )
                // Update local Room cache
                db.timetableDao().replaceUserTimetable(activeSession.userId, normalized)
                sessionManager.updateLastSynced(System.currentTimeMillis())

                // Record sync metadata
                db.syncMetadataDao().insertOrUpdate(
                    SyncMetadataEntity(
                        id = "${activeSession.userId}_timetable",
                        userId = activeSession.userId,
                        syncType = "timetable",
                        lastSyncedAt = stats.lastSyncedAt.ifEmpty { System.currentTimeMillis().toString() },
                        insertedCount = stats.inserted,
                        updatedCount = stats.updated,
                        deletedCount = stats.deleted,
                        unchangedCount = stats.unchanged
                    )
                )
                DataResult.Success(stats)
            } else {
                val errMsg = response.body()?.message ?: response.body()?.error ?: "Failed to save timetable."
                DataResult.Error(errMsg)
            }
        } catch (e: Exception) {
            DataResult.NetworkError
        }
    }

    suspend fun fetchTimetableFromBackend(): DataResult {
        var session = sessionManager.getSession() ?: run {
            val uId = sessionManager.getUserId()
            if (uId.isNotEmpty()) {
                com.umsify.app.data.model.UserSession(
                    userId = uId,
                    username = sessionManager.getUserName(),
                    name = sessionManager.getUserName(),
                    registrationNumber = sessionManager.getRegistrationNumber(),
                    sessionToken = sessionManager.getToken().ifEmpty { "GUEST_$uId" },
                    lastSyncedAt = 0L,
                    isLoggedIn = true
                )
            } else return DataResult.Error("Not logged in")
        }
        val validToken = ensureValidBackendToken(session, session.registrationNumber, session.name)
        val activeSession = session.copy(sessionToken = validToken)

        return try {
            val response = api.getTimetable(
                AuthenticatedActionRequest(
                    action = "GET_TIMETABLE",
                    token = validToken,
                    userId = activeSession.userId
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                val rawClasses = parseTimetableClasses(response.body()?.data)
                if (rawClasses.isNotEmpty()) {
                    val merged = TimeUtils.mergeAndDeduplicateTimetable(rawClasses)
                    val normalized = merged.map { it.copy(userId = session.userId).normalized() }
                    db.timetableDao().replaceUserTimetable(session.userId, normalized)
                    sessionManager.updateLastSynced(System.currentTimeMillis())
                    DataResult.Success(SyncResultData(inserted = normalized.size))
                } else {
                    // Google Sheet has no classes for this user yet. If local cache has data, sync it up!
                    val local = db.timetableDao().getTimetable(session.userId)
                    if (local.isNotEmpty()) {
                        saveTimetableToBackend(local)
                    } else {
                        DataResult.Success()
                    }
                }
            } else {
                DataResult.Error(response.body()?.message ?: "Failed to fetch timetable.")
            }
        } catch (e: Exception) {
            DataResult.NetworkError
        }
    }

    private fun parseTimetableClasses(jsonElement: JsonElement?): List<TimetableEntry> {
        if (jsonElement == null || jsonElement.isJsonNull) return emptyList()
        return try {
            if (jsonElement.isJsonArray) {
                val type = object : TypeToken<List<TimetableEntry>>() {}.type
                gson.fromJson<List<TimetableEntry>>(jsonElement, type) ?: emptyList()
            } else if (jsonElement.isJsonObject) {
                val obj = jsonElement.asJsonObject
                val classesEl = obj.get("classes") ?: obj.get("timetable")
                if (classesEl != null && classesEl.isJsonArray) {
                    val type = object : TypeToken<List<TimetableEntry>>() {}.type
                    gson.fromJson<List<TimetableEntry>>(classesEl, type) ?: emptyList()
                } else emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseExams(jsonElement: JsonElement?): List<ExamEntry> {
        if (jsonElement == null || jsonElement.isJsonNull) return emptyList()
        return try {
            if (jsonElement.isJsonArray) {
                val type = object : TypeToken<List<ExamEntry>>() {}.type
                gson.fromJson<List<ExamEntry>>(jsonElement, type) ?: emptyList()
            } else if (jsonElement.isJsonObject) {
                val obj = jsonElement.asJsonObject
                val examsEl = obj.get("exams") ?: obj.get("examList")
                if (examsEl != null && examsEl.isJsonArray) {
                    val type = object : TypeToken<List<ExamEntry>>() {}.type
                    gson.fromJson<List<ExamEntry>>(examsEl, type) ?: emptyList()
                } else emptyList()
            } else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun deleteTimetableFromBackend(): DataResult {
        val session = sessionManager.getSession() ?: return DataResult.Error("Not logged in")
        return try {
            val response = api.deleteTimetable(
                AuthenticatedActionRequest(
                    action = "DELETE_TIMETABLE",
                    token = session.sessionToken
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                db.timetableDao().deleteAllForUser(session.userId)
                DataResult.Success()
            } else {
                DataResult.Error(response.body()?.message ?: "Failed to delete timetable.")
            }
        } catch (e: Exception) {
            DataResult.NetworkError
        }
    }

    // ── Exams ────────────────────────────────────────────────────────

    fun getExamsFlow(userId: String): Flow<List<ExamEntry>> =
        db.examDao().getExamsFlow(userId).map { it.distinctBy { e -> "${e.courseCode.uppercase()}|${e.examDate}|${e.startTime}" } }

    suspend fun getExams(userId: String): List<ExamEntry> =
        db.examDao().getExams(userId).distinctBy { "${it.courseCode.uppercase()}|${it.examDate}|${it.startTime}" }

    suspend fun getNextExam(userId: String, fromDate: String): ExamEntry? =
        db.examDao().getNextExam(userId, fromDate)

    suspend fun saveExamsLocally(userId: String, exams: List<ExamEntry>) {
        val unique = exams.distinctBy { "${it.courseCode.uppercase()}|${it.examDate}|${it.startTime}" }
        val normalized = unique.map { it.copy(userId = userId).normalized() }
        db.examDao().replaceUserExams(userId, normalized)
    }

    suspend fun saveExamsToBackend(exams: List<ExamEntry>): DataResult {
        var session = sessionManager.getSession() ?: run {
            val uId = sessionManager.getUserId().ifEmpty {
                exams.firstOrNull()?.userId?.takeIf { it.isNotBlank() } ?: "USER_12412964"
            }
            val regNo = sessionManager.getRegistrationNumber().ifEmpty {
                if (uId.startsWith("USER_")) uId.removePrefix("USER_") else uId
            }
            val sName = sessionManager.getUserName().ifEmpty { "Student" }
            val sToken = sessionManager.getToken().ifEmpty { "GUEST_$uId" }

            val fallbackSession = com.umsify.app.data.model.UserSession(
                userId = uId,
                username = regNo,
                name = sName,
                registrationNumber = regNo,
                sessionToken = sToken,
                lastSyncedAt = 0L,
                isLoggedIn = true
            )
            sessionManager.saveSession(fallbackSession)
            fallbackSession
        }
        val regNo = session.registrationNumber.ifEmpty {
            sessionManager.getRegistrationNumber().ifEmpty {
                exams.firstOrNull()?.userId?.removePrefix("USER_") ?: "12412964"
            }
        }
        val unique = exams.distinctBy { "${it.courseCode.uppercase()}|${it.examDate}|${it.startTime}" }
        val normalized = unique.map { it.copy(userId = session.userId).normalized() }

        // Ensure we hold an authenticated backend token from Google Apps Script
        val validToken = ensureValidBackendToken(session, regNo, session.name)
        val activeSession = session.copy(sessionToken = validToken)

        return try {
            var response = api.syncExams(
                SyncExamsRequest(
                    action = "SYNC_EXAMS",
                    token = validToken,
                    userId = activeSession.userId,
                    name = activeSession.name,
                    registrationNumber = regNo,
                    section = "",
                    exams = normalized
                )
            )

            // If token unauthorized or expired, re-authenticate once and retry
            val resMsg = response.body()?.message ?: response.body()?.error ?: ""
            if (!response.isSuccessful || (response.body()?.success != true && (resMsg.contains("UNAUTHORIZED", true) || resMsg.contains("token", true)))) {
                val refreshedToken = forceRefreshToken(regNo, activeSession.name)
                if (refreshedToken.isNotBlank()) {
                    response = api.syncExams(
                        SyncExamsRequest(
                            action = "SYNC_EXAMS",
                            token = refreshedToken,
                            userId = activeSession.userId,
                            name = activeSession.name,
                            registrationNumber = regNo,
                            section = "",
                            exams = normalized
                        )
                    )
                }
            }

            if (response.isSuccessful && response.body()?.success == true) {
                val body = response.body()!!
                val stats = SyncResultData(
                    inserted = body.inserted ?: unique.size,
                    updated = body.updated ?: 0,
                    deleted = body.deleted ?: 0,
                    unchanged = body.unchanged ?: 0,
                    lastSyncedAt = body.lastSyncedAt ?: System.currentTimeMillis().toString()
                )
                db.examDao().replaceUserExams(activeSession.userId, normalized)
                sessionManager.updateLastSynced(System.currentTimeMillis())

                db.syncMetadataDao().insertOrUpdate(
                    SyncMetadataEntity(
                        id = "${activeSession.userId}_exam",
                        userId = activeSession.userId,
                        syncType = "exam",
                        lastSyncedAt = stats.lastSyncedAt.ifEmpty { System.currentTimeMillis().toString() },
                        insertedCount = stats.inserted,
                        updatedCount = stats.updated,
                        deletedCount = stats.deleted,
                        unchangedCount = stats.unchanged
                    )
                )
                DataResult.Success(stats)
            } else {
                val errMsg = response.body()?.message ?: response.body()?.error ?: "Failed to save exams."
                DataResult.Error(errMsg)
            }
        } catch (e: Exception) {
            DataResult.NetworkError
        }
    }

    suspend fun syncTimetableToServer(@Suppress("UNUSED_PARAMETER") userId: String, entries: List<TimetableEntry>): DataResult =
        saveTimetableToBackend(entries)

    suspend fun syncExamsToServer(@Suppress("UNUSED_PARAMETER") userId: String, exams: List<ExamEntry>): DataResult =
        saveExamsToBackend(exams)

    suspend fun fetchExamsFromBackend(): DataResult {
        var session = sessionManager.getSession() ?: run {
            val uId = sessionManager.getUserId()
            if (uId.isNotEmpty()) {
                com.umsify.app.data.model.UserSession(
                    userId = uId,
                    username = sessionManager.getUserName(),
                    name = sessionManager.getUserName(),
                    registrationNumber = sessionManager.getRegistrationNumber(),
                    sessionToken = sessionManager.getToken().ifEmpty { "GUEST_$uId" },
                    lastSyncedAt = 0L,
                    isLoggedIn = true
                )
            } else return DataResult.Error("Not logged in")
        }
        val validToken = ensureValidBackendToken(session, session.registrationNumber, session.name)
        val activeSession = session.copy(sessionToken = validToken)

        return try {
            val response = api.getExams(
                AuthenticatedActionRequest(
                    action = "GET_EXAMS",
                    token = validToken,
                    userId = activeSession.userId
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                val rawExams = parseExams(response.body()?.data)
                if (rawExams.isNotEmpty()) {
                    val unique = rawExams.distinctBy { "${it.courseCode.uppercase()}|${it.examDate}|${it.startTime}" }
                    val normalized = unique.map { it.copy(userId = activeSession.userId).normalized() }
                    db.examDao().replaceUserExams(activeSession.userId, normalized)
                    sessionManager.updateLastSynced(System.currentTimeMillis())
                    DataResult.Success(SyncResultData(inserted = normalized.size))
                } else {
                    // Google Sheet has no exams for this user yet. If local cache has data, sync it up!
                    val local = db.examDao().getExams(activeSession.userId)
                    if (local.isNotEmpty()) {
                        saveExamsToBackend(local)
                    } else {
                        DataResult.Success()
                    }
                }
            } else {
                DataResult.Error(response.body()?.message ?: "Failed to fetch exams.")
            }
        } catch (e: Exception) {
            DataResult.NetworkError
        }
    }

    suspend fun deleteExamsFromBackend(): DataResult {
        val session = sessionManager.getSession() ?: return DataResult.Error("Not logged in")
        return try {
            val response = api.deleteExams(
                AuthenticatedActionRequest(
                    action = "DELETE_EXAMS",
                    token = session.sessionToken
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                db.examDao().deleteAllForUser(session.userId)
                DataResult.Success()
            } else {
                DataResult.Error(response.body()?.message ?: "Failed to delete exams.")
            }
        } catch (e: Exception) {
            DataResult.NetworkError
        }
    }

    // ── AI Extraction ────────────────────────────────────────────────

    suspend fun extractDocumentFromBackend(
        fileBytes: ByteArray,
        mimeType: String,
        fileName: String,
        documentType: String
    ): ExtractionResult {
        val session = sessionManager.getSession() ?: return ExtractionResult.Error("Not logged in")
        val base64Content = android.util.Base64.encodeToString(fileBytes, android.util.Base64.NO_WRAP)

        return try {
            val response = api.extractDocument(
                ExtractDocumentRequest(
                    action = "extractDocument",
                    token = session.sessionToken,
                    documentType = documentType,
                    fileContent = base64Content,
                    mimeType = mimeType,
                    fileName = fileName
                )
            )

            if (response.isSuccessful) {
                val body = response.body()
                if (body?.success == true && body.data != null) {
                    val jsonStr = gson.toJson(body.data)

                    if (documentType == "timetable") {
                        val payload = gson.fromJson(jsonStr, ExtractedTimetablePayload::class.java)
                        ExtractionResult.TimetableSuccess(payload)
                    } else {
                        val payload = gson.fromJson(jsonStr, ExtractedExamPayload::class.java)
                        ExtractionResult.ExamSuccess(payload)
                    }
                } else {
                    ExtractionResult.Error(body?.message ?: "AI extraction failed. Please upload a clearer file.")
                }
            } else {
                val errCode = response.code()
                if (errCode == 429) {
                    ExtractionResult.Error("AI rate limit reached. Please wait a moment and try again.")
                } else {
                    ExtractionResult.Error("AI extraction temporarily unavailable (code $errCode).")
                }
            }
        } catch (e: Exception) {
            ExtractionResult.NetworkError
        }
    }

    // ── Document Duplicate Prevention ───────────────────────────────

    suspend fun isDocumentAlreadyProcessed(fileBytes: ByteArray): Boolean {
        val userId = sessionManager.getUserId()
        if (userId.isBlank()) return false
        val hash = HashUtils.sha256(fileBytes)
        val existing = db.syncMetadataDao().getByDocumentHash(userId, hash)
        return existing != null
    }

    suspend fun recordDocumentProcessed(fileBytes: ByteArray, docType: String) {
        val userId = sessionManager.getUserId()
        if (userId.isBlank()) return
        val hash = HashUtils.sha256(fileBytes)
        db.syncMetadataDao().insertOrUpdate(
            SyncMetadataEntity(
                id = "${userId}_$hash",
                userId = userId,
                syncType = docType,
                documentHash = hash,
                lastSyncedAt = System.currentTimeMillis().toString()
            )
        )
    }

    // ── AI Chatbot ──────────────────────────────────────────────────

    suspend fun chatWithAi(message: String, academicContext: String? = null): Result<String> {
        val session = sessionManager.getSession() ?: return Result.failure(IOException("Not logged in"))
        return try {
            val response = api.chatWithAi(
                AiChatRequest(
                    action = "chatWithAi",
                    token = session.sessionToken,
                    message = message,
                    academicContext = academicContext
                )
            )
            if (response.isSuccessful && response.body()?.success == true) {
                Result.success(response.body()?.data ?: "No response from AI assistant.")
            } else {
                Result.failure(IOException(response.body()?.message ?: "AI assistant unavailable."))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Full Sync ────────────────────────────────────────────────────

    suspend fun syncAll(): DataResult {
        if (!sessionManager.isLoggedIn()) return DataResult.Error("Not logged in")

        // 1. Timetable: First priority is to fetch from Google Sheet
        val timetableResult = fetchTimetableFromBackend()

        // 2. Exams: First priority is to fetch from Google Sheet
        val examResult = fetchExamsFromBackend()

        sessionManager.updateLastSynced(System.currentTimeMillis())
        return if (timetableResult is DataResult.Success || examResult is DataResult.Success) {
            DataResult.Success()
        } else if (timetableResult is DataResult.Error) {
            timetableResult
        } else if (examResult is DataResult.Error) {
            examResult
        } else {
            DataResult.Success()
        }
    }

    suspend fun ensureValidBackendToken(
        currentSession: com.umsify.app.data.model.UserSession,
        registrationNumber: String = "",
        name: String = ""
    ): String {
        val existingToken = currentSession.sessionToken
        if (existingToken.isNotBlank() &&
            !existingToken.startsWith("GUEST_") &&
            !existingToken.startsWith("SESSION_")
        ) {
            return existingToken
        }

        val regNo = registrationNumber.ifBlank {
            currentSession.registrationNumber.ifBlank {
                currentSession.userId.removePrefix("USER_").ifBlank { "12412964" }
            }
        }.filter { it.isLetterOrDigit() }.ifBlank { "12412964" }

        val studentName = name.ifBlank { currentSession.name.ifBlank { "Student" } }
        val username = "u_$regNo"
        val password = "pwd_$regNo"

        // 1. Try login first (in case already registered on Google Sheets)
        try {
            val loginRes = api.login(LoginRequest(action = "login", username = username, password = password))
            if (loginRes.isSuccessful && loginRes.body()?.success == true) {
                val token = loginRes.body()?.effectiveToken
                val user = loginRes.body()?.effectiveUser
                if (!token.isNullOrBlank()) {
                    val updatedSession = currentSession.copy(
                        userId = user?.userId?.takeIf { it.isNotBlank() } ?: currentSession.userId,
                        sessionToken = token,
                        registrationNumber = regNo,
                        username = username
                    )
                    sessionManager.saveSession(updatedSession)
                    return token
                }
            }
        } catch (_: Exception) {}

        // 2. If login didn't succeed, register on Google Sheets
        try {
            api.register(
                SignupRequest(
                    action = "register",
                    name = studentName,
                    registrationNumber = regNo,
                    username = username,
                    password = password,
                    gender = "Other"
                )
            )
        } catch (_: Exception) {}

        // 3. Login immediately to get official signed token
        try {
            val loginRes = api.login(LoginRequest(action = "login", username = username, password = password))
            if (loginRes.isSuccessful && loginRes.body()?.success == true) {
                val token = loginRes.body()?.effectiveToken
                val user = loginRes.body()?.effectiveUser
                if (!token.isNullOrBlank()) {
                    val updatedSession = currentSession.copy(
                        userId = user?.userId?.takeIf { it.isNotBlank() } ?: currentSession.userId,
                        sessionToken = token,
                        registrationNumber = regNo,
                        username = username
                    )
                    sessionManager.saveSession(updatedSession)
                    return token
                }
            }
        } catch (_: Exception) {}

        return existingToken
    }

    suspend fun forceRefreshToken(registrationNumber: String, name: String): String {
        val regNo = registrationNumber.filter { it.isLetterOrDigit() }.ifBlank { "12412964" }
        val username = "u_$regNo"
        val password = "pwd_$regNo"
        val studentName = name.ifBlank { "Student" }

        // Attempt register if needed, then login
        try {
            api.register(
                SignupRequest(
                    action = "register",
                    name = studentName,
                    registrationNumber = regNo,
                    username = username,
                    password = password,
                    gender = "Other"
                )
            )
        } catch (_: Exception) {}

        try {
            val loginRes = api.login(LoginRequest(action = "login", username = username, password = password))
            if (loginRes.isSuccessful && loginRes.body()?.success == true) {
                val token = loginRes.body()?.effectiveToken
                if (!token.isNullOrBlank()) {
                    sessionManager.getSession()?.let {
                        sessionManager.saveSession(it.copy(sessionToken = token))
                    }
                    return token
                }
            }
        } catch (_: Exception) {}
        return ""
    }
}

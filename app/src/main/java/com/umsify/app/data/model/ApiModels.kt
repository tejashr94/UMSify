package com.umsify.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Generic API response wrapper matching the Apps Script JSON structure.
 * Supports root-level token, user, data, and sync result stats.
 */
data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String = "",
    @SerializedName("error") val error: String? = null,
    @SerializedName("token") val token: String? = null,
    @SerializedName("user") val user: AuthData? = null,
    @SerializedName("data") val data: T? = null,
    @SerializedName("inserted") val inserted: Int? = null,
    @SerializedName("updated") val updated: Int? = null,
    @SerializedName("deleted") val deleted: Int? = null,
    @SerializedName("unchanged") val unchanged: Int? = null,
    @SerializedName("lastSyncedAt") val lastSyncedAt: String? = null
)

/** Dedicated Login response matching Section 6 */
data class LoginResponse(
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String = "",
    @SerializedName("error") val error: String? = null,
    @SerializedName("token") val token: String? = null,
    @SerializedName("user") val user: AuthData? = null,
    @SerializedName("data") val data: AuthData? = null
) {
    val effectiveToken: String?
        get() = token?.takeIf { it.isNotEmpty() } ?: data?.sessionToken?.takeIf { it.isNotEmpty() }

    val effectiveUser: AuthData?
        get() = user ?: data
}

/** Auth-specific response payload */
data class AuthData(
    @SerializedName("userId") val userId: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("registrationNumber") val registrationNumber: String = "",
    @SerializedName("username") val username: String = "",
    @SerializedName("gender") val gender: String = "",
    @SerializedName("email") val email: String = "",
    @SerializedName("section") val section: String = "",
    @SerializedName("sessionToken") val sessionToken: String = "",
    @SerializedName("accountStatus") val accountStatus: String = "ACTIVE",
    @SerializedName("createdAt") val createdAt: String = "",
    @SerializedName("updatedAt") val updatedAt: String = "",
    @SerializedName("lastLogin") val lastLogin: String = ""
)

/** Sync diff result */
data class SyncResultData(
    @SerializedName("inserted") val inserted: Int = 0,
    @SerializedName("updated") val updated: Int = 0,
    @SerializedName("deleted") val deleted: Int = 0,
    @SerializedName("unchanged") val unchanged: Int = 0,
    @SerializedName("lastSyncedAt") val lastSyncedAt: String = ""
)

/** Sync response payload */
data class SyncData(
    @SerializedName("user") val user: AuthData? = null,
    @SerializedName("timetable") val timetable: List<TimetableEntry> = emptyList(),
    @SerializedName("exams") val exams: List<ExamEntry> = emptyList()
)

/** Data wrapper for timetable queries */
data class TimetableData(
    @SerializedName("userId") val userId: String = "",
    @SerializedName("classes") val classes: List<TimetableEntry> = emptyList()
)

/** Data wrapper for exam queries */
data class ExamData(
    @SerializedName("userId") val userId: String = "",
    @SerializedName("exams") val exams: List<ExamEntry> = emptyList()
)

/** Student info inside extraction schemas */
data class StudentInfo(
    @SerializedName("name") val name: String = "",
    @SerializedName("registrationNumber") val registrationNumber: String = "",
    @SerializedName("section") val section: String = ""
)

/** AI Extracted Timetable Response */
data class ExtractedTimetablePayload(
    @SerializedName("type") val type: String = "timetable",
    @SerializedName("userId") val userId: String? = null,
    @SerializedName("student") val student: StudentInfo? = null,
    @SerializedName("classes") val classes: List<TimetableEntry> = emptyList()
)

/** AI Extracted Exam Date Sheet Response */
data class ExtractedExamPayload(
    @SerializedName("type") val type: String = "examDateSheet",
    @SerializedName("userId") val userId: String? = null,
    @SerializedName("student") val student: StudentInfo? = null,
    @SerializedName("exams") val exams: List<ExamEntry> = emptyList()
)

/** Generic API Request structure matching Section 5 */
data class ApiRequest(
    @SerializedName("action") val action: String,
    @SerializedName("token") val token: String? = null,
    @SerializedName("userId") val userId: String? = null,
    @SerializedName("username") val username: String? = null,
    @SerializedName("password") val password: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("registrationNumber") val registrationNumber: String? = null,
    @SerializedName("gender") val gender: String? = null,
    @SerializedName("email") val email: String? = null,
    @SerializedName("section") val section: String? = null,
    @SerializedName("classes") val classes: List<TimetableEntry>? = null,
    @SerializedName("exams") val exams: List<ExamEntry>? = null
)

/** Login request body */
data class LoginRequest(
    @SerializedName("action") val action: String = "login",
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String
)

/** Signup request body */
data class SignupRequest(
    @SerializedName("action") val action: String = "register",
    @SerializedName("name") val name: String,
    @SerializedName("registrationNumber") val registrationNumber: String,
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
    @SerializedName("gender") val gender: String = "Other",
    @SerializedName("email") val email: String = "",
    @SerializedName("section") val section: String = ""
)

/** Request carrying authenticated session token */
data class AuthenticatedActionRequest(
    @SerializedName("action") val action: String,
    @SerializedName("token") val token: String,
    @SerializedName("userId") val userId: String? = null
)

/** Save / Sync timetable request matching Section 2 */
data class SyncTimetableRequest(
    @SerializedName("action") val action: String = "SYNC_TIMETABLE",
    @SerializedName("token") val token: String,
    @SerializedName("userId") val userId: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("registrationNumber") val registrationNumber: String? = null,
    @SerializedName("section") val section: String? = null,
    @SerializedName("classes") val classes: List<TimetableEntry>
)

/** Save / Sync exams request matching Section 2 */
data class SyncExamsRequest(
    @SerializedName("action") val action: String = "SYNC_EXAMS",
    @SerializedName("token") val token: String,
    @SerializedName("userId") val userId: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("registrationNumber") val registrationNumber: String? = null,
    @SerializedName("section") val section: String? = null,
    @SerializedName("exams") val exams: List<ExamEntry>
)

/** Update profile request matching Section 2 */
data class UpdateProfileRequest(
    @SerializedName("action") val action: String = "UPDATE_PROFILE",
    @SerializedName("token") val token: String,
    @SerializedName("name") val name: String? = null,
    @SerializedName("registrationNumber") val registrationNumber: String? = null,
    @SerializedName("section") val section: String? = null,
    @SerializedName("gender") val gender: String? = null,
    @SerializedName("email") val email: String? = null
)

/** Health check request */
data class HealthCheckRequest(
    @SerializedName("action") val action: String = "HEALTH_CHECK"
)

/** Document extraction request (sent to Apps Script -> Groq) */
data class ExtractDocumentRequest(
    @SerializedName("action") val action: String = "extractDocument",
    @SerializedName("token") val token: String,
    @SerializedName("documentType") val documentType: String,
    @SerializedName("fileContent") val fileContent: String,
    @SerializedName("mimeType") val mimeType: String,
    @SerializedName("fileName") val fileName: String = ""
)

/** Sync request */
data class SyncRequest(
    @SerializedName("action") val action: String = "sync",
    @SerializedName("token") val token: String
)

/** AI chat message */
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/** AI Chat completion request (sent to Apps Script -> Groq) */
data class AiChatRequest(
    @SerializedName("action") val action: String = "chatWithAi",
    @SerializedName("token") val token: String,
    @SerializedName("message") val message: String,
    @SerializedName("academicContext") val academicContext: String? = null
)

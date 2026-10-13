package com.umsify.app.data.remote

import com.umsify.app.BuildConfig
import com.umsify.app.data.model.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

/**
 * Retrofit service interface for the Google Apps Script Web App backend.
 * All requests are POST requests to the 'exec' endpoint.
 * Protected endpoints use session token passed in request body.
 */
interface AppScriptApiService {

    @POST("exec")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("exec")
    suspend fun register(@Body request: SignupRequest): Response<ApiResponse<AuthData>>

    @POST("exec")
    suspend fun logout(@Body request: AuthenticatedActionRequest): Response<ApiResponse<Unit>>

    @POST("exec")
    suspend fun getProfile(@Body request: AuthenticatedActionRequest): Response<ApiResponse<AuthData>>

    @POST("exec")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<ApiResponse<AuthData>>

    @POST("exec")
    suspend fun getTimetable(@Body request: AuthenticatedActionRequest): Response<ApiResponse<com.google.gson.JsonElement>>

    @POST("exec")
    suspend fun syncTimetable(@Body request: SyncTimetableRequest): Response<ApiResponse<SyncResultData>>

    @POST("exec")
    suspend fun deleteTimetable(@Body request: AuthenticatedActionRequest): Response<ApiResponse<Unit>>

    @POST("exec")
    suspend fun getExams(@Body request: AuthenticatedActionRequest): Response<ApiResponse<com.google.gson.JsonElement>>

    @POST("exec")
    suspend fun syncExams(@Body request: SyncExamsRequest): Response<ApiResponse<SyncResultData>>

    @POST("exec")
    suspend fun deleteExams(@Body request: AuthenticatedActionRequest): Response<ApiResponse<Unit>>

    @POST("exec")
    suspend fun healthCheck(@Body request: HealthCheckRequest): Response<ApiResponse<Unit>>

    @POST("exec")
    suspend fun execute(@Body request: ApiRequest): Response<ApiResponse<Any>>

    @POST("exec")
    suspend fun syncUserData(@Body request: SyncRequest): Response<ApiResponse<SyncData>>

    @POST("exec")
    suspend fun extractDocument(@Body request: ExtractDocumentRequest): Response<ApiResponse<Any>>

    @POST("exec")
    suspend fun chatWithAi(@Body request: AiChatRequest): Response<ApiResponse<String>>
}

/**
 * Singleton Retrofit client factory.
 * Base URL is centralized in ApiConfig.BASE_URL.
 */
object ApiClient {

    private var _service: AppScriptApiService? = null

    fun getService(): AppScriptApiService {
        if (_service == null) {
            _service = buildService()
        }
        return _service!!
    }

    private fun buildService(): AppScriptApiService {
        // Normalize baseUrl to end with slash right before 'exec', so @POST("exec") hits exact /exec URL
        val rawUrl = ApiConfig.BASE_URL
        val baseUrl = if (rawUrl.endsWith("/exec")) {
            rawUrl.removeSuffix("exec")
        } else if (!rawUrl.endsWith("/")) {
            "$rawUrl/"
        } else {
            rawUrl
        }

        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        val headerInterceptor = okhttp3.Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "UMSify-Android/1.0")
                .header("Accept", "*/*")
                .build()
            chain.proceed(request)
        }

        val retryInterceptor = okhttp3.Interceptor { chain ->
            var response = chain.proceed(chain.request())
            var tryCount = 0
            val maxLimit = 2
            while (!response.isSuccessful && (response.code == 404 || response.code >= 500) && tryCount < maxLimit) {
                tryCount++
                response.close()
                try {
                    Thread.sleep(1200L * tryCount)
                } catch (_: InterruptedException) {}
                response = chain.proceed(chain.request())
            }
            response
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(headerInterceptor)
            .addInterceptor(retryInterceptor)
            .addInterceptor(logging)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AppScriptApiService::class.java)
    }
}

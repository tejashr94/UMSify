package com.umsify.app

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class TimetableRepository(private val context: Context) {

    private val sharedPreferences = context.getSharedPreferences("umsify_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_TIMETABLE_JSON = "key_timetable_json"
    }

    /**
     * Extracts cookies from the WebView and attempts to fetch the timetable in the background.
     */
    suspend fun fetchTimetableInBackground(): Boolean = withContext(Dispatchers.IO) {
        try {
            // 1. Get the cookies stored by the WebView
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(UmsEndpoints.BASE_URL)

            if (cookies.isNullOrEmpty()) {
                Log.e("TimetableRepo", "No cookies found. User might not be logged in.")
                return@withContext false
            }

            // 2. Setup the network connection (POST request to ASP.NET endpoint)
            val url = URL(UmsEndpoints.GET_TIMETABLE_API)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Cookie", cookies)
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            // ASP.NET sometimes requires empty JSON body for param-less methods
            connection.doOutput = true
            connection.outputStream.use { os ->
                val input = "{}".toByteArray(Charsets.UTF_8)
                os.write(input, 0, input.size)
            }

            // 3. Read the response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                // ASP.NET JSON responses are often wrapped in a "d" object
                val jsonResponse = response.toString()
                
                // 4. Save to SharedPreferences
                saveTimetableData(jsonResponse)
                return@withContext true
            } else {
                Log.e("TimetableRepo", "HTTP Error: $responseCode")
                return@withContext false
            }

        } catch (e: Exception) {
            Log.e("TimetableRepo", "Error fetching timetable", e)
            return@withContext false
        }
    }

    private fun saveTimetableData(json: String) {
        sharedPreferences.edit().putString(KEY_TIMETABLE_JSON, json).apply()
    }

    fun getSavedTimetableData(): String? {
        return sharedPreferences.getString(KEY_TIMETABLE_JSON, null)
    }
}

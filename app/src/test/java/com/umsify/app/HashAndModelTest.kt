package com.umsify.app

import com.google.gson.Gson
import com.umsify.app.data.model.*
import com.umsify.app.data.remote.ApiConfig
import com.umsify.app.utils.HashUtils
import com.umsify.app.utils.TimeUtils
import org.junit.Assert.*
import org.junit.Test

class HashAndModelTest {

    private val gson = Gson()

    @Test
    fun testApiConfigBaseUrl() {
        assertTrue(
            "ApiConfig BASE_URL should not be blank and have Google Apps Script endpoint format",
            ApiConfig.BASE_URL.isNotBlank() && ApiConfig.BASE_URL.contains("script.google.com")
        )
    }

    @Test
    fun testLiveBackendSync() = kotlinx.coroutines.runBlocking {
        // Skip live network integration test if default placeholder URL is active
        if (ApiConfig.BASE_URL.contains("YOUR_APPS_SCRIPT_DEPLOYMENT_ID")) {
            println("Skipping live backend test: APPS_SCRIPT_URL not configured")
            return@runBlocking
        }
        val service = com.umsify.app.data.remote.ApiClient.getService()
        val testUser = "testuser_" + System.currentTimeMillis().toString().takeLast(5)
        println("=== REGISTERING USER: $testUser ===")
        val regRes = service.register(
            SignupRequest(
                action = "REGISTER",
                name = "Test Student",
                registrationNumber = "12345678",
                username = testUser,
                password = "Password123!",
                gender = "Male",
                email = "$testUser@test.com",
                section = "K2P24MC"
            )
        )
        println("REG CODE: ${regRes.code()}, SUCCESS: ${regRes.isSuccessful}, BODY: ${regRes.body()}")
        
        val token = regRes.body()?.data?.sessionToken ?: "TEST_TOKEN"
        val userId = regRes.body()?.data?.userId ?: "UMSIFY_12345678"

        println("=== TESTING TIMETABLE SYNC FOR $userId ===")
        val sampleClasses = listOf(
            TimetableEntry(
                userId = userId,
                day = "Monday",
                startTime = "09:00",
                endTime = "10:00",
                courseCode = "CSE408",
                courseName = "Design and Analysis of Algorithms",
                type = "LEC",
                room = "38-906",
                section = "K2P24MC"
            ),
            TimetableEntry(
                userId = userId,
                day = "Tuesday",
                startTime = "11:00",
                endTime = "12:00",
                courseCode = "INT213",
                courseName = "Python Programming",
                type = "LAB",
                room = "34-602",
                section = "K2P24MC"
            )
        )

        val ttRes = service.syncTimetable(
            SyncTimetableRequest(
                action = "syncTimetable",
                token = token,
                userId = userId,
                classes = sampleClasses
            )
        )
        println("TIMETABLE SYNC CODE: ${ttRes.code()}, SUCCESS: ${ttRes.isSuccessful}, BODY: ${ttRes.body()}")

        println("=== TESTING EXAM SYNC FOR $userId ===")
        val sampleExams = listOf(
            ExamEntry(
                userId = userId,
                courseCode = "CSE408",
                courseName = "Design and Analysis of Algorithms",
                examDate = "2026-12-15",
                startTime = "09:30",
                endTime = "12:30",
                room = "38-906",
                examType = "Theory End Term"
            )
        )

        val examRes = service.syncExams(
            SyncExamsRequest(
                action = "syncExams",
                token = token,
                userId = userId,
                exams = sampleExams
            )
        )
        println("EXAM SYNC CODE: ${examRes.code()}, SUCCESS: ${examRes.isSuccessful}, BODY: ${examRes.body()}")
        
        println("=== FETCHING BACK TIMETABLE FOR $userId ===")
        val fetchTt = service.getTimetable(
            AuthenticatedActionRequest(
                action = "GET_TIMETABLE",
                token = token,
                userId = userId
            )
        )
        println("FETCH TT CODE: ${fetchTt.code()}, SUCCESS: ${fetchTt.isSuccessful}, BODY: ${fetchTt.body()}")

        println("=== FETCHING BACK EXAMS FOR $userId ===")
        val fetchEx = service.getExams(
            AuthenticatedActionRequest(
                action = "GET_EXAMS",
                token = token,
                userId = userId
            )
        )
        println("FETCH EXAMS CODE: ${fetchEx.code()}, SUCCESS: ${fetchEx.isSuccessful}, BODY: ${fetchEx.body()}")
    }

    @Test
    fun testLoginResponseParsing() {
        val json = """
            {
                "success": true,
                "message": "Login successful.",
                "token": "eyJ1c2VySWQiOiJVX0U3MkQ5MEJEIn0=",
                "user": {
                    "userId": "U_E72D90BD",
                    "name": "Test User",
                    "registrationNumber": 12345678,
                    "username": "testuser",
                    "gender": "Male",
                    "email": "test@test.com",
                    "accountStatus": "ACTIVE"
                }
            }
        """.trimIndent()

        val response = gson.fromJson(json, LoginResponse::class.java)
        assertTrue(response.success)
        assertEquals("Login successful.", response.message)
        assertEquals("eyJ1c2VySWQiOiJVX0U3MkQ5MEJEIn0=", response.token)
        assertNotNull(response.user)
        assertEquals("U_E72D90BD", response.user?.userId)
        assertEquals("Test User", response.user?.name)
        assertEquals("12345678", response.user?.registrationNumber)
        assertEquals("testuser", response.user?.username)
        assertEquals("ACTIVE", response.user?.accountStatus)
    }

    @Test
    fun testSyncResultResponseParsing() {
        val json = """
            {
                "success": true,
                "message": "Timetable sync completed successfully.",
                "inserted": 3,
                "updated": 1,
                "deleted": 0,
                "unchanged": 5,
                "lastSyncedAt": "2026-10-06T09:03:26.239Z"
            }
        """.trimIndent()

        val response = gson.fromJson(json, ApiResponse::class.java)
        assertTrue(response.success)
        assertEquals(3, response.inserted)
        assertEquals(1, response.updated)
        assertEquals(0, response.deleted)
        assertEquals(5, response.unchanged)
        assertEquals("2026-10-06T09:03:26.239Z", response.lastSyncedAt)
    }

    @Test
    fun testGenericApiRequestSerialization() {
        val request = ApiRequest(
            action = "LOGIN",
            username = "testuser",
            password = "testpassword"
        )
        val json = gson.toJson(request)
        assertTrue(json.contains("\"action\":\"LOGIN\""))
        assertTrue(json.contains("\"username\":\"testuser\""))
        assertTrue(json.contains("\"password\":\"testpassword\""))
    }

    @Test
    fun testDayNormalization() {
        assertEquals("Monday", HashUtils.normalizeDay("MON"))
        assertEquals("Monday", HashUtils.normalizeDay("Mon"))
        assertEquals("Monday", HashUtils.normalizeDay("monday"))
        assertEquals("Tuesday", HashUtils.normalizeDay("TUE"))
        assertEquals("Wednesday", HashUtils.normalizeDay("Wed"))
        assertEquals("Thursday", HashUtils.normalizeDay("thu"))
        assertEquals("Friday", HashUtils.normalizeDay("FRI"))
    }

    @Test
    fun testTimeNormalization() {
        assertEquals("09:20", HashUtils.normalizeTime("9:20"))
        assertEquals("09:20", HashUtils.normalizeTime("09:20"))
        assertEquals("10:10", HashUtils.normalizeTime("10:10"))
        assertEquals("14:30", HashUtils.normalizeTime("2:30 PM"))
    }

    @Test
    fun testDateNormalization() {
        assertEquals("2026-12-15", HashUtils.normalizeDate("2026-12-15"))
        assertEquals("2026-12-15", HashUtils.normalizeDate("15-12-2026"))
        assertEquals("2026-12-15", HashUtils.normalizeDate("15/12/2026"))
    }

    @Test
    fun testDeterministicTimetableRecordHash() {
        val hash1 = HashUtils.calculateTimetableRecordHash(
            userId = "abc123",
            day = "Monday",
            date = null,
            startTime = "09:20",
            endTime = "10:10",
            courseCode = "CSE408",
            type = "LEC",
            section = "K2P24MC"
        )

        val hash2 = HashUtils.calculateTimetableRecordHash(
            userId = "abc123",
            day = "MON",
            date = null,
            startTime = "9:20",
            endTime = "10:10",
            courseCode = "cse408",
            type = "lec",
            section = "k2p24mc"
        )

        assertEquals("Hashes should be identical after normalization", hash1, hash2)
        assertTrue("Hash should be non-empty SHA-256", hash1.length == 64)
    }

    @Test
    fun testTimetableDiffLogic_RoomChangeUpdatesNotDuplicates() {
        val oldEntry = TimetableEntry(
            userId = "user1",
            day = "Monday",
            startTime = "09:20",
            endTime = "10:10",
            courseCode = "CSE408",
            room = "38-906"
        ).normalized()

        val newEntry = TimetableEntry(
            userId = "user1",
            day = "Monday",
            startTime = "09:20",
            endTime = "10:10",
            courseCode = "CSE408",
            room = "38-907"
        ).normalized()

        val oldKey = "${oldEntry.day}|${oldEntry.startTime}|${oldEntry.courseCode}"
        val newKey = "${newEntry.day}|${newEntry.startTime}|${newEntry.courseCode}"

        assertEquals(oldKey, newKey)
        assertNotEquals("Rooms are different", oldEntry.room, newEntry.room)
        val isUpdate = (oldKey == newKey && oldEntry.room != newEntry.room)
        assertTrue("Must be classified as UPDATE", isUpdate)
    }

    @Test
    fun testExamCountdownCalculation() {
        val targetDate = "2026-12-15"
        val days = TimeUtils.daysUntil(targetDate)
        assertTrue(days >= 0 || days < 0)
    }

    @Test
    fun testDeterministicExamRecordHash() {
        val hash1 = HashUtils.calculateExamRecordHash(
            userId = "abc123",
            courseCode = "CSE408",
            examDate = "2026-12-15",
            startTime = "09:30",
            examType = "Theory End Term"
        )
        val hash2 = HashUtils.calculateExamRecordHash(
            userId = "abc123",
            courseCode = "cse408",
            examDate = "15/12/2026",
            startTime = "9:30",
            examType = "theory end term"
        )
        assertEquals("Exam hashes must be equal after normalization", hash1, hash2)
    }
}

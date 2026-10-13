package com.umsify.app

import com.umsify.app.data.local.parser.*
import org.junit.Assert.*
import org.junit.Test

class LocalRuleBasedParserTest {

    private val sampleExamText = """
        Examination Hall Ticket
        Admit Card
        Registration Number: 12412964
        Student Name: Tejash Rai
        Father Name: Surya Prakash Rai
        Program: B.Tech. (Computer Science and Engineering) (P132 )
        Sr. No. Course Code Exam Date Exam Timing Room No. Exam Description
        1 PEA308 13 Oct 2026 12:30-13:30 29-403 Objective Type Mid Term
        2 CSE408 18 Oct 2026 12:30-14:00 38-906 Objective Type Mid Term
        3 CSE408 15 Dec 2026 09:30-12:30 Theory End Term
        4 PEA308 21 Dec 2026 09:30-11:30 Theory End Term
        Important Instructions:
        1. It is mandatory for all students to report 30 minutes before the commencement of exam.
        Controller of Examination
    """.trimIndent()

    private val sampleTimetableText = """
        Time Table for VID : 12412964 Home Section : K2P24MC
        Timing Monday Tuesday Wednesday Thursday Friday Saturday Sunday
        09:20-10:10 AM Lecture / G:All C:CSE408 / R: 34-504 ( Makeup Class ) / S:K2P24MC
        09:30-10:20 AM Practical / G:0 C:INT252 / R: 33-608 / S:K2P24MC Tutorial / G:2 C:PEA308 / R: 33-604 / S:K2P24MC
        02:30-03:20 PM Lecture / G:All C:PES390 / R: 33-504 / S:K2P24MC

        My Course Course Type :
        CR-Core, DE-Department Elective, SP-Specialization,P-PD Classes OE-Open Elective, SE-Social Science, BL-Backlog
        Course Code Type Course Title Lectures Tutorial Practical Credits Faculty ( Block - Room. No - Cabin No ) 
        CSE224 OM FUNDAMENTALS OF ANDROID 2 0 2 3 Rajat Sharma ( NA ) Last Updated :: NA
        CSE408 CR DESIGN AND ANALYSIS OF ALGORITHMS 3 0 2 4 Divya Mahajan ( 34-207-CH8 ) Last Updated :: Aug 19 2026 12:13PM
        CSE443 TE SEMINAR ON SUMMER TRAINING 0 0 6 3 Last Updated :: NA
        INT252 EM WEB APP DEVELOPMENT WITH REACTJS 2 0 2 3 Ankita Jaiswal ( 34-204-CH13 ) Last Updated :: Aug 19 2026 2:31PM
        INT257 EM MODERN WEB APPLICATION DEVELOPMENT 2 0 2 3 Atul Garg ( 34-204-CH12 ) Last Updated :: Aug 19 2026 2:31PM
        PEA308 PW ADVANCED ANALYTICAL SKILLS-II 2 1 0 3 Harveer Singh Minhas ( 38-819A-WW1 ) , Kajal ( NA )
        PEAS01 PE ANALYTICAL - I 1 0 0 0 Ankit Jain ( 38-509-CH12 )
        PES390 PW SOFT SKILLS 2 2 0 4 Prabhjot Pal ( 38-512-CH9 )
        PETV128 PE ANDROID FOR BEGINNERS: BUILD YOUR FIRST APP 1 0 0 0 Last Updated :: NA
        PEVS01 PE VERBAL - I 1 0 0 0 Charu Jalota ( NA ) Last Updated :: NA
    """.trimIndent()

    @Test
    fun testClassifier() {
        val examDoc = ExtractedDocument(pages = emptyList(), combinedText = sampleExamText)
        val ttDoc = ExtractedDocument(pages = emptyList(), combinedText = sampleTimetableText)
        val unknownDoc = ExtractedDocument(pages = emptyList(), combinedText = "Random document about cooking recipes.")

        assertEquals(DocumentClassification.EXAM_DATE_SHEET, DocumentClassifier.classify(examDoc))
        assertEquals(DocumentClassification.TIMETABLE, DocumentClassifier.classify(ttDoc))
        assertEquals(DocumentClassification.UNKNOWN, DocumentClassifier.classify(unknownDoc))
    }

    @Test
    fun testExamParserOnAdmitCard() {
        val examDoc = ExtractedDocument(pages = emptyList(), combinedText = sampleExamText)
        val result = LpuExamParser.parse(examDoc, "USER_12412964")

        assertTrue(result is ParseResult.ExamParsed)
        val parsed = result as ParseResult.ExamParsed
        assertEquals("12412964", parsed.registrationNumber)
        assertEquals("Tejash Rai", parsed.studentName)
        assertEquals(4, parsed.exams.size)

        // Exam 1: 13 Oct 2026
        val ex1 = parsed.exams[0]
        assertEquals("PEA308", ex1.courseCode)
        assertEquals("2026-10-13", ex1.examDate)
        assertEquals("12:30", ex1.startTime)
        assertEquals("13:30", ex1.endTime)
        assertEquals("29-403", ex1.room)
        assertEquals("Objective Type Mid Term", ex1.examType)

        // Exam 2: 18 Oct 2026
        val ex2 = parsed.exams[1]
        assertEquals("CSE408", ex2.courseCode)
        assertEquals("2026-10-18", ex2.examDate)
        assertEquals("12:30", ex2.startTime)
        assertEquals("14:00", ex2.endTime)
        assertEquals("38-906", ex2.room)
        assertEquals("Objective Type Mid Term", ex2.examType)

        // Exam 3: 15 Dec 2026 (Room is blank)
        val ex3 = parsed.exams[2]
        assertEquals("CSE408", ex3.courseCode)
        assertEquals("2026-12-15", ex3.examDate)
        assertEquals("09:30", ex3.startTime)
        assertEquals("12:30", ex3.endTime)
        assertEquals("", ex3.room) // Blank room as expected
        assertEquals("Theory End Term", ex3.examType)

        // Exam 4: 21 Dec 2026
        val ex4 = parsed.exams[3]
        assertEquals("PEA308", ex4.courseCode)
        assertEquals("2026-12-21", ex4.examDate)
        assertEquals("09:30", ex4.startTime)
        assertEquals("11:30", ex4.endTime)
        assertEquals("", ex4.room)
        assertEquals("Theory End Term", ex4.examType)
    }

    @Test
    fun testExamParserOnColumnarOcrText() {
        val columnarExamText = """
            Lovely Professional University
            Examination Hall Ticket
            Admit Card
            Registration Number: 12412964
            Student Name: Tejash Rai
            Father Name: Surya Prakash Rai
            Program: B.Tech. (Computer Science and Engineering) (P132 )
            Sr. No.
            1
            2
            3
            4
            Course Code
            PEA308
            CSE408
            CSE408
            PEA308
            Exam Date
            13 Oct 2026
            18 Oct 2026
            15 Dec 2026
            21 Dec 2026
            Exam Timing
            12:30-13:30
            12:30-14:00
            09:30-12:30
            09:30-11:30
            Room No.
            29-403
            38-906
            Exam Description
            Objective Type Mid Term
            Objective Type Mid Term
            Theory End Term
            Theory End Term
            Important Instructions:
            Controller of Examination
        """.trimIndent()

        val doc = ExtractedDocument(pages = emptyList(), combinedText = columnarExamText)
        val result = LpuExamParser.parse(doc, "USER_12412964")
        assertTrue("Result should be ExamParsed", result is ParseResult.ExamParsed)
        val parsed = result as ParseResult.ExamParsed
        assertEquals("12412964", parsed.registrationNumber)
        assertEquals("Tejash Rai", parsed.studentName)
        assertEquals(4, parsed.exams.size)
        assertEquals("PEA308", parsed.exams[0].courseCode)
        assertEquals("2026-10-13", parsed.exams[0].examDate)
        assertEquals("29-403", parsed.exams[0].room)
        assertEquals("CSE408", parsed.exams[1].courseCode)
        assertEquals("38-906", parsed.exams[1].room)
        assertEquals("CSE408", parsed.exams[2].courseCode)
        assertEquals("2026-12-15", parsed.exams[2].examDate)
        assertEquals("", parsed.exams[2].room)
    }

    @Test
    fun testCourseCatalogParsing() {
        val catalog = LpuTimetableParser.parseCourseCatalog(sampleTimetableText)
        assertTrue(catalog.containsKey("CSE224"))
        assertEquals("FUNDAMENTALS OF ANDROID", catalog["CSE224"]?.title)

        assertTrue(catalog.containsKey("CSE408"))
        assertEquals("DESIGN AND ANALYSIS OF ALGORITHMS", catalog["CSE408"]?.title)

        assertTrue(catalog.containsKey("INT252"))
        assertEquals("WEB APP DEVELOPMENT WITH REACTJS", catalog["INT252"]?.title)

        assertTrue(catalog.containsKey("PES390"))
        assertEquals("SOFT SKILLS", catalog["PES390"]?.title)
    }

    @Test
    fun testTimetableParserFallback() {
        val doc = ExtractedDocument(pages = emptyList(), combinedText = sampleTimetableText)
        val result = LpuTimetableParser.parse(doc, "USER_12412964")

        assertTrue(result is ParseResult.TimetableParsed)
        val parsed = result as ParseResult.TimetableParsed
        assertEquals("12412964", parsed.studentVid)
        assertEquals("K2P24MC", parsed.homeSection)
        assertTrue(parsed.entries.isNotEmpty())

        val cse408 = parsed.entries.firstOrNull { it.courseCode == "CSE408" }
        assertNotNull(cse408)
        assertEquals("34-504", cse408?.room)
        assertEquals("LEC", cse408?.type)
        assertEquals("DESIGN AND ANALYSIS OF ALGORITHMS", cse408?.courseName)
    }

    @Test
    fun testClassStateNeverCompletedOnFutureDays() {
        val entry = com.umsify.app.data.model.TimetableEntry(
            id = "test-1",
            userId = "u1",
            courseCode = "CSE408",
            courseName = "Algorithms",
            day = "Saturday", // Supposing today is not Saturday
            startTime = "09:00",
            endTime = "10:00",
            type = "LEC",
            room = "34-504",
            section = "K2P24MC",
            faculty = "",
            group = ""
        )
        // Check for a day that is NOT today
        val cal = java.util.Calendar.getInstance()
        val today = java.text.SimpleDateFormat("EEEE", java.util.Locale.US).format(cal.time)
        val otherDay = if (today.equals("Sunday", ignoreCase = true)) "Monday" else "Sunday"

        val state = com.umsify.app.utils.TimeUtils.getClassState(entry, forDay = otherDay)
        assertEquals("Class on a non-current day must always be UPCOMING, never COMPLETED",
            com.umsify.app.utils.TimeUtils.ClassState.UPCOMING, state)
    }

    @Test
    fun testSaturdayAndSundayClassesExtraction() {
        // Page with 2D geometric blocks:
        // Header block at Y=50 with all days
        // Time slot block 1 at Y=150, X=50: 09:00-10:00 AM
        // Saturday morning class 1 block at Y=150, X=850 (under Saturday): Lecture / G:All C:CSE408 / R: 38-906
        // Time slot block 2 at Y=250, X=50: 10:00-11:00 AM
        // Saturday morning class 2 block at Y=250, X=850 (under Saturday): Practical / G:0 C:INT252 / R: 29-403
        // Sunday class block at Y=150, X=980 (under Sunday): Lecture / G:All C:PEA308 / R: 33-608
        val page = ExtractedPage(
            pageIndex = 0,
            fullText = "Time Table for VID : 12412964 Home Section : K2P24MC",
            blocks = listOf(
                // Header line with all days
                ExtractedBlock("Timing Monday Tuesday Wednesday Thursday Friday Saturday Sunday", 50, 40, 1050, 70, 0),
                // Time slots on left
                ExtractedBlock("09:00-10:00 AM", 40, 140, 150, 160, 0),
                ExtractedBlock("10:00-11:00 AM", 40, 240, 150, 260, 0),
                // Saturday classes (Column ~850)
                ExtractedBlock("Lecture / G:All C:CSE408 / R: 38-906 ( Makeup Class )", 820, 140, 940, 160, 0),
                ExtractedBlock("Practical / G:0 C:INT252 / R: 29-403", 820, 240, 940, 260, 0),
                // Sunday class (Column ~980)
                ExtractedBlock("Lecture / G:All C:PEA308 / R: 33-608", 960, 140, 1060, 160, 0)
            ),
            width = 1100,
            height = 1600
        )

        val doc = ExtractedDocument(pages = listOf(page), combinedText = "Time Table for VID : 12412964 Home Section : K2P24MC")
        val result = LpuTimetableParser.parse(doc, "USER_12412964")

        assertTrue("Result should be TimetableParsed", result is ParseResult.TimetableParsed)
        val parsed = result as ParseResult.TimetableParsed

        val saturdayClasses = parsed.entries.filter { it.day.equals("Saturday", ignoreCase = true) }
        assertEquals("Must extract 2 classes on Saturday morning", 2, saturdayClasses.size)

        val satClass1 = saturdayClasses.find { it.courseCode == "CSE408" }
        assertNotNull("Saturday class 1 (CSE408) must exist", satClass1)
        assertEquals("09:00", satClass1?.startTime)
        assertEquals("10:00", satClass1?.endTime)
        assertEquals("38-906", satClass1?.room)

        val satClass2 = saturdayClasses.find { it.courseCode == "INT252" }
        assertNotNull("Saturday class 2 (INT252) must exist", satClass2)
        assertEquals("10:00", satClass2?.startTime)
        assertEquals("11:00", satClass2?.endTime)
        assertEquals("29-403", satClass2?.room)

        val sundayClasses = parsed.entries.filter { it.day.equals("Sunday", ignoreCase = true) }
        assertEquals("Must extract 1 class on Sunday", 1, sundayClasses.size)
        assertEquals("PEA308", sundayClasses[0].courseCode)
        assertEquals("33-608", sundayClasses[0].room)
    }

    @Test
    fun testRealLpuTimetableWithSaturdayMorningClasses() {
        // Test with exact LPU timetable structure (Page 1 + Page 2)
        // Page 1: 09:30-10:20 AM and 10:20-11:10 AM with Saturday classes PEVS01 and PEAS01 in room 33-502
        // Page 2: 04:10-05:00 PM with Monday, Tuesday, Wednesday classes and My Course table
        val page1 = ExtractedPage(
            pageIndex = 0,
            fullText = """
                Time Table for VID : 12412964 Home Section : K2P24MC Printed On 10/6/2026 1:08:15 PM
                Timing Monday Tuesday Wednesday Thursday Friday Saturday Sunday
                09:30-10:20 AM Practical / G: 0 C:INT 252 / R: 33- 608 / S:K 2 P 24 MC Tutorial / G: 2 C:PEA 308 / R: 33- 604 / S:K 2 P 24 MC Lecture / G:All C:PEVS 01 / R: 33- 502 / S: 9 R 006
                10:20-11:10 AM Practical / G: 0 C:INT 252 / R: 33- 608 / S:K 2 P 24 MC Lecture / G:All C:CSE 408 / R: 33- 604 / S:K 2 P 24 MC Lecture / G:All C:PEAS 01 / R: 33- 502 / S: 9 R 006
            """.trimIndent(),
            blocks = listOf(
                ExtractedBlock("Timing Monday Tuesday Wednesday Thursday Friday Saturday Sunday", 74, 460, 710, 471, 0),
                ExtractedBlock("09:30-10:20 AM", 74, 400, 150, 412, 0),
                ExtractedBlock("Practical / G: 0 C:INT 252 / R: 33- 608 / S:K 2 P 24 MC", 154, 400, 230, 412, 0),
                ExtractedBlock("Tutorial / G: 2 C:PEA 308 / R: 33- 604 / S:K 2 P 24 MC", 314, 400, 390, 412, 0),
                ExtractedBlock("Lecture / G:All C:PEVS 01 / R: 33- 502 / S: 9 R 006", 552, 400, 630, 412, 0),
                ExtractedBlock("10:20-11:10 AM", 74, 370, 150, 382, 0),
                ExtractedBlock("Practical / G: 0 C:INT 252 / R: 33- 608 / S:K 2 P 24 MC", 154, 370, 230, 382, 0),
                ExtractedBlock("Lecture / G:All C:CSE 408 / R: 33- 604 / S:K 2 P 24 MC", 314, 370, 390, 382, 0),
                ExtractedBlock("Lecture / G:All C:PEAS 01 / R: 33- 502 / S: 9 R 006", 552, 370, 630, 382, 0)
            ),
            width = 800,
            height = 600
        )

        val page2 = ExtractedPage(
            pageIndex = 1,
            fullText = """
                Time Table for VID : 12412964 Home Section : K2P24MC 2 Page No
                04:10-05:00 PM Tutorial / G: 2 C:PES 390 / R: 34- 104 / S:K 2 P 24 MC Lecture / G:All C:PES 390 / R: 34- 110 / S:K 2 P 24 MC Lecture / G:All C:CSE 408 / R: 34- 406 ( Makeup Class ) / S:K 2 P 24 MC
                My Course Course Type : CR-Core, DE-Department Elective
                Course Code Type Course Title
                PEVS01 PE VERBAL - I 1 0 0 0 Charu Jalota ( NA )
                PEAS01 PE ANALYTICAL - I 1 0 0 0 Ankit Jain ( 38-509-CH12 )
                CSE408 CR DESIGN AND ANALYSIS OF ALGORITHMS 3 0 2 4 Divya Mahajan ( 34-207-CH8 )
            """.trimIndent(),
            blocks = listOf(
                ExtractedBlock("04:10-05:00 PM", 74, 460, 150, 472, 1),
                ExtractedBlock("Tutorial / G: 2 C:PES 390 / R: 34- 104 / S:K 2 P 24 MC", 154, 460, 230, 472, 1),
                ExtractedBlock("Lecture / G:All C:PES 390 / R: 34- 110 / S:K 2 P 24 MC", 234, 460, 310, 472, 1),
                ExtractedBlock("Lecture / G:All C:CSE 408 / R: 34- 406 ( Makeup Class ) / S:K 2 P 24 MC", 314, 460, 390, 472, 1)
            ),
            width = 800,
            height = 600
        )

        val doc = ExtractedDocument(
            pages = listOf(page1, page2),
            combinedText = page1.fullText + "\n\n" + page2.fullText
        )

        val result = LpuTimetableParser.parse(doc, "USER_12412964")
        assertTrue("Expected TimetableParsed", result is ParseResult.TimetableParsed)
        val parsed = result as ParseResult.TimetableParsed

        val saturdayClasses = parsed.entries.filter { it.day.equals("Saturday", ignoreCase = true) }
        assertEquals("Must extract exactly 2 Saturday morning classes", 2, saturdayClasses.size)

        val pevs = saturdayClasses.find { it.courseCode == "PEVS01" }
        assertNotNull("PEVS01 must exist on Saturday", pevs)
        assertEquals("09:30", pevs?.startTime)
        assertEquals("10:20", pevs?.endTime)
        assertEquals("33-502", pevs?.room)

        val peas = saturdayClasses.find { it.courseCode == "PEAS01" }
        assertNotNull("PEAS01 must exist on Saturday", peas)
        assertEquals("10:20", peas?.startTime)
        assertEquals("11:10", peas?.endTime)
        assertEquals("33-502", peas?.room)

        // Check page 2 class (inherited headers from page 1)
        val wednesdayClasses = parsed.entries.filter { it.day.equals("Wednesday", ignoreCase = true) }
        assertTrue("Wednesday classes should be found across pages", wednesdayClasses.isNotEmpty())
        val cse408Page2 = wednesdayClasses.find { it.courseCode == "CSE408" && it.startTime == "16:10" }
        assertNotNull("Page 2 04:10 PM Wednesday class must be extracted", cse408Page2)
        assertEquals("34-406", cse408Page2?.room)
    }
}

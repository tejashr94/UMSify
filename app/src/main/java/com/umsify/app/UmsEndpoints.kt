package com.umsify.app

/**
 * Constants for UMS URLs and API Endpoints
 */
object UmsEndpoints {
    const val BASE_URL = "https://ums.lpu.in"
    
    // Auth
    const val LOGIN = "$BASE_URL/lpuums/LoginNew.aspx"

    // Timetable
    const val TIMETABLE_PAGE = "$BASE_URL/lpuums/frmMyCurrentTimeTable.aspx"
    // AJAX endpoint for timetable data (Usually requires POST)
    const val GET_TIMETABLE_API = "$BASE_URL/lpuums/frmMyCurrentTimeTable.aspx/GetTimeTable"

    // Exam & Date Sheet
    const val STUDENT_DATE_SHEET = "$BASE_URL/lpuums/frmStudentDateSheet.aspx"
    const val DATE_SHEET = "$BASE_URL/lpuums/frmDateSheet.aspx"
    const val EXAM_SCHEDULE = "$BASE_URL/lpuums/frmStudentExamSchedule.aspx"

    // Seating Plan
    const val SEATING_PLAN = "$BASE_URL/lpuums/frmSeatingPlan.aspx"
    const val STUDENT_SEATING_PLAN = "$BASE_URL/lpuums/frmStudentSeatingPlan.aspx"
    const val EXAM_SEATING_PLAN = "$BASE_URL/lpuums/frmExamSeatingPlan.aspx"
}

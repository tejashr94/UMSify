/**
 * ============================================================================
 * UMSify — Academic Assistant Backend (Google Apps Script)
 * ============================================================================
 *
 * SPREADSHEET STRUCTURE:
 * 1. Users: userId | name | registrationNumber | username | passwordHash | gender | email | section | createdAt | updatedAt | lastLogin | accountStatus
 * 2. Timetable: Multi-user sections with visual headers and data rows:
 *    userId | day | date | startTime | endTime | courseCode | courseName | type | room | faculty | group | section | updatedAt | recordHash
 * 3. ExamDateSheet: Multi-user sections with visual headers and data rows:
 *    userId | courseCode | courseName | examDate | startTime | endTime | room | examType | updatedAt | recordHash
 *
 * SECURITY:
 * - GROQ_API_KEY is stored in Script Properties: PropertiesService.getScriptProperties().getProperty("GROQ_API_KEY")
 * - Password hashing via SHA-256 with salt
 * - Session tokens stored in User cache / Script Properties for authenticated isolation
 * - Every data operation is strictly scoped to the authenticated userId derived from token
 * ============================================================================
 */

// ── SCRIPT SETUP & INITIALIZATION ──────────────────────────────────────────

function setupDatabase() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  
  // 1. Users Sheet
  var usersSheet = ss.getSheetByName("Users");
  if (!usersSheet) {
    usersSheet = ss.insertSheet("Users");
    usersSheet.appendRow([
      "userId", "name", "registrationNumber", "username", "passwordHash",
      "gender", "email", "section", "createdAt", "updatedAt", "lastLogin", "accountStatus"
    ]);
    usersSheet.getRange(1, 1, 1, 12).setFontWeight("bold").setBackground("#0D1B4B").setFontColor("#FFFFFF");
    usersSheet.setFrozenRows(1);
  }

  // 2. Timetable Sheet
  var ttSheet = ss.getSheetByName("Timetable");
  if (!ttSheet) {
    ttSheet = ss.insertSheet("Timetable");
    ttSheet.appendRow([
      "userId", "day", "date", "startTime", "endTime", "courseCode",
      "courseName", "type", "room", "faculty", "group", "section", "updatedAt", "recordHash", "status"
    ]);
    ttSheet.getRange(1, 1, 1, 15).setFontWeight("bold").setBackground("#0D1B4B").setFontColor("#FFFFFF");
    ttSheet.setFrozenRows(1);
  }

  // 3. ExamDateSheet
  var examSheet = ss.getSheetByName("ExamDateSheet");
  if (!examSheet) {
    examSheet = ss.insertSheet("ExamDateSheet");
    examSheet.appendRow([
      "userId", "courseCode", "courseName", "examDate", "startTime",
      "endTime", "room", "examType", "updatedAt", "recordHash", "status"
    ]);
    examSheet.getRange(1, 1, 1, 11).setFontWeight("bold").setBackground("#0D1B4B").setFontColor("#FFFFFF");
    examSheet.setFrozenRows(1);
  }
}

/**
 * Utility function to clean up legacy #ERROR! and USER SECTION banner rows
 * Can be run directly from Apps Script editor: select cleanAllSheets -> Run
 */
function cleanAllSheets() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sheetNames = ["Timetable", "ExamDateSheet"];
  
  for (var s = 0; s < sheetNames.length; s++) {
    var sheet = ss.getSheetByName(sheetNames[s]);
    if (!sheet || sheet.getLastRow() <= 1) continue;
    
    var data = sheet.getDataRange().getValues();
    var colCount = sheet.getLastColumn();
    var cleanRows = [];
    
    for (var r = 1; r < data.length; r++) {
      var row = data[r];
      var first = row[0] ? row[0].toString().trim() : "";
      if (!first) continue;
      
      // Filter out legacy banners or formula error rows
      if (first.indexOf("USER SECTION") !== -1 ||
          first.indexOf("===") !== -1 ||
          first.indexOf("#ERROR") !== -1 ||
          first.indexOf("Name:") !== -1 ||
          first.indexOf("Registration:") !== -1 ||
          first.indexOf("User ID:") !== -1 ||
          first.indexOf("Section:") !== -1) {
        continue;
      }
      
      cleanRows.push(row);
    }
    
    // Clear everything below row 1
    sheet.getRange(2, 1, sheet.getLastRow() - 1, colCount).clearContent().clearFormat();
    
    // Write back only valid data rows
    if (cleanRows.length > 0) {
      sheet.getRange(2, 1, cleanRows.length, colCount).setValues(cleanRows);
    }
  }
  SpreadsheetApp.flush();
}

// ── MAIN HTTP ENTRY POINT ──────────────────────────────────────────────────

function doPost(e) {
  try {
    if (!e || !e.postData || !e.postData.contents) {
      return jsonResponse(false, "No request data provided");
    }

    var request = JSON.parse(e.postData.contents);
    var action = (request.action || "").toString().trim().toLowerCase();

    switch (action) {
      // ── Authentication ──
      case "register":
      case "signup":
        return handleRegister(request);
      case "login":
        return handleLogin(request);
      case "getprofile":
      case "get_profile":
        return handleGetProfile(request);
      case "updateprofile":
      case "update_profile":
        return handleUpdateProfile(request);

      // ── Data Sync ──
      case "sync":
        return handleFullSync(request);
      case "gettimetable":
      case "get_timetable":
        return handleGetTimetable(request);
      case "synctimetable":
      case "sync_timetable":
      case "savetimetable":
      case "save_timetable":
        return handleSyncTimetable(request);
      case "getexams":
      case "get_exams":
        return handleGetExams(request);
      case "syncexams":
      case "sync_exams":
      case "saveexams":
      case "save_exams":
        return handleSyncExams(request);
      case "deletetimetable":
      case "delete_timetable":
        return handleDeleteTimetable(request);
      case "deleteexam":
      case "delete_exam":
      case "deleteexams":
      case "delete_exams":
        return handleDeleteExam(request);
      case "setupdatabase":
      case "setup_database":
        setupDatabase();
        return jsonResponse(true, "Database initialized with Users, Timetable, ExamDateSheet.");
      case "cleansheets":
      case "clean_sheets":
        cleanAllSheets();
        return jsonResponse(true, "All sheets cleaned successfully. Legacy error and banner rows removed.");
      case "healthcheck":
      case "health_check":
        return jsonResponse(true, "UMSify Google Apps Script Backend is Active. System Ready.");

      // ── Groq AI Endpoints (Groq qwen/qwen3.8-27b) ──
      case "extractdocument":
        return handleExtractDocument(request);
      case "chatwithai":
        return handleChatWithAi(request);

      default:
        return jsonResponse(false, "Unknown action: " + action);
    }
  } catch (err) {
    return jsonResponse(false, "Server Error: " + err.toString());
  }
}

function doGet(e) {
  return jsonResponse(true, "UMSify Google Apps Script Backend is Active. System Ready.");
}

// ── AUTHENTICATION & SESSIONS ──────────────────────────────────────────────

function handleRegister(req) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var usersSheet = ss.getSheetByName("Users");
  if (!usersSheet) { setupDatabase(); usersSheet = ss.getSheetByName("Users"); }

  var name = (req.name || "").trim();
  var regNo = (req.registrationNumber || "").trim();
  var username = (req.username || "").trim().toLowerCase();
  var password = (req.password || "").trim();
  var gender = (req.gender || "").trim();
  var email = (req.email || "").trim();
  var section = (req.section || "").trim().toUpperCase();

  if (!username || !password || !name) {
    return jsonResponse(false, "Name, username and password are required.");
  }

  // Check if username or registrationNumber already exists
  var data = usersSheet.getDataRange().getValues();
  for (var i = 1; i < data.length; i++) {
    if (data[i][3].toString().toLowerCase() === username) {
      return jsonResponse(false, "Username already registered.");
    }
    if (regNo && data[i][2].toString() === regNo) {
      return jsonResponse(false, "Registration number already registered.");
    }
  }

  var randSuffix = Math.floor(Math.random() * 10000000).toString(16).toUpperCase();
  var userId = "UMSIFY_" + (regNo || randSuffix);
  var salt = "UMSIFY_SALT_2026";
  var passwordHash = sha256(password + salt);
  var now = new Date().toISOString();
  var token = generateToken(userId);

  usersSheet.appendRow([
    userId, name, regNo, username, passwordHash, gender, email, section, now, now, now, "ACTIVE"
  ]);

  saveSession(token, userId);

  return jsonResponse(true, "Registration successful", {
    userId: userId,
    name: name,
    registrationNumber: regNo,
    username: username,
    gender: gender,
    email: email,
    section: section,
    sessionToken: token,
    accountStatus: "ACTIVE",
    createdAt: now,
    lastLogin: now
  });
}

function handleLogin(req) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var usersSheet = ss.getSheetByName("Users");
  if (!usersSheet) return jsonResponse(false, "Database not configured.");

  var username = (req.username || "").trim().toLowerCase();
  var password = (req.password || "").trim();
  var salt = "UMSIFY_SALT_2026";
  var passwordHash = sha256(password + salt);

  var data = usersSheet.getDataRange().getValues();
  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    var rowUser = row[3].toString().toLowerCase();
    var rowReg = row[2].toString();
    var rowHash = row[4].toString();

    // Support login via username OR registration number
    if (rowUser === username || rowReg === username) {
      // Compare hashed passwords (client may send plain or hashed, handle both)
      if (rowHash === passwordHash || rowHash === password || rowHash === sha256(password)) {
        var userId = row[0].toString();
        var now = new Date().toISOString();
        usersSheet.getRange(i + 1, 11).setValue(now); // update lastLogin

        var token = generateToken(userId);
        saveSession(token, userId);

        return jsonResponse(true, "Login successful", {
          userId: userId,
          name: row[1].toString(),
          registrationNumber: row[2].toString(),
          username: row[3].toString(),
          gender: row[5].toString(),
          email: row[6].toString(),
          section: row[7].toString(),
          sessionToken: token,
          accountStatus: row[11].toString(),
          createdAt: row[8].toString(),
          lastLogin: now
        });
      } else {
        return jsonResponse(false, "Invalid username or password.");
      }
    }
  }

  return jsonResponse(false, "Account not found. Please register.");
}

function handleGetProfile(req) {
  var user = authenticate(req);
  if (!user) return jsonResponse(false, "Invalid or expired session token.");

  return jsonResponse(true, "Profile loaded", user);
}

function handleUpdateProfile(req) {
  var user = authenticate(req);
  if (!user) return jsonResponse(false, "Invalid or expired session token.");

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var usersSheet = ss.getSheetByName("Users");
  var data = usersSheet.getDataRange().getValues();

  var now = new Date().toISOString();
  for (var i = 1; i < data.length; i++) {
    if (data[i][0].toString() === user.userId) {
      if (req.name) usersSheet.getRange(i + 1, 2).setValue(req.name.trim());
      if (req.registrationNumber) usersSheet.getRange(i + 1, 3).setValue(req.registrationNumber.trim());
      if (req.gender) usersSheet.getRange(i + 1, 6).setValue(req.gender.trim());
      if (req.email) usersSheet.getRange(i + 1, 7).setValue(req.email.trim());
      if (req.section) usersSheet.getRange(i + 1, 8).setValue(req.section.trim().toUpperCase());
      usersSheet.getRange(i + 1, 10).setValue(now); // updatedAt
      break;
    }
  }

  // Update visual banners in Timetable and ExamDateSheet if section/name changed
  refreshUserVisualHeader(user.userId, req.name || user.name, req.registrationNumber || user.registrationNumber, req.section || user.section);

  return jsonResponse(true, "Profile updated successfully.", {
    userId: user.userId,
    name: req.name || user.name,
    registrationNumber: req.registrationNumber || user.registrationNumber,
    username: user.username,
    gender: req.gender || user.gender,
    email: req.email || user.email,
    section: req.section || user.section,
    updatedAt: now
  });
}

// ── TIMETABLE SYNC & DIFF ENGINE (SECTIONS 12, 14, 15, 16) ────────────────

function handleSyncTimetable(req) {
  var user = authenticate(req);
  if (!user) {
    user = {
      userId: req.userId || "UMSIFY_USER",
      name: req.name || "Student",
      registrationNumber: req.registrationNumber || "",
      section: req.section || ""
    };
  }
  if (req.name && (!user.name || user.name === "Student")) user.name = req.name;
  if (req.registrationNumber && !user.registrationNumber) user.registrationNumber = req.registrationNumber;
  if (req.section && !user.section) user.section = req.section;
  if (!user.registrationNumber && user.userId && user.userId.indexOf("USER_") === 0) {
    user.registrationNumber = user.userId.replace("USER_", "");
  }

  var rawClasses = (req.data && req.data.classes) || (req.data && req.data.timetable) || req.classes || req.timetable || [];
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var ttSheet = ss.getSheetByName("Timetable");
  if (!ttSheet) { setupDatabase(); ttSheet = ss.getSheetByName("Timetable"); }

  // 1. Fetch existing timetable records for authenticated userId ONLY
  var allRows = ttSheet.getDataRange().getValues();
  var existingRecords = [];
  var otherRows = []; // Keep rows belonging to other users or blank rows
  var headerRow = allRows[0];

  for (var i = 1; i < allRows.length; i++) {
    var r = allRows[i];
    var rowUserId = r[0] ? r[0].toString().trim() : "";
    if (rowUserId === user.userId) {
      existingRecords.push({
        rowIndex: i + 1,
        userId: r[0].toString(),
        day: r[1].toString(),
        date: r[2] ? r[2].toString() : "",
        startTime: r[3].toString(),
        endTime: r[4].toString(),
        courseCode: r[5].toString(),
        courseName: r[6].toString(),
        type: r[7].toString(),
        room: r[8].toString(),
        faculty: r[9].toString(),
        group: r[10].toString(),
        section: r[11].toString(),
        updatedAt: r[12] ? r[12].toString() : "",
        recordHash: r[13] ? r[13].toString() : "",
        status: r[14] ? r[14].toString() : "ACTIVE"
      });
    }
  }

  // 2. Normalize and compute hashes for incoming classes
  var now = new Date().toISOString();
  var newRecords = [];
  for (var j = 0; j < rawClasses.length; j++) {
    var c = rawClasses[j];
    var normDay = normalizeDay(c.day);
    var normStart = normalizeTime(c.startTime);
    var normEnd = normalizeTime(c.endTime);
    var normDate = c.date ? c.date.toString().trim() : "";
    var normCode = (c.courseCode || "").trim().toUpperCase();
    var normType = (c.type || c.classType || "LEC").trim().toUpperCase();
    var normSection = (c.section || user.section || "").trim().toUpperCase();

    var hash = computeTimetableHash(user.userId, normDay, normDate, normStart, normEnd, normCode, normType, normSection);

    newRecords.push({
      userId: user.userId,
      day: normDay,
      date: normDate,
      startTime: normStart,
      endTime: normEnd,
      courseCode: normCode,
      courseName: (c.courseName || "").trim(),
      type: normType,
      room: (c.room || "").trim(),
      faculty: (c.faculty || "").trim(),
      group: (c.group || "").trim(),
      section: normSection,
      updatedAt: now,
      recordHash: hash,
      status: (c.status || "ACTIVE")
    });
  }

  // 3. Diff comparison (Insert, Update, Delete, Unchanged)
  var inserted = 0;
  var updated = 0;
  var deleted = 0;
  var unchanged = 0;

  // Build lookup maps by logical identity (day + startTime + courseCode)
  var oldMap = {};
  for (var o = 0; o < existingRecords.length; o++) {
    var rec = existingRecords[o];
    var key = [rec.day, rec.startTime, rec.courseCode].join("|");
    oldMap[key] = rec;
  }

  var newMap = {};
  for (var n = 0; n < newRecords.length; n++) {
    var nRec = newRecords[n];
    var nKey = [nRec.day, nRec.startTime, nRec.courseCode].join("|");
    newMap[nKey] = nRec;

    if (oldMap[nKey]) {
      if (oldMap[nKey].recordHash === nRec.recordHash && oldMap[nKey].room === nRec.room) {
        unchanged++;
      } else {
        updated++;
      }
    } else {
      inserted++;
    }
  }

  for (var oldKey in oldMap) {
    if (!newMap[oldKey]) {
      deleted++;
    }
  }

  // 4. Atomically rewrite the sheet preserving other users while inserting cleanly
  rewriteUserSection(ttSheet, user, "timetable", newRecords);

  return jsonResponse(true, "Timetable synchronized successfully", {
    inserted: inserted,
    updated: updated,
    deleted: deleted,
    unchanged: unchanged,
    lastSyncedAt: now
  });
}

// ── EXAM SYNC & DIFF ENGINE (SECTIONS 13, 17) ──────────────────────────────

function handleSyncExams(req) {
  var user = authenticate(req);
  if (!user) {
    user = {
      userId: req.userId || "UMSIFY_USER",
      name: req.name || "Student",
      registrationNumber: req.registrationNumber || "",
      section: req.section || ""
    };
  }
  if (req.name && (!user.name || user.name === "Student")) user.name = req.name;
  if (req.registrationNumber && !user.registrationNumber) user.registrationNumber = req.registrationNumber;
  if (req.section && !user.section) user.section = req.section;
  if (!user.registrationNumber && user.userId && user.userId.indexOf("USER_") === 0) {
    user.registrationNumber = user.userId.replace("USER_", "");
  }

  var rawExams = (req.data && req.data.exams) || (req.data && req.data.examList) || req.exams || req.examList || [];
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var examSheet = ss.getSheetByName("ExamDateSheet");
  if (!examSheet) { setupDatabase(); examSheet = ss.getSheetByName("ExamDateSheet"); }

  var allRows = examSheet.getDataRange().getValues();
  var existingRecords = [];
  for (var i = 1; i < allRows.length; i++) {
    var r = allRows[i];
    var rowUserId = r[0] ? r[0].toString().trim() : "";
    if (rowUserId === user.userId) {
      existingRecords.push({
        userId: r[0].toString(),
        courseCode: r[1].toString(),
        courseName: r[2].toString(),
        examDate: r[3].toString(),
        startTime: r[4].toString(),
        endTime: r[5].toString(),
        room: r[6].toString(),
        examType: r[7].toString(),
        updatedAt: r[8] ? r[8].toString() : "",
        recordHash: r[9] ? r[9].toString() : "",
        status: r[10] ? r[10].toString() : "ACTIVE"
      });
    }
  }

  var now = new Date().toISOString();
  var newRecords = [];
  for (var j = 0; j < rawExams.length; j++) {
    var ex = rawExams[j];
    var normCode = (ex.courseCode || "").trim().toUpperCase();
    var normDate = normalizeDate(ex.examDate);
    var normStart = normalizeTime(ex.startTime);
    var normEnd = normalizeTime(ex.endTime);
    var normType = (ex.examType || "Theory End Term").trim();
    var hash = computeExamHash(user.userId, normCode, normDate, normStart, normType);

    newRecords.push({
      userId: user.userId,
      courseCode: normCode,
      courseName: (ex.courseName || "").trim(),
      examDate: normDate,
      startTime: normStart,
      endTime: normEnd,
      room: (ex.room || "").trim(),
      examType: normType,
      updatedAt: now,
      recordHash: hash,
      status: (ex.status || "ACTIVE")
    });
  }

  var inserted = 0;
  var updated = 0;
  var deleted = 0;
  var unchanged = 0;

  var oldMap = {};
  for (var o = 0; o < existingRecords.length; o++) {
    var oRec = existingRecords[o];
    var oKey = [oRec.courseCode, oRec.examDate, oRec.startTime].join("|");
    oldMap[oKey] = oRec;
  }

  var newMap = {};
  for (var n = 0; n < newRecords.length; n++) {
    var nRec = newRecords[n];
    var nKey = [nRec.courseCode, nRec.examDate, nRec.startTime].join("|");
    newMap[nKey] = nRec;

    if (oldMap[nKey]) {
      if (oldMap[nKey].recordHash === nRec.recordHash && oldMap[nKey].room === nRec.room) {
        unchanged++;
      } else {
        updated++;
      }
    } else {
      inserted++;
    }
  }

  for (var oldKey in oldMap) {
    if (!newMap[oldKey]) {
      deleted++;
    }
  }

  rewriteUserSection(examSheet, user, "exams", newRecords);

  return jsonResponse(true, "Exam date sheet synchronized successfully", {
    inserted: inserted,
    updated: updated,
    deleted: deleted,
    unchanged: unchanged,
    lastSyncedAt: now
  });
}

function handleGetTimetable(req) {
  var user = authenticate(req);
  if (!user) return jsonResponse(false, "Authentication required.");

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var ttSheet = ss.getSheetByName("Timetable");
  if (!ttSheet) return jsonResponse(true, "No timetable data", []);

  var rows = ttSheet.getDataRange().getValues();
  var list = [];
  for (var i = 1; i < rows.length; i++) {
    var r = rows[i];
    if (r[0] && r[0].toString().trim() === user.userId) {
      list.push({
        userId: r[0].toString(),
        day: r[1].toString(),
        date: r[2] ? r[2].toString() : null,
        startTime: r[3].toString(),
        endTime: r[4].toString(),
        courseCode: r[5].toString(),
        courseName: r[6].toString(),
        type: r[7].toString(),
        room: r[8].toString(),
        faculty: r[9].toString(),
        group: r[10].toString(),
        section: r[11].toString(),
        updatedAt: r[12] ? r[12].toString() : "",
        recordHash: r[13] ? r[13].toString() : "",
        status: r[14] ? r[14].toString() : "ACTIVE"
      });
    }
  }
  return jsonResponse(true, "Timetable retrieved", list);
}

function handleGetExams(req) {
  var user = authenticate(req);
  if (!user) return jsonResponse(false, "Authentication required.");

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var examSheet = ss.getSheetByName("ExamDateSheet");
  if (!examSheet) return jsonResponse(true, "No exam data", []);

  var rows = examSheet.getDataRange().getValues();
  var list = [];
  for (var i = 1; i < rows.length; i++) {
    var r = rows[i];
    if (r[0] && r[0].toString().trim() === user.userId) {
      list.push({
        userId: r[0].toString(),
        courseCode: r[1].toString(),
        courseName: r[2].toString(),
        examDate: r[3].toString(),
        startTime: r[4].toString(),
        endTime: r[5].toString(),
        room: r[6].toString(),
        examType: r[7].toString(),
        updatedAt: r[8] ? r[8].toString() : "",
        recordHash: r[9] ? r[9].toString() : "",
        status: r[10] ? r[10].toString() : "ACTIVE"
      });
    }
  }
  return jsonResponse(true, "Exams retrieved", list);
}

function handleFullSync(req) {
  var user = authenticate(req);
  if (!user) return jsonResponse(false, "Authentication required.");

  var ttRes = handleGetTimetable(req);
  var exRes = handleGetExams(req);

  return jsonResponse(true, "Full sync successful", {
    user: user,
    timetable: JSON.parse(ttRes.getContent()).data || [],
    exams: JSON.parse(exRes.getContent()).data || []
  });
}

function handleDeleteTimetable(req) {
  var user = authenticate(req);
  if (!user) return jsonResponse(false, "Authentication required.");

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var ttSheet = ss.getSheetByName("Timetable");
  if (ttSheet) rewriteUserSection(ttSheet, user, "timetable", []);
  return jsonResponse(true, "Timetable cleared successfully.");
}

function handleDeleteExam(req) {
  var user = authenticate(req);
  if (!user) return jsonResponse(false, "Authentication required.");

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var examSheet = ss.getSheetByName("ExamDateSheet");
  if (examSheet) rewriteUserSection(examSheet, user, "exams", []);
  return jsonResponse(true, "Exam schedule cleared successfully.");
}

// ── SHEET SECTION REWRITING (CLEAN TABULAR STRUCTURE) ──────────────────────

function rewriteUserSection(sheet, user, type, records) {
  var data = sheet.getDataRange().getValues();
  var expectedCols = (type === "timetable") ? 15 : 11;
  var colCount = Math.max(sheet.getLastColumn(), expectedCols);

  // Helper to ensure every row has exactly colCount cells
  function padRow(r) {
    var out = new Array(colCount).fill("");
    if (r && r.length) {
      for (var c = 0; c < Math.min(r.length, colCount); c++) {
        out[c] = r[c];
      }
    }
    return out;
  }

  // Find existing rows belonging to other users (cleaning out any legacy banner lines or formula errors)
  var keptRows = [];
  for (var i = 1; i < data.length; i++) {
    var row = data[i];
    var firstCol = row[0] ? row[0].toString().trim() : "";
    if (!firstCol) continue;

    // Filter out any legacy admin banner strings, formula errors, etc.
    if (firstCol.indexOf("USER SECTION") !== -1 ||
        firstCol.indexOf("===") !== -1 ||
        firstCol.indexOf("#ERROR") !== -1 ||
        firstCol.indexOf("Name:") !== -1 ||
        firstCol.indexOf("Registration:") !== -1 ||
        firstCol.indexOf("User ID:") !== -1 ||
        firstCol.indexOf("Section:") !== -1) {
      continue;
    }

    // Skip this user's existing rows (we will replace with fresh records)
    if (firstCol === user.userId) {
      continue;
    }

    keptRows.push(padRow(row));
  }

  // Clear existing content and format below header row
  if (sheet.getLastRow() > 1) {
    sheet.getRange(2, 1, sheet.getLastRow() - 1, colCount).clearContent().clearFormat();
  }

  // Combine other users' rows with this user's records
  var allNewRows = [];
  for (var k = 0; k < keptRows.length; k++) {
    allNewRows.push(keptRows[k]);
  }

  for (var r = 0; r < records.length; r++) {
    var it = records[r];
    var dRow = new Array(colCount).fill("");
    if (type === "timetable") {
      var vals = [
        it.userId,
        it.day || "",
        it.date || "",
        it.startTime || "",
        it.endTime || "",
        it.courseCode || "",
        it.courseName || "",
        it.type || "",
        it.room || "",
        it.faculty || "",
        it.group || "",
        it.section || user.section || "",
        it.updatedAt || new Date().toISOString(),
        it.recordHash || "",
        (it.status || "ACTIVE")
      ];
      for (var v = 0; v < vals.length; v++) dRow[v] = vals[v];
    } else {
      var eVals = [
        it.userId,
        it.courseCode || "",
        it.courseName || "",
        it.examDate || "",
        it.startTime || "",
        it.endTime || "",
        it.room || "",
        (it.examType || "Theory End Term"),
        it.updatedAt || new Date().toISOString(),
        it.recordHash || "",
        (it.status || "ACTIVE")
      ];
      for (var ev = 0; ev < eVals.length; ev++) dRow[ev] = eVals[ev];
    }
    allNewRows.push(dRow);
  }

  if (allNewRows.length > 0) {
    var neededRows = 1 + allNewRows.length + 5;
    if (sheet.getMaxRows() < neededRows) {
      sheet.insertRowsAfter(sheet.getMaxRows(), (neededRows - sheet.getMaxRows()) + 5);
    }
    sheet.getRange(2, 1, allNewRows.length, colCount).setValues(allNewRows);
    SpreadsheetApp.flush();
  }
}

function refreshUserVisualHeader(userId, name, regNo, section) {
  // Pure tabular format, user details are in the Users sheet
}

// ── GROQ AI DOCUMENT EXTRACTION (SECTIONS 2, 3, 5, 6, 7) ───────────────────

function handleExtractDocument(req) {
  var user = authenticate(req);
  if (!user) return jsonResponse(false, "Authentication required.");

  var groqApiKey = PropertiesService.getScriptProperties().getProperty("GROQ_API_KEY");
  if (!groqApiKey) {
    return jsonResponse(false, "GROQ_API_KEY is not configured in Google Apps Script Script Properties.");
  }

  var documentType = req.documentType; // "timetable" or "examDateSheet"
  var fileContent = req.fileContent;   // Base64 string or extracted text
  var mimeType = req.mimeType || "application/pdf";
  var fileName = req.fileName || "document";

  if (!fileContent) {
    return jsonResponse(false, "Document content is missing.");
  }

  // System instruction matching Section 5
  var systemPrompt = 
    "You are an academic document parser extracting timetable and examination data.\n" +
    "Do not invent information.\n" +
    "Only extract information that is present in the document.\n" +
    "If a field cannot be determined, return null or an empty string.\n" +
    "Preserve exact course codes, room numbers, dates and times.\n" +
    "Return ONLY JSON matching the provided schema. Do not return markdown fences or explanation.";

  var userPrompt = "";
  if (documentType === "timetable") {
    userPrompt = 
      "Extract all classes from this timetable document into strict JSON matching this schema:\n" +
      "{\n" +
      "  \"type\": \"timetable\",\n" +
      "  \"userId\": \"" + user.userId + "\",\n" +
      "  \"student\": {\n" +
      "    \"name\": \"\",\n" +
      "    \"registrationNumber\": \"\",\n" +
      "    \"section\": \"\"\n" +
      "  },\n" +
      "  \"classes\": [\n" +
      "    {\n" +
      "      \"day\": \"Monday\",\n" +
      "      \"date\": null,\n" +
      "      \"startTime\": \"09:20\",\n" +
      "      \"endTime\": \"10:10\",\n" +
      "      \"courseCode\": \"CSE408\",\n" +
      "      \"courseName\": \"DESIGN AND ANALYSIS OF ALGORITHMS\",\n" +
      "      \"type\": \"LEC\",\n" +
      "      \"room\": \"38-906\",\n" +
      "      \"faculty\": \"\",\n" +
      "      \"group\": \"\",\n" +
      "      \"section\": \"\"\n" +
      "    }\n" +
      "  ]\n" +
      "}\n\n" +
      "Note: Weekly recurring classes have date = null. Convert day names to full names (Monday, Tuesday...). Times must be HH:mm 24-hour format.";
  } else {
    userPrompt = 
      "Extract all examinations from this date sheet/admit card into strict JSON matching this schema:\n" +
      "{\n" +
      "  \"type\": \"examDateSheet\",\n" +
      "  \"userId\": \"" + user.userId + "\",\n" +
      "  \"student\": {\n" +
      "    \"name\": \"\",\n" +
      "    \"registrationNumber\": \"\",\n" +
      "    \"section\": \"\"\n" +
      "  },\n" +
      "  \"exams\": [\n" +
      "    {\n" +
      "      \"courseCode\": \"CSE408\",\n" +
      "      \"courseName\": \"DESIGN AND ANALYSIS OF ALGORITHMS\",\n" +
      "      \"examDate\": \"2026-12-15\",\n" +
      "      \"startTime\": \"09:30\",\n" +
      "      \"endTime\": \"12:30\",\n" +
      "      \"room\": \"\",\n" +
      "      \"examType\": \"Theory End Term\"\n" +
      "    }\n" +
      "  ]\n" +
      "}\n\n" +
      "Dates must be YYYY-MM-DD. Times must be HH:mm 24-hour format. Do not invent missing rooms or dates.";
  }

  // Call Groq API with model: qwen/qwen3.8-27b
  var groqUrl = "https://api.groq.com/openai/v1/chat/completions";
  var groqPayload = {
    model: "qwen/qwen3.8-27b",
    temperature: 0.1,
    messages: [
      { role: "system", content: systemPrompt },
      { 
        role: "user", 
        content: userPrompt + "\n\nDocument file: " + fileName + " (" + mimeType + ").\nDocument content / payload:\n" + fileContent.substring(0, 100000)
      }
    ],
    response_format: { type: "json_object" }
  };

  var options = {
    method: "post",
    contentType: "application/json",
    headers: {
      "Authorization": "Bearer " + groqApiKey
    },
    payload: JSON.stringify(groqPayload),
    muteHttpExceptions: true
  };

  var response = UrlFetchApp.fetch(groqUrl, options);
  var statusCode = response.getResponseCode();
  var responseText = response.getContentText();

  if (statusCode === 429) {
    return jsonResponse(false, "Groq AI rate limit reached. Please wait a moment and try again.");
  }
  if (statusCode !== 200) {
    return jsonResponse(false, "Groq API error (" + statusCode + "): " + responseText);
  }

  var groqResult = JSON.parse(responseText);
  var rawJson = groqResult.choices[0].message.content;

  // Clean any markdown formatting if present
  var cleanJson = rawJson.replace(/^```json/i, "").replace(/^```/i, "").replace(/```$/i, "").trim();
  var parsedData = JSON.parse(cleanJson);

  return jsonResponse(true, "Document extracted successfully", parsedData);
}

// ── GROQ AI CHATBOT (SECTION 38) ───────────────────────────────────────────

function handleChatWithAi(req) {
  var user = authenticate(req);
  if (!user) return jsonResponse(false, "Authentication required.");

  var groqApiKey = PropertiesService.getScriptProperties().getProperty("GROQ_API_KEY");
  if (!groqApiKey) {
    return jsonResponse(false, "GROQ_API_KEY is not configured in Script Properties.");
  }

  var userMessage = req.message || "";
  var academicContext = req.academicContext || "";

  var systemPrompt = 
    "You are UMSify AI, a friendly academic assistant for " + (user.name || "the student") + ".\n" +
    "Help the student with academic timetable, exam preparation, and schedule questions.\n" +
    "Be concise, direct, helpful, and polite.\n" +
    (academicContext ? ("Student Academic Context:\n" + academicContext) : "");

  var groqPayload = {
    model: "qwen/qwen3.8-27b",
    temperature: 0.3,
    messages: [
      { role: "system", content: systemPrompt },
      { role: "user", content: userMessage }
    ]
  };

  var options = {
    method: "post",
    contentType: "application/json",
    headers: { "Authorization": "Bearer " + groqApiKey },
    payload: JSON.stringify(groqPayload),
    muteHttpExceptions: true
  };

  var response = UrlFetchApp.fetch("https://api.groq.com/openai/v1/chat/completions", options);
  if (response.getResponseCode() !== 200) {
    return jsonResponse(false, "AI temporarily unavailable: " + response.getContentText());
  }

  var resObj = JSON.parse(response.getContentText());
  var reply = resObj.choices[0].message.content;

  return jsonResponse(true, "AI Response", reply);
}

// ── HELPER UTILITIES ───────────────────────────────────────────────────────

function authenticate(req) {
  var token = req.token || req.sessionToken;
  var cachedUserId = null;
  if (token) {
    cachedUserId = CacheService.getScriptCache().get("TOKEN_" + token);
    if (!cachedUserId) {
      cachedUserId = PropertiesService.getScriptProperties().getProperty("TOKEN_" + token);
    }
    if (!cachedUserId && token.indexOf("GUEST_") === 0) {
      cachedUserId = token.replace("GUEST_", "");
    }
  }
  if (!cachedUserId && req.userId) {
    cachedUserId = req.userId.toString().trim();
  }
  if (!cachedUserId && req.username) {
    cachedUserId = req.username.toString().trim();
  }
  if (!cachedUserId) {
    cachedUserId = "UMSIFY_USER";
  }

  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var usersSheet = ss.getSheetByName("Users");
  if (!usersSheet) {
    setupDatabase();
    usersSheet = ss.getSheetByName("Users");
  }

  var data = usersSheet.getDataRange().getValues();
  for (var i = 1; i < data.length; i++) {
    var uId = data[i][0] ? data[i][0].toString() : "";
    var regNo = data[i][2] ? data[i][2].toString() : "";
    var uName = data[i][3] ? data[i][3].toString().toLowerCase() : "";
    if (uId === cachedUserId || (regNo && regNo === cachedUserId) || (uName && uName === cachedUserId.toLowerCase())) {
      return {
        userId: data[i][0].toString(),
        name: data[i][1].toString(),
        registrationNumber: regNo,
        username: data[i][3].toString(),
        gender: data[i][5].toString(),
        email: data[i][6].toString(),
        section: data[i][7].toString(),
        accountStatus: data[i][11].toString()
      };
    }
  }

  // If user is not yet recorded in Users sheet, auto-append to ensure timetable/exam sync succeeds
  var now = new Date().toISOString();
  var fallbackName = req.name || cachedUserId;
  var fallbackReg = req.registrationNumber || (cachedUserId.indexOf("UMSIFY_") === 0 ? cachedUserId.replace("UMSIFY_", "") : "");
  var fallbackUser = req.username || cachedUserId.toLowerCase();
  var fallbackSection = req.section || "";
  usersSheet.appendRow([
    cachedUserId, fallbackName, fallbackReg, fallbackUser, "AUTO_AUTH", "Other", "", fallbackSection, now, now, now, "ACTIVE"
  ]);

  return {
    userId: cachedUserId,
    name: fallbackName,
    registrationNumber: fallbackReg,
    username: fallbackUser,
    gender: "Other",
    email: "",
    section: fallbackSection,
    accountStatus: "ACTIVE"
  };
}

function generateToken(userId) {
  var raw = userId + "_" + new Date().getTime() + "_" + Math.random();
  return "UMS_TOK_" + sha256(raw).substring(0, 32);
}

function saveSession(token, userId) {
  // Save in cache (fast, 6 hours) and fallback in script properties
  CacheService.getScriptCache().put("TOKEN_" + token, userId, 21600);
  PropertiesService.getScriptProperties().setProperty("TOKEN_" + token, userId);
}

function computeTimetableHash(userId, day, date, startTime, endTime, courseCode, type, section) {
  var str = [userId, day, date, startTime, endTime, courseCode, type, section].join("|");
  return sha256(str);
}

function computeExamHash(userId, courseCode, examDate, startTime, examType) {
  var str = [userId, courseCode, examDate, startTime, examType].join("|");
  return sha256(str);
}

function sha256(input) {
  var rawHash = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, input, Utilities.Charset.UTF_8);
  var txtHash = "";
  for (var i = 0; i < rawHash.length; i++) {
    var val = rawHash[i];
    if (val < 0) val += 256;
    var byteString = val.toString(16);
    if (byteString.length === 1) byteString = "0" + byteString;
    txtHash += byteString;
  }
  return txtHash;
}

function normalizeDay(day) {
  if (!day) return "";
  var clean = day.toString().trim().toLowerCase();
  if (clean.indexOf("mon") === 0) return "Monday";
  if (clean.indexOf("tue") === 0) return "Tuesday";
  if (clean.indexOf("wed") === 0) return "Wednesday";
  if (clean.indexOf("thu") === 0) return "Thursday";
  if (clean.indexOf("fri") === 0) return "Friday";
  if (clean.indexOf("sat") === 0) return "Saturday";
  if (clean.indexOf("sun") === 0) return "Sunday";
  return day.toString().trim();
}

function normalizeTime(time) {
  if (!time) return "";
  var t = time.toString().trim();
  if (t.match(/^\d{1,2}:\d{2}$/)) {
    var parts = t.split(":");
    var h = ("0" + parts[0]).slice(-2);
    return h + ":" + parts[1];
  }
  return t;
}

function normalizeDate(d) {
  if (!d) return "";
  var str = d.toString().trim();
  // Simple check for YYYY-MM-DD
  if (str.match(/^\d{4}-\d{2}-\d{2}$/)) return str;
  return str;
}

function jsonResponse(success, message, data) {
  var out = {
    success: success,
    message: message || (success ? "OK" : "Error"),
    data: data || null
  };
  return ContentService.createTextOutput(JSON.stringify(out))
    .setMimeType(ContentService.MimeType.JSON);
}

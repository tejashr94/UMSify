# UMSify Google Apps Script Backend Setup Guide

This Google Apps Script acts as the secure serverless backend and bridge between the Android application, Google Sheets (database), and Groq AI (`qwen/qwen3.8-27b`).

---

## 1. Create Google Sheet

1. Go to [Google Sheets](https://sheets.new) and create a **new blank spreadsheet**.
2. Name the spreadsheet: **UMSify Master Database**.

---

## 2. Install Google Apps Script Code

1. In your Google Sheet, click **Extensions > Apps Script**.
2. Delete any existing code in the editor.
3. Copy the entire contents of [`Code.gs`](file:///e:/desktop%203rd%20sem%20mid/browseums/UMSify/backend/Code.gs) and paste it into the Apps Script editor.
4. Save the project as **UMSify Backend**.
5. In the toolbar, select the function `setupDatabase` from the dropdown and click **Run**.
   - Review permissions and click **Allow**.
   - This creates and formats the three required sheets:
     - `Users`
     - `Timetable`
     - `ExamDateSheet`

---

## 3. Set Groq API Key Securely (Script Properties)

The Groq API key is **NEVER placed inside Android source code or the APK**. It is stored exclusively in Apps Script Script Properties.

1. In the Apps Script editor, click **Project Settings** (gear icon on the left sidebar).
2. Scroll down to **Script Properties**.
3. Click **Add script property**:
   - **Property**: `GROQ_API_KEY`
   - **Value**: `<Your Groq API Key starting with gsk_...>`
4. Click **Save script properties**.

---

## 4. Deploy as Web App

1. In the top right corner, click **Deploy > New deployment**.
2. Click the gear icon next to "Select type" and choose **Web app**.
3. Configure the settings:
   - **Description**: `UMSify Production Web App`
   - **Execute as**: `Me (<your_email@gmail.com>)`
   - **Who has access**: `Anyone` *(Crucial so the mobile app can reach the API)*
4. Click **Deploy**.
5. Copy the **Web App URL** (e.g. `https://script.google.com/macros/s/AKfycbx.../exec`).

---

## 5. Configure Android App

Open [`local.properties`](file:///e:/desktop%203rd%20sem%20mid/browseums/UMSify/local.properties) in the root of the Android project:

```properties
APPS_SCRIPT_URL=https://script.google.com/macros/s/AKfycbx.../exec
```

Replace the placeholder with your actual deployed Web App URL.

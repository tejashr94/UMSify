package com.umsify.app.ui.chatbot

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.umsify.app.data.local.AppDatabase
import com.umsify.app.data.model.ChatMessage
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.data.repository.UserDataRepository
import com.umsify.app.databinding.ActivityAiChatbotBinding
import com.umsify.app.databinding.ItemChatMessageBinding
import com.umsify.app.utils.SessionManager
import com.umsify.app.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import java.util.regex.Pattern

/**
 * AiChatbotActivity — Academic Assistant Chatbot matching Section 38:
 * - Solves deterministic questions locally from Room cache (zero network/Groq calls)
 * - Uses Groq qwen/qwen3.8-27b through backend only for open-ended questions
 */
class AiChatbotActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAiChatbotBinding
    private lateinit var repository: UserDataRepository
    private lateinit var sessionManager: SessionManager
    private lateinit var db: AppDatabase

    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAiChatbotBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sessionManager = SessionManager.getInstance(this)
        db = AppDatabase.getInstance(this)
        repository = UserDataRepository(db, sessionManager)

        setupToolbar()
        setupRecyclerView()
        setupInput()
        setupSuggestionChips()

        // Welcome message
        val studentName = sessionManager.getUserName().split(" ").firstOrNull() ?: "there"
        messages.add(
            ChatMessage(
                role = "assistant",
                content = "Hi $studentName! 👋 I'm your UMSify academic assistant. Ask me anything about your timetable, classes, or upcoming exams."
            )
        )
        adapter.notifyItemInserted(0)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        adapter = ChatAdapter()
        binding.rvChatMessages.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvChatMessages.adapter = adapter
    }

    private fun setupInput() {
        binding.btnSendMessage.setOnClickListener {
            val text = binding.etChatInput.text.toString().trim()
            if (text.isNotBlank()) {
                sendMessage(text)
                binding.etChatInput.setText("")
            }
        }
    }

    private fun setupSuggestionChips() {
        binding.chipNextClass.setOnClickListener { sendMessage("What is my next class?") }
        binding.chipTodayClasses.setOnClickListener { sendMessage("How many classes do I have today?") }
        binding.chipNextExam.setOnClickListener { sendMessage("When is my next exam?") }
        binding.chipRoom.setOnClickListener { sendMessage("Which room is my next class?") }
    }

    private fun sendMessage(text: String) {
        messages.add(ChatMessage(role = "user", content = text))
        val userIdx = messages.size - 1
        adapter.notifyItemInserted(userIdx)
        binding.rvChatMessages.scrollToPosition(userIdx)

        lifecycleScope.launch {
            val userId = sessionManager.getUserId()

            // 1. FAST LOCAL DETERMINISTIC EVALUATION (Section 38)
            val localAnswer = withContext(Dispatchers.IO) {
                tryLocalDeterministicAnswer(text, userId)
            }

            if (localAnswer != null) {
                messages.add(ChatMessage(role = "assistant", content = localAnswer))
                adapter.notifyItemInserted(messages.size - 1)
                binding.rvChatMessages.scrollToPosition(messages.size - 1)
            } else {
                // 2. REMOTE GROQ EVALUATION VIA BACKEND
                messages.add(ChatMessage(role = "assistant", content = "Thinking…"))
                val thinkingIdx = messages.size - 1
                adapter.notifyItemInserted(thinkingIdx)
                binding.rvChatMessages.scrollToPosition(thinkingIdx)

                val contextSummary = withContext(Dispatchers.IO) {
                    buildAcademicContext(userId)
                }

                val result = withContext(Dispatchers.IO) {
                    repository.chatWithAi(text, contextSummary)
                }

                val reply = result.getOrElse {
                    "I couldn't reach the AI assistant right now. Please check your internet connection."
                }
                messages[thinkingIdx] = ChatMessage(role = "assistant", content = reply)
                adapter.notifyItemChanged(thinkingIdx)
            }
        }
    }

    /**
     * Resolves questions deterministically from Room without calling Groq.
     */
    private suspend fun tryLocalDeterministicAnswer(query: String, userId: String): String? {
        val q = query.lowercase(Locale.US)

        val dayFormat = SimpleDateFormat("EEEE", Locale.US)
        val today = dayFormat.format(Date())
        val todayClasses = db.timetableDao().getTimetableForDay(userId, today).sortedBy { it.startTime }

        val now = Calendar.getInstance()
        val currentTime = SimpleDateFormat("HH:mm", Locale.US).format(now.time)

        // 1. "How many classes do I have today?"
        if (q.contains("how many classes") || q.contains("classes today") || q.contains("today's classes")) {
            return if (todayClasses.isEmpty()) {
                "You have no classes scheduled for today ($today). Enjoy your day! 🎉"
            } else {
                val list = todayClasses.joinToString("\n") { "• ${it.startTime}–${it.endTime}: ${it.courseCode} (Room ${it.room})" }
                "You have ${todayClasses.size} classes today ($today):\n$list"
            }
        }

        // 2. "What is my next class?" / "Which room is my next class?"
        if (q.contains("next class") || q.contains("upcoming class") || (q.contains("room") && q.contains("next"))) {
            var nextClass: TimetableEntry? = null
            var currentClass: TimetableEntry? = null

            for (cls in todayClasses) {
                if (currentTime >= cls.startTime && currentTime < cls.endTime) {
                    currentClass = cls
                } else if (currentTime < cls.startTime && nextClass == null) {
                    nextClass = cls
                }
            }

            return when {
                currentClass != null && nextClass != null -> {
                    "Currently: ${currentClass.courseCode} in Room ${currentClass.room}.\nNext class is ${nextClass.courseCode} (${nextClass.courseName.ifBlank { nextClass.courseCode }}) at ${TimeUtils.formatTo12h(nextClass.startTime)} in Room ${nextClass.room}."
                }
                currentClass != null -> {
                    "Currently in ${currentClass.courseCode} in Room ${currentClass.room}. No more classes scheduled after this today!"
                }
                nextClass != null -> {
                    "Your next class is ${nextClass.courseCode} (${nextClass.courseName.ifBlank { nextClass.courseCode }}) at ${TimeUtils.formatTo12h(nextClass.startTime)} in Room ${nextClass.room}."
                }
                todayClasses.isNotEmpty() -> {
                    "All classes for today are complete! Rest well."
                }
                else -> {
                    "No classes scheduled for today ($today)."
                }
            }
        }

        // 3. "When is my <CODE> exam?"
        val codePattern = Pattern.compile("([a-z]{2,4}\\d{3})")
        val matcher = codePattern.matcher(q)
        if (matcher.find()) {
            val code = matcher.group(1)?.uppercase(Locale.US) ?: return null
            val exams = db.examDao().getExams(userId)
            val match = exams.firstOrNull { it.courseCode.equals(code, ignoreCase = true) }
            if (match != null) {
                val days = TimeUtils.daysUntil(match.examDate)
                val dayStr = when {
                    days == 0 -> "TODAY!"
                    days > 0 -> "$days days left"
                    else -> "Past"
                }
                return "Your $code exam is on ${TimeUtils.formatExamDate(match.examDate)} from ${match.startTime} to ${match.endTime} (${match.examType}). Room: ${match.room.ifBlank { "TBA" }}. ($dayStr)"
            }
        }

        // 4. "Next exam" / "When is my next exam?" / "How many exams"
        if (q.contains("next exam") || q.contains("upcoming exam") || q.contains("how many exams")) {
            val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val nextExam = db.examDao().getNextExam(userId, todayIso)
            val allExams = db.examDao().getExams(userId).filter { it.examDate >= todayIso }

            return if (nextExam != null) {
                val days = TimeUtils.daysUntil(nextExam.examDate)
                "You have ${allExams.size} upcoming exams.\nYour next exam is ${nextExam.courseCode} (${nextExam.courseName}) on ${TimeUtils.formatExamDate(nextExam.examDate)} ($days days left) in Room ${nextExam.room.ifBlank { "TBA" }}."
            } else {
                "No upcoming exams found in your schedule."
            }
        }

        return null // Not deterministic, pass to Groq
    }

    private suspend fun buildAcademicContext(userId: String): String {
        val today = SimpleDateFormat("EEEE", Locale.US).format(Date())
        val todayClasses = db.timetableDao().getTimetableForDay(userId, today)
        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val upcomingExams = db.examDao().getExams(userId).filter { it.examDate >= todayIso }.take(5)

        val sb = StringBuilder()
        sb.appendLine("Today is $today.")
        if (todayClasses.isNotEmpty()) {
            sb.appendLine("Today's classes:")
            todayClasses.forEach { sb.appendLine("  - ${it.courseCode}: ${it.startTime}-${it.endTime}, Room ${it.room}") }
        }
        if (upcomingExams.isNotEmpty()) {
            sb.appendLine("Upcoming exams:")
            upcomingExams.forEach { sb.appendLine("  - ${it.courseCode}: ${it.examDate} ${it.startTime}, Room ${it.room}") }
        }
        return sb.toString()
    }

    // ── RECYCLERVIEW ADAPTER ─────────────────────────────────────────────

    private inner class ChatAdapter : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

        inner class ViewHolder(val b: ItemChatMessageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemChatMessageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val msg = messages[position]
            val b = holder.b
            if (msg.role == "user") {
                b.layoutUserBubble.visibility = View.VISIBLE
                b.layoutAiBubble.visibility = View.GONE
                b.tvUserMessage.text = msg.content
            } else {
                b.layoutUserBubble.visibility = View.GONE
                b.layoutAiBubble.visibility = View.VISIBLE
                b.tvAiMessage.text = msg.content
            }
        }

        override fun getItemCount(): Int = messages.size
    }
}

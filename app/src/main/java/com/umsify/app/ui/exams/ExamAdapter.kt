package com.umsify.app.ui.exams

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.umsify.app.data.model.ExamEntry
import com.umsify.app.databinding.ItemExamBinding
import com.umsify.app.utils.TimeUtils

class ExamAdapter : ListAdapter<ExamEntry, ExamAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemExamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemExamBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(exam: ExamEntry) {
            binding.tvExamCode.text = exam.courseCode
            binding.tvExamName.text = exam.courseName.ifEmpty { exam.courseCode }
            binding.tvExamDate.text = TimeUtils.formatExamDate(exam.examDate)
            binding.tvExamTime.text = "${TimeUtils.formatTo12h(exam.startTime)} – ${TimeUtils.formatTo12h(exam.endTime)}"
            binding.tvExamRoom.text = if (exam.room.isNotEmpty()) "Room: ${exam.room}" else ""
            binding.tvExamType.text = exam.examType

            val daysLeft = TimeUtils.daysUntil(exam.examDate)
            binding.tvDaysLeft.text = when {
                daysLeft == 0 -> "TODAY!"
                daysLeft > 0 -> "$daysLeft DAYS LEFT"
                else -> "COMPLETED"
            }

            val ctx = binding.root.context
            val color = when {
                daysLeft < 0 -> ctx.getColor(com.umsify.app.R.color.colorCompleted)
                daysLeft <= 3 -> ctx.getColor(android.R.color.holo_red_light)
                daysLeft <= 7 -> ctx.getColor(com.umsify.app.R.color.colorHoliday)
                else -> ctx.getColor(com.umsify.app.R.color.colorAccentLight)
            }
            binding.tvDaysLeft.setTextColor(color)

            binding.root.alpha = if (daysLeft < 0) 0.55f else 1f
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<ExamEntry>() {
            override fun areItemsTheSame(a: ExamEntry, b: ExamEntry) =
                a.courseCode == b.courseCode && a.examDate == b.examDate && a.examType == b.examType
            override fun areContentsTheSame(a: ExamEntry, b: ExamEntry) = a == b
        }
    }
}

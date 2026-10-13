package com.umsify.app.ui.timetable

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.databinding.ItemClassBinding
import com.umsify.app.utils.TimeUtils

class TimetableAdapter : ListAdapter<TimetableEntry, TimetableAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClassBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemClassBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: TimetableEntry) {
            binding.tvCourseCode.text = entry.courseCode
            binding.tvCourseName.text = entry.courseName.ifEmpty { entry.courseCode }
            binding.tvTime.text = "${TimeUtils.formatTo12h(entry.startTime)} – ${TimeUtils.formatTo12h(entry.endTime)}"
            binding.tvRoom.text = if (entry.room.isNotEmpty()) "Room: ${entry.room}" else ""
            binding.tvType.text = entry.classType

            val state = TimeUtils.getClassState(entry)
            val ctx = binding.root.context

            when (state) {
                TimeUtils.ClassState.CURRENT -> {
                    binding.tvStatus.text = "● CURRENT"
                    binding.tvStatus.setTextColor(ctx.getColor(com.umsify.app.R.color.colorAccent))
                    binding.viewBar.setBackgroundColor(ctx.getColor(com.umsify.app.R.color.colorAccent))
                    binding.root.alpha = 1f
                    binding.root.elevation = 6f
                }
                TimeUtils.ClassState.COMPLETED -> {
                    binding.tvStatus.text = "✓ COMPLETED"
                    binding.tvStatus.setTextColor(ctx.getColor(com.umsify.app.R.color.colorCompleted))
                    binding.viewBar.setBackgroundColor(ctx.getColor(com.umsify.app.R.color.colorCompleted))
                    binding.root.alpha = 0.55f
                }
                TimeUtils.ClassState.UPCOMING -> {
                    binding.tvStatus.text = ""
                    binding.viewBar.setBackgroundColor(ctx.getColor(com.umsify.app.R.color.colorOnSurfaceVariant))
                    binding.root.alpha = 1f
                }
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TimetableEntry>() {
            override fun areItemsTheSame(a: TimetableEntry, b: TimetableEntry) =
                a.courseCode == b.courseCode && a.startTime == b.startTime && a.day == b.day
            override fun areContentsTheSame(a: TimetableEntry, b: TimetableEntry) = a == b
        }
    }
}

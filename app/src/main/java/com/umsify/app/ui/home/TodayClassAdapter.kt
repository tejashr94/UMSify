package com.umsify.app.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.umsify.app.data.model.TimetableEntry
import com.umsify.app.databinding.ItemClassHomeBinding
import com.umsify.app.utils.TimeUtils

/**
 * Adapter for today's class list on the Home screen.
 * Shows CURRENT / COMPLETED / UPCOMING state for each class.
 */
class TodayClassAdapter : ListAdapter<TimetableEntry, TodayClassAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClassHomeBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class ViewHolder(private val binding: ItemClassHomeBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: TimetableEntry) {
            binding.tvCourseCode.text = entry.courseCode
            binding.tvCourseName.text = entry.courseName.ifEmpty { entry.courseCode }
            binding.tvTime.text = "${TimeUtils.formatTo12h(entry.startTime)} – ${TimeUtils.formatTo12h(entry.endTime)}"
            binding.tvRoom.text = if (entry.room.isNotEmpty()) "Room ${entry.room}" else ""
            binding.tvType.text = entry.classType

            val state = TimeUtils.getClassState(entry)
            val ctx = binding.root.context

            when (state) {
                TimeUtils.ClassState.CURRENT -> {
                    binding.tvState.text = "CURRENT"
                    binding.tvState.setTextColor(ctx.getColor(com.umsify.app.R.color.colorAccent))
                    binding.viewStateBar.setBackgroundColor(ctx.getColor(com.umsify.app.R.color.colorAccent))
                    binding.root.alpha = 1f
                }
                TimeUtils.ClassState.COMPLETED -> {
                    binding.tvState.text = "COMPLETED"
                    binding.tvState.setTextColor(ctx.getColor(com.umsify.app.R.color.colorCompleted))
                    binding.viewStateBar.setBackgroundColor(ctx.getColor(com.umsify.app.R.color.colorCompleted))
                    binding.root.alpha = 0.6f
                }
                TimeUtils.ClassState.UPCOMING -> {
                    binding.tvState.text = "UPCOMING"
                    binding.tvState.setTextColor(ctx.getColor(com.umsify.app.R.color.colorOnSurfaceVariant))
                    binding.viewStateBar.setBackgroundColor(ctx.getColor(com.umsify.app.R.color.colorOnSurfaceVariant))
                    binding.root.alpha = 1f
                }
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<TimetableEntry>() {
            override fun areItemsTheSame(a: TimetableEntry, b: TimetableEntry) =
                a.courseCode == b.courseCode && a.startTime == b.startTime
            override fun areContentsTheSame(a: TimetableEntry, b: TimetableEntry) = a == b
        }
    }
}

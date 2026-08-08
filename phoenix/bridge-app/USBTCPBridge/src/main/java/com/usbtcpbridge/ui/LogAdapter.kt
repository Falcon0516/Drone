package com.usbtcpbridge.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.usbtcpbridge.R
import com.usbtcpbridge.databinding.ItemLogBinding

/**
 * RecyclerView adapter for displaying log messages
 */
class LogAdapter : ListAdapter<LogItem, LogAdapter.LogViewHolder>(LogDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(
            LayoutInflater.from(parent.context), 
            parent, 
            false
        )
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(
        private val binding: ItemLogBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: LogItem) {
            binding.logText.text = item.message
            
            val colorRes = when (item.type) {
                LogItem.LogType.INFO -> R.color.log_info
                LogItem.LogType.SUCCESS -> R.color.log_success
                LogItem.LogType.WARNING -> R.color.log_warning
                LogItem.LogType.ERROR -> R.color.log_error
                LogItem.LogType.DATA_IN -> R.color.log_data_in
                LogItem.LogType.DATA_OUT -> R.color.log_data_out
            }
            
            binding.logText.setTextColor(
                ContextCompat.getColor(binding.root.context, colorRes)
            )
        }
    }

    private class LogDiffCallback : DiffUtil.ItemCallback<LogItem>() {
        override fun areItemsTheSame(oldItem: LogItem, newItem: LogItem): Boolean {
            return oldItem.timestamp == newItem.timestamp && oldItem.message == newItem.message
        }

        override fun areContentsTheSame(oldItem: LogItem, newItem: LogItem): Boolean {
            return oldItem == newItem
        }
    }
}

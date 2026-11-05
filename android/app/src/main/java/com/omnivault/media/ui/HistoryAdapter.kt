package com.omnivault.media.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.omnivault.media.databinding.ItemDownloadHistoryBinding
import com.omnivault.media.model.DownloadItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onPlayClick: (DownloadItem) -> Unit,
    private val onShareClick: (DownloadItem) -> Unit,
    private val onDeleteClick: (DownloadItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val items = mutableListOf<DownloadItem>()
    private val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    fun updateList(newItems: List<DownloadItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemDownloadHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemDownloadHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DownloadItem) {
            binding.tvHistoryTitle.text = item.title
            binding.tvHistoryPlatform.text = item.platform.displayName
            binding.tvHistoryDate.text = dateFormat.format(Date(item.timestamp))
            binding.tvHistorySize.text = item.fileSizeFormatted

            binding.btnPlay.setOnClickListener { onPlayClick(item) }
            binding.btnShare.setOnClickListener { onShareClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }
}

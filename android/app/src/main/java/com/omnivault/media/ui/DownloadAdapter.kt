package com.omnivault.media.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.omnivault.media.databinding.ItemActiveDownloadBinding
import com.omnivault.media.model.DownloadItem

class DownloadAdapter(
    private val onCancelClick: (DownloadItem) -> Unit
) : RecyclerView.Adapter<DownloadAdapter.ViewHolder>() {

    private val items = mutableListOf<DownloadItem>()

    fun updateList(newItems: List<DownloadItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemActiveDownloadBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemActiveDownloadBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DownloadItem) {
            binding.tvItemTitle.text = item.title
            binding.progressBar.progress = item.progress
            binding.tvPercentage.text = "${item.progress}%"
            binding.tvDownloadSpeed.text = item.speedText
            binding.tvFormatBadge.text = item.format.extension.uppercase()

            binding.btnCancelDownload.setOnClickListener {
                onCancelClick(item)
            }
        }
    }
}

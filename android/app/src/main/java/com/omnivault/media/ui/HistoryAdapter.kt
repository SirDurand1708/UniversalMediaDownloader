package com.omnivault.media.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.omnivault.media.R
import com.omnivault.media.databinding.ItemDownloadHistoryBinding
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.SortOption
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private val onPlayClick: (DownloadItem) -> Unit,
    private val onShareClick: (DownloadItem) -> Unit,
    private val onDeleteClick: (DownloadItem) -> Unit,
    var onAddToPlaylistClick: ((DownloadItem) -> Unit)? = null
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val allItems = mutableListOf<DownloadItem>()
    private val items = mutableListOf<DownloadItem>()
    private var currentQuery: String = ""
    private var currentSort: SortOption = SortOption.DATE_DESC
    private val dateFormat = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault())

    fun updateList(newItems: List<DownloadItem>) {
        allItems.clear()
        allItems.addAll(newItems)
        applySortAndFilter()
    }

    fun setSortOption(sort: SortOption) {
        currentSort = sort
        applySortAndFilter()
    }

    fun filter(query: String) {
        currentQuery = query
        applySortAndFilter()
    }

    private fun applySortAndFilter() {
        val filtered = if (currentQuery.isBlank()) {
            allItems
        } else {
            val q = currentQuery.lowercase().trim()
            allItems.filter {
                it.title.lowercase().contains(q) ||
                (it.artist?.lowercase()?.contains(q) == true) ||
                it.platform.displayName.lowercase().contains(q)
            }
        }

        val sorted = when (currentSort) {
            SortOption.DATE_DESC -> filtered.sortedByDescending { it.timestamp }
            SortOption.DATE_ASC -> filtered.sortedBy { it.timestamp }
            SortOption.MOST_PLAYED -> filtered.sortedByDescending { it.playCount }
            SortOption.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            SortOption.NAME_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            SortOption.SIZE_DESC -> filtered.sortedByDescending { File(it.localFilePath ?: "").length() }
            SortOption.SIZE_ASC -> filtered.sortedBy { File(it.localFilePath ?: "").length() }
        }

        items.clear()
        items.addAll(sorted)
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
            binding.tvHistoryPlatform.text = item.artist ?: item.platform.displayName
            binding.tvHistoryDate.text = dateFormat.format(Date(item.timestamp))

            val file = item.localFilePath?.let { File(it) }
            val sizeStr = if (item.fileSizeFormatted.isNotBlank()) {
                item.fileSizeFormatted
            } else if (file != null && file.exists()) {
                String.format("%.1f MB", file.length() / (1024f * 1024f))
            } else {
                "Descargado"
            }
            binding.tvHistorySize.text = sizeStr

            binding.btnPlay.setOnClickListener { v ->
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
                    v.scaleX = 1f; v.scaleY = 1f
                    onPlayClick(item)
                }.start()
            }

            binding.btnShare.setOnClickListener { v ->
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
                    v.scaleX = 1f; v.scaleY = 1f
                    onShareClick(item)
                }.start()
            }

            binding.btnDelete.setOnClickListener { v ->
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
                    v.scaleX = 1f; v.scaleY = 1f
                    onDeleteClick(item)
                }.start()
            }

            // Botón Añadir a Playlist
            binding.btnAddToPlaylist.visibility = if (item.format.isAudio) View.VISIBLE else View.GONE
            binding.btnAddToPlaylist.setOnClickListener { v ->
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(70).withEndAction {
                    v.scaleX = 1f; v.scaleY = 1f
                    onAddToPlaylistClick?.invoke(item)
                }.start()
            }

            // Cargar carátula con Coil
            val thumb = item.thumbnailUrl
            if (!thumb.isNullOrBlank()) {
                val localFile = File(thumb)
                if (localFile.exists()) {
                    binding.imgHistoryCover.load(localFile) {
                        crossfade(true)
                        error(R.mipmap.ic_launcher)
                    }
                } else {
                    binding.imgHistoryCover.load(thumb) {
                        crossfade(true)
                        error(R.mipmap.ic_launcher)
                    }
                }
            } else {
                binding.imgHistoryCover.setImageResource(R.mipmap.ic_launcher)
            }
        }
    }
}

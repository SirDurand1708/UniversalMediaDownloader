package com.omnivault.media.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.omnivault.media.R
import com.omnivault.media.databinding.ItemVideoCardBinding
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.SortOption
import java.io.File

class VideoAdapter(
    private val onVideoClick: (DownloadItem) -> Unit,
    private val onShareClick: (DownloadItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

    private val allItems = mutableListOf<DownloadItem>()
    private val items = mutableListOf<DownloadItem>()
    private var currentQuery: String = ""
    private var currentSort: SortOption = SortOption.DATE_DESC

    fun updateList(newItems: List<DownloadItem>) {
        allItems.clear()
        // Filtrar exclusivamente videos válidos (con archivo local existente)
        allItems.addAll(newItems.filter { !it.format.isAudio && !it.localFilePath.isNullOrBlank() })
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
                it.platform.displayName.lowercase().contains(q)
            }
        }

        val sorted = when (currentSort) {
            SortOption.DATE_DESC -> filtered.sortedByDescending { it.timestamp }
            SortOption.DATE_ASC -> filtered.sortedBy { it.timestamp }
            SortOption.MOST_PLAYED -> filtered.sortedByDescending { it.playCount }
            SortOption.NAME_ASC -> filtered.sortedBy { it.title.lowercase() }
            SortOption.NAME_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            SortOption.SIZE_DESC -> filtered.sortedByDescending { if (it.fileSizeBytes > 0) it.fileSizeBytes else File(it.localFilePath ?: "").length() }
            SortOption.SIZE_ASC -> filtered.sortedBy { if (it.fileSizeBytes > 0) it.fileSizeBytes else File(it.localFilePath ?: "").length() }
        }

        items.clear()
        items.addAll(sorted)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemVideoCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemVideoCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DownloadItem) {
            binding.tvVideoTitle.text = item.title

            val resolution = if (item.format == MediaFormat.MP4_1080) "1080p FHD" else "720p HD"
            binding.tvVideoResolutionBadge.text = resolution

            val sizeMb = if (item.fileSizeFormatted.isNotBlank()) {
                item.fileSizeFormatted
            } else if (item.fileSizeBytes > 0) {
                String.format("%.1f MB", item.fileSizeBytes / (1024f * 1024f))
            } else {
                "Descargado"
            }
            binding.tvVideoSubtitle.text = "${item.platform.displayName} • MP4 • $sizeMb"

            // Duración formateada desde metadatos calculados en el escaneo
            val durationText = if (item.durationFormatted.isNotBlank()) {
                item.durationFormatted
            } else if (item.format == MediaFormat.MP4_1080) {
                "1080p FHD"
            } else {
                "720p HD"
            }
            binding.tvVideoDuration.text = durationText

            // Carga de Miniatura de Video con Coil (tamaño acotado 360x202 para reproducción fluida)
            val thumb = item.localFilePath ?: item.thumbnailUrl
            if (!thumb.isNullOrBlank()) {
                val model: Any = if (thumb.startsWith("http")) thumb else File(thumb)
                binding.imgVideoThumbnail.load(model) {
                    crossfade(true)
                    size(360, 202)
                    error(R.mipmap.ic_launcher)
                }
            } else {
                binding.imgVideoThumbnail.setImageResource(R.mipmap.ic_launcher)
            }

            binding.cardVideoRoot.setOnClickListener { onVideoClick(item) }
            binding.btnVideoPlayOverlay.setOnClickListener { onVideoClick(item) }
            binding.btnVideoShare.setOnClickListener { onShareClick(item) }
        }
    }
}

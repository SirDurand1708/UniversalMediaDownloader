package com.omnivault.media.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.omnivault.media.R
import com.omnivault.media.databinding.ItemVideoCardBinding
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import java.io.File

class VideoAdapter(
    private val onVideoClick: (DownloadItem) -> Unit,
    private val onShareClick: (DownloadItem) -> Unit
) : RecyclerView.Adapter<VideoAdapter.ViewHolder>() {

    private val allItems = mutableListOf<DownloadItem>()
    private val items = mutableListOf<DownloadItem>()
    private var currentQuery: String = ""

    fun updateList(newItems: List<DownloadItem>) {
        allItems.clear()
        // Filtrar exclusivamente videos válidos (con archivo local existente)
        allItems.addAll(newItems.filter { !it.format.isAudio && !it.localFilePath.isNullOrBlank() })
        filter(currentQuery)
    }

    fun filter(query: String) {
        currentQuery = query
        items.clear()
        if (query.isBlank()) {
            items.addAll(allItems)
        } else {
            val q = query.lowercase().trim()
            items.addAll(allItems.filter {
                it.title.lowercase().contains(q) ||
                it.platform.displayName.lowercase().contains(q)
            })
        }
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

            val file = item.localFilePath?.let { File(it) }
            val sizeMb = if (item.fileSizeFormatted.isNotBlank()) {
                item.fileSizeFormatted
            } else if (file != null && file.exists()) {
                String.format("%.1f MB", file.length() / (1024f * 1024f))
            } else {
                "Descargado"
            }
            binding.tvVideoSubtitle.text = "${item.platform.displayName} • MP4 • $sizeMb"

            // Duración formateada desde el archivo local o etiqueta de calidad
            var durationText = if (item.format == MediaFormat.MP4_1080) "1080p" else "720p"
            if (file != null && file.exists()) {
                try {
                    val retriever = android.media.MediaMetadataRetriever()
                    retriever.setDataSource(file.absolutePath)
                    val timeStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val timeMs = timeStr?.toLongOrNull() ?: 0L
                    if (timeMs > 0) {
                        val totalSec = timeMs / 1000
                        durationText = String.format("%02d:%02d", totalSec / 60, totalSec % 60)
                    }
                    retriever.release()
                } catch (_: Exception) {}
            }
            binding.tvVideoDuration.text = durationText

            // Carga de Miniatura con Coil
            val thumb = item.thumbnailUrl
            if (!thumb.isNullOrBlank()) {
                val f = File(thumb)
                if (f.exists()) {
                    binding.imgVideoThumbnail.load(f) {
                        crossfade(true)
                        error(R.mipmap.ic_launcher)
                    }
                } else {
                    binding.imgVideoThumbnail.load(thumb) {
                        crossfade(true)
                        error(R.mipmap.ic_launcher)
                    }
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

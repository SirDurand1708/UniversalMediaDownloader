package com.omnivault.media.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.omnivault.media.R
import com.omnivault.media.databinding.ItemDownloadHistoryBinding
import com.omnivault.media.model.DownloadItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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
    private val httpClient = OkHttpClient()

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
        holder.itemView.alpha = 0f
        holder.itemView.translationY = 15f
        holder.itemView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(160)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemDownloadHistoryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: DownloadItem) {
            binding.tvHistoryTitle.text = item.title
            binding.tvHistoryPlatform.text = item.platform.displayName
            binding.tvHistoryDate.text = dateFormat.format(Date(item.timestamp))
            binding.tvHistorySize.text = item.fileSizeFormatted

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

            fun applyCover(bitmap: android.graphics.Bitmap) {
                binding.imgHistoryCover.alpha = 0f
                binding.imgHistoryCover.setImageBitmap(bitmap)
                binding.imgHistoryCover.animate().alpha(1f).setDuration(180).start()
            }

            // Cargar carátula del contenido
            val thumb = item.thumbnailUrl
            if (!thumb.isNullOrBlank()) {
                val localFile = File(thumb)
                if (localFile.exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(localFile.absolutePath)
                        if (bitmap != null) {
                            applyCover(bitmap)
                            return
                        }
                    } catch (_: Exception) {}
                } else if (thumb.startsWith("http")) {
                    CoroutineScope(Dispatchers.IO).launch {
                        try {
                            val req = Request.Builder().url(thumb).build()
                            httpClient.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val bitmap = BitmapFactory.decodeStream(resp.body?.byteStream())
                                    withContext(Dispatchers.Main) {
                                        if (bitmap != null) {
                                            applyCover(bitmap)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            } else {
                binding.imgHistoryCover.setImageResource(R.mipmap.ic_launcher)
            }
        }
    }
}

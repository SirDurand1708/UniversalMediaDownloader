package com.omnivault.media.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.omnivault.media.data.PlaylistEntity
import com.omnivault.media.databinding.ItemPlaylistCardBinding

data class PlaylistUiModel(
    val entity: PlaylistEntity,
    val count: Int
)

class PlaylistAdapter(
    private val onPlaylistClick: (PlaylistEntity) -> Unit,
    private val onPlayClick: (PlaylistEntity) -> Unit,
    private val onDeleteClick: (PlaylistEntity) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    private val items = mutableListOf<PlaylistUiModel>()

    fun updateList(newItems: List<PlaylistUiModel>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaylistCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemPlaylistCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(model: PlaylistUiModel) {
            val p = model.entity
            binding.tvPlaylistName.text = p.name
            binding.tvPlaylistTrackCount.text = "${model.count} canciones"

            binding.root.setOnClickListener { onPlaylistClick(p) }
            binding.btnPlayPlaylist.setOnClickListener { onPlayClick(p) }
            binding.btnDeletePlaylist.setOnClickListener { onDeleteClick(p) }
        }
    }
}

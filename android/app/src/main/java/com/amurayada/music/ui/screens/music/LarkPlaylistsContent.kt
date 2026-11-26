package com.amurayada.music.ui.screens.music

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.amurayada.music.data.model.Playlist
import com.amurayada.music.data.model.Song
import com.amurayada.music.ui.theme.LocalAuraPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun PlaylistsContent(
    playlists: List<Playlist>,
    smartPlaylists: List<Playlist> = emptyList(),
    allSongs: List<Song>,
    searchQuery: String = "",
    currentSongId: Long? = null,
    isPlaying: Boolean = false,
    onCreatePlaylist: () -> Unit,
    onPlaylistClick: (Playlist) -> Unit,
    onPlaylistPlay: (Playlist) -> Unit = {},
    onPlayPlaylist: (Playlist) -> Unit = onPlaylistPlay,
    onDuplicatePlaylist: (Playlist) -> Unit = {},
    onRenamePlaylist: (Playlist) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onExportPlaylistsJson: () -> String = { "[]" },
    onImportPlaylistsJson: (String, (Int) -> Unit) -> Unit = { _, cb -> cb(0) }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val validSongIds = remember(allSongs) { allSongs.map { it.id }.toSet() }
    val songMap = remember(allSongs) { allSongs.associateBy { it.id } }
    var sortMode by remember { mutableIntStateOf(0) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val json = onExportPlaylistsJson()
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Playlists exportadas correctamente", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error al exportar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    withContext(Dispatchers.Main) {
                        onImportPlaylistsJson(text) { count ->
                            val msg = when {
                                count > 0 -> "$count playlists importadas"
                                count == 0 -> "No se encontraron playlists nuevas en el archivo"
                                else -> "Archivo JSON inválido"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "Error al importar: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    val visiblePlaylists = remember(playlists, searchQuery, sortMode, validSongIds) {
        val filtered = if (searchQuery.isBlank()) playlists else {
            val q = searchQuery.trim().lowercase()
            playlists.filter { it.name.lowercase().contains(q) }
        }
        when (sortMode) {
            1 -> filtered.sortedBy { it.name.lowercase() }
            2 -> filtered.sortedByDescending { pl -> pl.songIds.count { it in validSongIds } }
            else -> filtered.sortedByDescending { it.dateCreated }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Surface(
                onClick = onCreatePlaylist,
                color = MusicCardBg,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MusicAccent.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MusicAccent.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = MusicAccent)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("Crear nueva playlist", fontWeight = FontWeight.Bold, color = MusicTextPrimary)
                        Text("Organiza tus canciones locales", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("omnivault_playlists.json") },
                    enabled = playlists.isNotEmpty(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Exportar", fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Importar", fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { sortMode = (sortMode + 1) % 3 },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Rounded.Sort, contentDescription = null, modifier = Modifier.size(14.dp), tint = MusicAccent)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = when (sortMode) { 1 -> "A-Z"; 2 -> "Más canciones"; else -> "Recientes" },
                        fontSize = 11.sp,
                        color = MusicAccent
                    )
                }
            }
        }

        if (smartPlaylists.isNotEmpty() && searchQuery.isBlank()) {
            item {
                Text(
                    text = "PLAYLISTS INTELIGENTES",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MusicAccent,
                    letterSpacing = 1.sp
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(smartPlaylists, key = { it.id }) { smart ->
                        val iconVec = when (smart.id) {
                            -1L -> Icons.Rounded.NewReleases
                            -2L -> Icons.Rounded.TrendingUp
                            else -> Icons.Rounded.Favorite
                        }
                        val hasActiveSong = currentSongId != null && currentSongId in smart.songIds
                        Surface(
                            onClick = { onPlaylistClick(smart) },
                            color = if (hasActiveSong) MusicCardActive else MusicCardBg,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(
                                if (hasActiveSong) 1.5.dp else 1.dp,
                                if (hasActiveSong) MusicAccent else MusicAccent.copy(alpha = 0.28f)
                            ),
                            modifier = Modifier.width(156.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(iconVec, contentDescription = null, tint = MusicAccent, modifier = Modifier.size(22.dp))
                                    if (hasActiveSong) {
                                        Surface(
                                            color = MusicAccent.copy(alpha = 0.16f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Icon(
                                                    if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause,
                                                    contentDescription = null,
                                                    tint = MusicAccent,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = if (isPlaying) "Activa" else "Pausa",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MusicAccent
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(smart.name, fontWeight = FontWeight.Bold, color = if (hasActiveSong) MusicAccent else MusicTextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${smart.songIds.size} canciones", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        }

        if (visiblePlaylists.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.QueueMusic,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = Color.Gray.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = if (searchQuery.isBlank()) "No tienes playlists todavía" else "Sin resultados para \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
            }
        }

        items(visiblePlaylists, key = { it.id }) { playlist ->
            var itemMenuExpanded by remember { mutableStateOf(false) }
            val validCount = remember(playlist.songIds, validSongIds) {
                if (playlist.songIds.isNotEmpty()) playlist.songIds.count { it in validSongIds } else playlist.songCount
            }
            val firstArt = remember(playlist.artworkUri, playlist.songIds, songMap) {
                playlist.artworkUri?.let { Uri.parse(it) }
                    ?: playlist.songIds.firstNotNullOfOrNull { songMap[it]?.albumArtUri }
            }
            val hasActiveSong = currentSongId != null && currentSongId in playlist.songIds

            Surface(
                onClick = { onPlaylistClick(playlist) },
                color = if (hasActiveSong) MusicCardActive.copy(alpha = 0.9f) else MusicCardBg.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(
                    if (hasActiveSong) 1.5.dp else 1.dp,
                    if (hasActiveSong) MusicAccent else MusicBorder
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (firstArt != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(firstArt)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (LocalAuraPalette.current.isDark) Color(0xFF1E2838) else Color(0xFFE2E8F0)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.QueueMusic, contentDescription = null, tint = MusicAccent)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            playlist.name,
                            fontWeight = FontWeight.SemiBold,
                            color = if (hasActiveSong) MusicAccent else MusicTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("$validCount canciones", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            if (hasActiveSong) {
                                Surface(
                                    color = MusicAccent.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Icon(
                                            if (isPlaying) Icons.Rounded.GraphicEq else Icons.Rounded.Pause,
                                            contentDescription = null,
                                            tint = MusicAccent,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Text(
                                            text = if (isPlaying) "Reproduciendo" else "En pausa",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MusicAccent
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Box {
                        IconButton(onClick = { itemMenuExpanded = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Rounded.MoreVert,
                                contentDescription = "Opciones",
                                tint = MusicTextPrimary.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = itemMenuExpanded,
                            onDismissRequest = { itemMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Reproducir") },
                                leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                                onClick = {
                                    itemMenuExpanded = false
                                    onPlayPlaylist(playlist)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Duplicar") },
                                leadingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = null) },
                                onClick = {
                                    itemMenuExpanded = false
                                    onDuplicatePlaylist(playlist)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Renombrar") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    itemMenuExpanded = false
                                    onRenamePlaylist(playlist)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Eliminar playlist", color = Color(0xFFFF5252)) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
                                onClick = {
                                    itemMenuExpanded = false
                                    onDeletePlaylist(playlist)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

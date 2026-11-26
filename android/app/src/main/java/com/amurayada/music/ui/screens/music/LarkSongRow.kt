package com.amurayada.music.ui.screens.music

import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.amurayada.music.data.model.Album
import com.amurayada.music.data.model.Artist
import com.amurayada.music.data.model.PlaybackState
import com.amurayada.music.data.model.Playlist
import com.amurayada.music.data.model.Song
import com.amurayada.music.data.model.SongSort
import com.amurayada.music.ui.components.AddToPlaylistSheet
import com.amurayada.music.ui.components.EqualizerIndicator
import com.amurayada.music.ui.viewmodel.LibraryViewModel
import com.amurayada.music.ui.viewmodel.PlaybackViewModel
import com.amurayada.music.ui.viewmodel.PlaylistViewModel
import com.omnivault.media.utils.FilenameStandardizer
import java.io.File

internal fun resolveAudioFormatLabel(song: Song): String {
    val mime = song.mimeType?.lowercase() ?: ""
    return when {
        mime.contains("flac") -> "FLAC"
        mime.contains("mpeg") || mime.contains("mp3") -> "MP3"
        mime.contains("mp4") || mime.contains("m4a") || mime.contains("aac") -> "M4A"
        mime.contains("ogg") || mime.contains("vorbis") -> "OGG"
        mime.contains("opus") -> "OPUS"
        mime.contains("wav") -> "WAV"
        else -> {
            val ext = song.path.substringAfterLast('.', "").uppercase()
            if (ext in setOf("MP3", "M4A", "FLAC", "WAV", "OGG", "OPUS", "AAC")) ext else "AUDIO"
        }
    }
}

/**
 * Fila individual con el diseño visual exacto de Lark Player.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LarkSongRow(
    song: Song,
    isCurrent: Boolean,
    isPlaying: Boolean,
    inSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    lrcKeys: Set<String> = emptySet(),
    showAlphabetPadding: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit = {},
    onAddToPlaylist: () -> Unit,
    onEditTags: () -> Unit = {},
    onShowDetails: () -> Unit = {},
    onStandardize: () -> Unit,
    onShare: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    // Lookup O(1) sin I/O en el hilo principal, compatible con rutas físicas y content://
    val hasLyrics = remember(song.path, song.title, lrcKeys) {
        if (lrcKeys.isEmpty()) false
        else {
            val byPath = song.path.startsWith("/") && lrcKeys.contains(song.path.substringBeforeLast("."))
            val byTitle = lrcKeys.contains(song.title.trim().lowercase()) ||
                lrcKeys.contains(com.omnivault.media.utils.FilenameStandardizer.standardizeTitle(song.title).lowercase())
            byPath || byTitle
        }
    }
    val formatLabel = remember(song.mimeType, song.path) { resolveAudioFormatLabel(song) }
    val durationLabel = remember(song.duration) {
        if (song.duration > 0L) com.amurayada.music.ui.screens.nowplaying.formatDuration(song.duration) else ""
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(
                when {
                    isSelected -> MusicAccent.copy(alpha = 0.20f)
                    isCurrent -> MusicAccent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                }
            )
            .padding(
                start = 16.dp,
                end = if (showAlphabetPadding) 26.dp else 12.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (inSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                colors = CheckboxDefaults.colors(checkedColor = MusicAccent)
            )
            Spacer(Modifier.width(4.dp))
        }
        // Carátula cuadrada redondeada de 48dp
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MusicCardBg),
            contentAlignment = Alignment.Center
        ) {
            val artModel = com.amurayada.music.ui.utils.toArtModel(song)
            if (artModel != null) {
                val artRequest = remember(song.id, song.path, song.albumArtUri) {
                    ImageRequest.Builder(context)
                        .data(artModel)
                        .size(144)
                        .crossfade(true)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build()
                }
                AsyncImage(
                    model = artRequest,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MusicTextPrimary.copy(alpha = 0.6f),
                    modifier = Modifier.size(24.dp)
                )
            }

            if (isCurrent && isPlaying) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    EqualizerIndicator(
                        isAnimating = true,
                        color = MusicAccent,
                        maxHeight = 22.dp,
                        barCount = 3,
                        barWidth = 3.dp
                    )
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        // Info de la Canción (Título y Subtítulo con insignia LETRAS)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (isCurrent) MusicAccent else MusicTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Insignia LETRAS exactamente como en Lark Player
                if (hasLyrics) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .border(0.8.dp, MusicTextPrimary.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "LETRAS",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MusicTextPrimary.copy(alpha = 0.85f),
                            fontSize = 8.sp,
                            letterSpacing = 0.5.sp
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }

                val rawArtist = FilenameStandardizer.cleanArtistName(song.artist)
                val cleanArtist = if (FilenameStandardizer.isValidArtist(rawArtist)) rawArtist else "Artista desconocido"
                val rawAlbum = song.album.replace(" - Topic", "", ignoreCase = true).trim()
                val cleanAlbum = if (rawAlbum.equals("<unknown>", ignoreCase = true) || rawAlbum.equals("unknown", ignoreCase = true)) "" else rawAlbum
                val subtitleText = if (cleanAlbum.isBlank() || cleanAlbum.equals(cleanArtist, ignoreCase = true)) {
                    cleanArtist
                } else {
                    "$cleanArtist • $cleanAlbum"
                }

                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MusicTextPrimary.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(horizontal = 6.dp)
        ) {
            if (durationLabel.isNotEmpty()) {
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MusicTextPrimary.copy(alpha = 0.65f),
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.height(2.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MusicAccent.copy(alpha = 0.14f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = formatLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MusicAccent,
                    fontSize = 8.sp
                )
            }
        }

        // Menú de 3 puntos
        Box {
            IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(36.dp)) {
                Icon(
                    imageVector = Icons.Rounded.MoreVert,
                    contentDescription = "Opciones",
                    tint = MusicTextPrimary.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }

            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text("Reproducir") },
                    leadingIcon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onClick()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Reproducir a continuación") },
                    leadingIcon = { Icon(Icons.Rounded.QueueMusic, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onPlayNext()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Añadir a la cola") },
                    leadingIcon = { Icon(Icons.Rounded.AddToQueue, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onAddToQueue()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Añadir a playlist") },
                    leadingIcon = { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onAddToPlaylist()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Editar etiquetas") },
                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onEditTags()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Detalles del archivo") },
                    leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onShowDetails()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Estandarizar nombre") },
                    leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onStandardize()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Compartir") },
                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onShare()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Ocultar") },
                    leadingIcon = { Icon(Icons.Rounded.VisibilityOff, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onHide()
                    }
                )
                DropdownMenuItem(
                    text = { Text("Eliminar", color = Color(0xFFFF5252)) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = Color(0xFFFF5252)) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            }
        }
    }
}


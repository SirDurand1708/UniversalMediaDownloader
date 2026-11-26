package com.amurayada.music.ui.screens.music

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.amurayada.music.data.model.Album
import com.amurayada.music.data.model.Artist
import com.amurayada.music.data.model.Song

import com.amurayada.music.ui.theme.LocalAuraPalette

private val MusicNeonCyan @Composable get() = LocalAuraPalette.current.accent
private val MusicNeonGreen @Composable get() = LocalAuraPalette.current.accentGreen
private val MusicTitaniumCard @Composable get() = LocalAuraPalette.current.cardBg
private val MusicBorderSlate @Composable get() = LocalAuraPalette.current.border
private val MusicTextSecondary @Composable get() = LocalAuraPalette.current.textSecondary

private fun formatSongCount(count: Int): String =
    if (count == 1) "1 canción" else "$count canciones"

@Composable
private fun LibrarySectionSummary(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MusicTextSecondary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun LibraryEmptyState(icon: ImageVector, title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MusicNeonCyan.copy(alpha = 0.12f))
                    .border(1.dp, MusicNeonCyan.copy(alpha = 0.3f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MusicNeonCyan, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MusicTextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MusicTextSecondary
            )
        }
    }
}

/**
 * Pestaña de Carpetas locales con filtrado en vivo y paleta unificada de Música.
 */
@Composable
internal fun FoldersContent(
    folderGroups: Map<String, List<Song>>,
    searchQuery: String = "",
    onFolderClick: (String, List<Song>) -> Unit
) {
    val visibleFolders = remember(folderGroups, searchQuery) {
        if (searchQuery.isBlank()) {
            folderGroups.entries.toList()
        } else {
            val q = searchQuery.trim().lowercase()
            folderGroups.entries.filter { it.key.lowercase().contains(q) }
        }
    }
    if (visibleFolders.isEmpty()) {
        LibraryEmptyState(
            icon = Icons.Rounded.Folder,
            title = if (searchQuery.isNotBlank()) "Sin carpetas coincidentes" else "No hay carpetas de audio",
            subtitle = "Tus descargas se guardan localmente en Music/OmniVault"
        )
        return
    }
    val totalSongs = remember(visibleFolders) { visibleFolders.sumOf { it.value.size } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "folders_summary") {
            LibrarySectionSummary("${visibleFolders.size} carpetas locales • ${formatSongCount(totalSongs)}")
        }
        items(visibleFolders, key = { it.key }) { (folderName, songs) ->
            val isOmniVault = folderName.equals("OmniVault", ignoreCase = true)
            Surface(
                onClick = { onFolderClick(folderName, songs) },
                color = MusicTitaniumCard,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (isOmniVault) MusicNeonCyan.copy(alpha = 0.45f) else MusicBorderSlate
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                if (isOmniVault) {
                                    Brush.linearGradient(
                                        listOf(MusicNeonGreen.copy(alpha = 0.22f), MusicNeonCyan.copy(alpha = 0.22f))
                                    )
                                } else {
                                    Brush.linearGradient(
                                        listOf(MusicNeonCyan.copy(alpha = 0.14f), MusicNeonCyan.copy(alpha = 0.08f))
                                    )
                                }
                            )
                            .border(1.dp, MusicNeonCyan.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Folder,
                            contentDescription = null,
                            tint = if (isOmniVault) MusicNeonGreen else MusicNeonCyan
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = folderName,
                                fontWeight = FontWeight.SemiBold,
                                color = MusicTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            if (isOmniVault) {
                                Spacer(Modifier.width(8.dp))
                                Surface(
                                    color = MusicNeonGreen.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = "Local App",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MusicNeonGreen,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = formatSongCount(songs.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MusicTextSecondary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(MusicNeonCyan.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = "Reproducir",
                            tint = MusicNeonCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Pestaña de Álbumes locales (solo álbumes reales con 2+ canciones) con paleta unificada de Música.
 */
@Composable
internal fun AlbumsContent(
    albums: List<Album>,
    searchQuery: String = "",
    onAlbumClick: (Album) -> Unit
) {
    val visibleAlbums = remember(albums, searchQuery) {
        if (searchQuery.isBlank()) {
            albums
        } else {
            val q = searchQuery.trim().lowercase()
            albums.filter {
                it.name.lowercase().contains(q) || it.artist.lowercase().contains(q)
            }
        }
    }
    if (visibleAlbums.isEmpty()) {
        LibraryEmptyState(
            icon = Icons.Rounded.Album,
            title = if (searchQuery.isNotBlank()) "Sin álbumes coincidentes" else "Sin álbumes agrupados",
            subtitle = "Los sencillos independientes se mantienen en Canciones para evitar ruido"
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "albums_summary") {
            LibrarySectionSummary("${visibleAlbums.size} álbumes")
        }
        items(visibleAlbums, key = { it.id }) { album ->
            Surface(
                onClick = { onAlbumClick(album) },
                color = MusicTitaniumCard,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MusicBorderSlate),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MusicNeonCyan.copy(alpha = 0.12f))
                            .border(1.dp, MusicBorderSlate, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (album.artworkUri != null) {
                            AsyncImage(
                                model = album.artworkUri,
                                contentDescription = album.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(Icons.Rounded.Album, contentDescription = null, tint = MusicNeonCyan)
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = album.name,
                            fontWeight = FontWeight.SemiBold,
                            color = MusicTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = album.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MusicTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        color = MusicNeonCyan.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = formatSongCount(album.songCount),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MusicNeonCyan,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MusicTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Pestaña de Artistas locales (con 2+ canciones) con paleta unificada de Música.
 */
@Composable
internal fun ArtistsContent(
    artists: List<Artist>,
    searchQuery: String = "",
    onArtistClick: (Artist) -> Unit
) {
    val visibleArtists = remember(artists, searchQuery) {
        if (searchQuery.isBlank()) {
            artists
        } else {
            val q = searchQuery.trim().lowercase()
            artists.filter { it.name.lowercase().contains(q) }
        }
    }
    if (visibleArtists.isEmpty()) {
        LibraryEmptyState(
            icon = Icons.Rounded.Person,
            title = if (searchQuery.isNotBlank()) "Sin artistas coincidentes" else "Sin artistas destacados",
            subtitle = "Los temas independientes de 1 sola pista se mantienen en Canciones"
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "artists_summary") {
            LibrarySectionSummary("${visibleArtists.size} artistas")
        }
        items(visibleArtists, key = { it.id }) { artist ->
            Surface(
                onClick = { onArtistClick(artist) },
                color = MusicTitaniumCard,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MusicBorderSlate),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(MusicNeonGreen.copy(alpha = 0.2f), MusicNeonCyan.copy(alpha = 0.2f))
                                )
                            )
                            .border(1.dp, MusicNeonCyan.copy(alpha = 0.35f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.Person, contentDescription = null, tint = MusicNeonCyan)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artist.name,
                            fontWeight = FontWeight.SemiBold,
                            color = MusicTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        val subtitle = if (artist.albumCount > 0) {
                            "${formatSongCount(artist.songCount)} • ${artist.albumCount} ${if (artist.albumCount == 1) "álbum" else "álbumes"}"
                        } else {
                            formatSongCount(artist.songCount)
                        }
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MusicTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MusicTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

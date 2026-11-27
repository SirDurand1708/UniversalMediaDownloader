package com.amurayada.music.ui.screens.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amurayada.music.data.model.Song
import com.amurayada.music.data.model.SongSort
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SongsListContent(
    songs: List<Song>,
    currentSong: Song?,
    isPlaying: Boolean,
    currentSort: SongSort = SongSort.RECENT,
    lrcKeys: Set<String> = emptySet(),
    hiddenSongsCount: Int = 0,
    isInitialLoading: Boolean = false,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onShowHiddenSongs: () -> Unit = {},
    onSongClick: (Song) -> Unit,
    onPlayAll: () -> Unit = {},
    onShuffle: () -> Unit,
    onPlayNext: (Song) -> Unit,
    onAddToQueue: (Song) -> Unit = {},
    onAddToPlaylist: (Song) -> Unit,
    onAddMultipleToQueue: (List<Song>) -> Unit = {},
    onAddMultipleToPlaylist: (List<Song>) -> Unit = {},
    onHideMultiple: (List<Song>) -> Unit = {},
    onDeleteMultiple: (List<Song>) -> Unit = {},
    onEditTags: (Song) -> Unit = {},
    onShowDetails: (Song) -> Unit = {},
    onStandardize: (Song) -> Unit,
    onShare: (Song) -> Unit,
    onHide: (Song) -> Unit,
    onDelete: (Song) -> Unit
) {
    if (songs.isEmpty() && isInitialLoading) {
        val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.28f,
            targetValue = 0.65f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = androidx.compose.animation.core.tween(750),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse
            ),
            label = "skeleton_alpha"
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            repeat(10) {
                Surface(
                    color = MusicCardBg,
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, MusicBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MusicBorder.copy(alpha = pulseAlpha))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.68f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MusicBorder.copy(alpha = pulseAlpha))
                            )
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.42f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(5.dp))
                                    .background(MusicBorder.copy(alpha = pulseAlpha * 0.8f))
                            )
                        }
                    }
                }
            }
        }
        return
    } else if (songs.isEmpty()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.Gray.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "No se encontraron canciones locales",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.Gray
                    )
                    if (hiddenSongsCount > 0) {
                        Spacer(Modifier.height(12.dp))
                        TextButton(onClick = onShowHiddenSongs) {
                            Text("Ver canciones ocultas ($hiddenSongsCount)", color = MusicAccent)
                        }
                    }
                }
            }
        }
        return
    }

    val selectedIds = remember { mutableStateListOf<Long>() }
    LaunchedEffect(songs) {
        if (selectedIds.isNotEmpty()) {
            val validIds = songs.mapTo(HashSet()) { it.id }
            selectedIds.removeAll { it !in validIds }
        }
    }
    val inSelectionMode = selectedIds.isNotEmpty()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 12 } }
    val showAlphabetIndex = (currentSort == SongSort.NAME_ASC || currentSort == SongSort.NAME_DESC) && songs.size > 30
    val currentSongIndex = remember(songs, currentSong?.id) {
        if (currentSong == null) -1 else songs.indexOfFirst { it.id == currentSong.id }
    }

    BackHandler(enabled = inSelectionMode) {
        selectedIds.clear()
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                if (inSelectionMode) {
                    item {
                        Surface(
                            color = MusicCardBg,
                            shape = RoundedCornerShape(14.dp),
                            border = BorderStroke(1.dp, MusicAccent.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { selectedIds.clear() },
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Cancelar selección", tint = MusicTextPrimary, modifier = Modifier.size(18.dp))
                                }
                                Text(
                                    text = "${selectedIds.size}",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MusicAccent
                                )
                                Spacer(Modifier.weight(1f))
                                IconButton(
                                    onClick = {
                                        val batch = songs.filter { selectedIds.contains(it.id) }
                                        onAddMultipleToPlaylist(batch)
                                        selectedIds.clear()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.PlaylistAdd, contentDescription = "Añadir a playlist", tint = MusicTextPrimary, modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = {
                                        val batch = songs.filter { selectedIds.contains(it.id) }
                                        onAddMultipleToQueue(batch)
                                        selectedIds.clear()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.QueueMusic, contentDescription = "Añadir a la cola", tint = MusicTextPrimary, modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = {
                                        val batch = songs.filter { selectedIds.contains(it.id) }
                                        onHideMultiple(batch)
                                        selectedIds.clear()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.VisibilityOff, contentDescription = "Ocultar", tint = MusicTextPrimary, modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = {
                                        val batch = songs.filter { selectedIds.contains(it.id) }
                                        onDeleteMultiple(batch)
                                        selectedIds.clear()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "Eliminar", tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = {
                                        if (selectedIds.size == songs.size) {
                                            selectedIds.clear()
                                        } else {
                                            selectedIds.clear()
                                            selectedIds.addAll(songs.map { it.id })
                                        }
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(Icons.Rounded.SelectAll, contentDescription = "Seleccionar todo", tint = MusicAccent, modifier = Modifier.size(20.dp))
                                }
                            }
                        }
                    }
                } else {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${songs.size} canciones",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MusicTextPrimary.copy(alpha = 0.7f),
                                    maxLines = 1
                                )
                                if (hiddenSongsCount > 0) {
                                    Text(
                                        text = "Ocultas ($hiddenSongsCount)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MusicAccent,
                                        modifier = Modifier.clickable { onShowHiddenSongs() }
                                    )
                                }
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (currentSongIndex >= 0) {
                                    Surface(
                                        onClick = { scope.launch { listState.animateScrollToItem(currentSongIndex + 1) } },
                                        color = MusicCardBg,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, MusicBorder)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.MyLocation,
                                            contentDescription = "Ir a canción actual",
                                            tint = MusicAccent,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp).size(16.dp)
                                        )
                                    }
                                }

                                Surface(
                                    onClick = onPlayAll,
                                    color = MusicCardBg,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MusicBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.PlayArrow,
                                            contentDescription = null,
                                            tint = MusicAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "Todo",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MusicTextPrimary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }

                                Surface(
                                    onClick = onShuffle,
                                    color = MusicCardBg,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, MusicBorder)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Shuffle,
                                            contentDescription = null,
                                            tint = MusicAccent,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = "Aleatorio",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MusicTextPrimary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                items(songs, key = { it.id }) { song ->
                    val isCurrent = currentSong?.id == song.id
                    val isSelected = selectedIds.contains(song.id)
                    LarkSongRow(
                        song = song,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        inSelectionMode = inSelectionMode,
                        isSelected = isSelected,
                        lrcKeys = lrcKeys,
                        showAlphabetPadding = showAlphabetIndex,
                        onClick = {
                            if (inSelectionMode) {
                                if (isSelected) selectedIds.remove(song.id) else selectedIds.add(song.id)
                            } else {
                                onSongClick(song)
                            }
                        },
                        onLongClick = {
                            if (isSelected) selectedIds.remove(song.id) else selectedIds.add(song.id)
                        },
                        onPlayNext = { onPlayNext(song) },
                        onAddToQueue = { onAddToQueue(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onEditTags = { onEditTags(song) },
                        onShowDetails = { onShowDetails(song) },
                        onStandardize = { onStandardize(song) },
                        onShare = { onShare(song) },
                        onHide = { onHide(song) },
                        onDelete = { onDelete(song) }
                    )
                }
            }

            if (showAlphabetIndex) {
                val alphabet = remember { ('A'..'Z').map { it.toString() } }
                var activeLetter by remember { mutableStateOf<String?>(null) }
                var barHeightPx by remember { mutableIntStateOf(1) }

                val jumpToLetter: (Float) -> Unit = { y ->
                    val idx = ((y / barHeightPx.toFloat()) * alphabet.size).toInt().coerceIn(0, alphabet.lastIndex)
                    val letter = alphabet[idx]
                    activeLetter = letter
                    val songIdx = songs.indexOfFirst { it.title.trimStart().startsWith(letter, ignoreCase = true) }
                    if (songIdx >= 0) {
                        scope.launch { listState.scrollToItem(songIdx + 1) }
                    }
                }

                if (activeLetter != null) {
                    Surface(
                        color = MusicAccent,
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = activeLetter ?: "",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MusicOnAccent
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 2.dp, top = 48.dp, bottom = 56.dp)
                        .onSizeChanged { barHeightPx = it.height.coerceAtLeast(1) }
                        .pointerInput(songs) {
                            detectVerticalDragGestures(
                                onDragStart = { offset -> jumpToLetter(offset.y) },
                                onVerticalDrag = { change, _ -> jumpToLetter(change.position.y) },
                                onDragEnd = { activeLetter = null },
                                onDragCancel = { activeLetter = null }
                            )
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    alphabet.forEach { letter ->
                        Text(
                            text = letter,
                            fontSize = 9.sp,
                            fontWeight = if (activeLetter == letter) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (activeLetter == letter) MusicAccent else MusicTextPrimary.copy(alpha = 0.5f),
                            modifier = Modifier
                                .clickable {
                                    val songIdx = songs.indexOfFirst { it.title.trimStart().startsWith(letter, ignoreCase = true) }
                                    if (songIdx >= 0) scope.launch { listState.scrollToItem(songIdx + 1) }
                                }
                                .padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = if (showAlphabetIndex) 36.dp else 16.dp, bottom = 16.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    containerColor = MusicCardBg,
                    contentColor = MusicAccent
                ) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Ir arriba")
                }
            }
        }
    }
}

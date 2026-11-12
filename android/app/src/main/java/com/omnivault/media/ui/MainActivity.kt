package com.omnivault.media.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.omnivault.media.R
import com.omnivault.media.data.AppDatabase
import com.omnivault.media.data.PlaylistEntity
import com.omnivault.media.data.PlaylistItemEntity
import com.omnivault.media.databinding.ActivityMainBinding
import com.omnivault.media.downloader.DownloadManager
import com.omnivault.media.downloader.MediaExtractor
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.PlatformType
import com.omnivault.media.model.SortOption
import com.omnivault.media.player.AudioPlayerManager
import com.omnivault.media.player.NowPlayingTrack
import com.omnivault.media.service.ClipboardMonitorService
import com.omnivault.media.service.MediaDownloadService
import com.omnivault.media.utils.CoverArtHelper
import com.omnivault.media.utils.FilenameStandardizer
import com.omnivault.media.utils.LyricsHelper
import com.omnivault.media.utils.NotificationHelper
import com.omnivault.media.utils.StorageHelper
import com.omnivault.media.utils.UpdateInfo
import com.omnivault.media.utils.UpdateManager
import com.omnivault.media.utils.UrlUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class MainActivity : AppCompatActivity() {

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(this, "🔔 Notificaciones activadas: verás la barra de carga en tiempo real", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestStoragePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.any { it.value }
        if (granted) {
            Toast.makeText(this, "📱 Permisos concedidos: sincronizando música y videos del celular...", Toast.LENGTH_SHORT).show()
            syncDeviceMedia(silent = false)
        }
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var downloadAdapter: DownloadAdapter
    private lateinit var recentAdapter: HistoryAdapter
    private lateinit var musicAdapter: HistoryAdapter
    private lateinit var videoAdapter: VideoAdapter
    private lateinit var playlistAdapter: PlaylistAdapter

    private var selectedFormat: MediaFormat = MediaFormat.M4A
    private var pendingUpdateInfo: UpdateInfo? = null
    private var isViewingPlaylistsSubTab = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Respetar recortes de cámara y barra de estado (Edge-to-Edge)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val topInset = maxOf(statusBar.top, cutout.top)
            binding.mainScrollView.setPadding(0, topInset, 0, 0)
            insets
        }

        // Subtítulo dinámico con versión oficial
        binding.tvAppSubtitle.text = "OmniVault • Music & Video v${UpdateManager.CURRENT_VERSION_NAME}"

        downloadManager = DownloadManager.getInstance(this)
        NotificationHelper.createNotificationChannels(this)
        checkNotificationPermission()
        checkStoragePermissions()

        setupRecyclerViews()
        setupPlaylists()
        setupListeners()
        setupBottomNavigation()
        selectFormat(MediaFormat.M4A) // Formato predeterminado HQ

        handlePreloadedUrl(intent)
        checkClipboardForMediaUrl()

        // Comprobación de actualizaciones en segundo plano
        checkForUpdates(silent = true)
        handleUpdateIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshLists()
        refreshPlaylists()
        checkClipboardForMediaUrl()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePreloadedUrl(intent)
        handleUpdateIntent(intent)
        syncDeviceMedia(silent = true)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun checkStoragePermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            requestStoragePermissionLauncher.launch(needed.toTypedArray())
        } else {
            syncDeviceMedia(silent = true)
        }
    }

    private fun syncDeviceMedia(silent: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val counts = downloadManager.syncWithDeviceMedia(this@MainActivity)
            withContext(Dispatchers.Main) {
                refreshLists()
                refreshPlaylists()
                if (!silent) {
                    Toast.makeText(this@MainActivity, "✓ Sincronizado: ${counts.first} canciones y ${counts.second} videos encontrados en el celular", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun handleUpdateIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("trigger_update", false) == true) {
            val apkUrl = intent.getStringExtra("update_url") ?: ""
            val version = intent.getStringExtra("update_version") ?: ""
            if (apkUrl.isNotBlank()) {
                startUpdateDownload(apkUrl, version)
            }
        }
    }

    private fun handlePreloadedUrl(intent: Intent?) {
        val preloaded = intent?.getStringExtra("preloaded_url")
        if (!preloaded.isNullOrBlank()) {
            binding.etUrlInput.setText(preloaded)
            updatePlatformBadge(preloaded)
            analyzeUrl(preloaded)
        }
    }

    private fun setupRecyclerViews() {
        // 1. Descargas activas
        downloadAdapter = DownloadAdapter { item ->
            downloadManager.cancelDownload(item.id)
            refreshLists()
        }
        binding.rvActiveDownloads.layoutManager = LinearLayoutManager(this)
        binding.rvActiveDownloads.adapter = downloadAdapter

        // 2. Historial de descargas recientes (Pestaña Descargas)
        recentAdapter = HistoryAdapter(
            onPlayClick = { item -> playMediaFile(item) },
            onShareClick = { item -> shareMediaFile(item) },
            onDeleteClick = { item ->
                downloadManager.removeHistoryItem(item)
                refreshLists()
            },
            onAddToPlaylistClick = { item -> showAddToPlaylistDialog(item) }
        )
        binding.rvDownloadHistory.layoutManager = LinearLayoutManager(this)
        binding.rvDownloadHistory.adapter = recentAdapter

        // 3. Biblioteca de Música (Pestaña Música)
        musicAdapter = HistoryAdapter(
            onPlayClick = { item -> playMediaFile(item) },
            onShareClick = { item -> shareMediaFile(item) },
            onDeleteClick = { item ->
                downloadManager.removeHistoryItem(item)
                refreshLists()
            },
            onAddToPlaylistClick = { item -> showAddToPlaylistDialog(item) }
        )
        binding.rvMusicLibrary.layoutManager = LinearLayoutManager(this)
        binding.rvMusicLibrary.adapter = musicAdapter

        // Recuperar preferencia de ordenamiento para música
        val savedMusicSort = getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE)
            .getString("sort_music", SortOption.DATE_DESC.name) ?: SortOption.DATE_DESC.name
        musicAdapter.setSortOption(SortOption.fromKey(savedMusicSort))

        // 4. Biblioteca de Videos (Pestaña Videos con tarjetas 16:9)
        videoAdapter = VideoAdapter(
            onVideoClick = { item -> playMediaFile(item) },
            onShareClick = { item -> shareMediaFile(item) }
        )
        binding.rvVideoLibrary.layoutManager = LinearLayoutManager(this)
        binding.rvVideoLibrary.adapter = videoAdapter

        // Recuperar preferencia de ordenamiento para videos
        val savedVideoSort = getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE)
            .getString("sort_video", SortOption.DATE_DESC.name) ?: SortOption.DATE_DESC.name
        videoAdapter.setSortOption(SortOption.fromKey(savedVideoSort))

        downloadManager.onDownloadUpdatedListener = {
            runOnUiThread { refreshLists() }
        }
        downloadManager.onHistoryUpdatedListener = {
            runOnUiThread { refreshLists() }
        }
        refreshLists()
    }

    private fun setupPlaylists() {
        playlistAdapter = PlaylistAdapter(
            onPlaylistClick = { playlist -> showPlaylistDetailsDialog(playlist) },
            onPlayClick = { playlist -> playPlaylistTracks(playlist) },
            onDeleteClick = { playlist -> deletePlaylist(playlist) }
        )
        binding.rvPlaylists.layoutManager = LinearLayoutManager(this)
        binding.rvPlaylists.adapter = playlistAdapter

        binding.btnSubTabSongs.setOnClickListener {
            it.bounce()
            switchMusicSubTab(isPlaylists = false)
        }

        binding.btnSubTabPlaylists.setOnClickListener {
            it.bounce()
            switchMusicSubTab(isPlaylists = true)
        }

        binding.btnCreatePlaylist.setOnClickListener {
            it.bounce()
            showCreatePlaylistDialog()
        }

        refreshPlaylists()
    }

    private fun switchMusicSubTab(isPlaylists: Boolean) {
        isViewingPlaylistsSubTab = isPlaylists
        val selectedBg = R.drawable.bg_chip_selected
        val unselectedBg = R.drawable.bg_chip_unselected
        val cyan = ContextCompat.getColor(this, R.color.vault_cyan)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)

        if (isPlaylists) {
            binding.btnSubTabSongs.setBackgroundResource(unselectedBg)
            binding.btnSubTabSongs.setTextColor(muted)
            binding.btnSubTabPlaylists.setBackgroundResource(selectedBg)
            binding.btnSubTabPlaylists.setTextColor(cyan)

            binding.layoutSongsSubTab.visibility = View.GONE
            binding.layoutPlaylistsSubTab.visibility = View.VISIBLE
            refreshPlaylists()
        } else {
            binding.btnSubTabSongs.setBackgroundResource(selectedBg)
            binding.btnSubTabSongs.setTextColor(cyan)
            binding.btnSubTabPlaylists.setBackgroundResource(unselectedBg)
            binding.btnSubTabPlaylists.setTextColor(muted)

            binding.layoutSongsSubTab.visibility = View.VISIBLE
            binding.layoutPlaylistsSubTab.visibility = View.GONE
        }
    }

    private fun refreshPlaylists() {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(this@MainActivity)
            val playlists = db.playlistDao().getAllPlaylists()
            val models = playlists.map { p ->
                val count = db.playlistDao().getItemCount(p.id)
                PlaylistUiModel(p, count)
            }
            withContext(Dispatchers.Main) {
                playlistAdapter.updateList(models)
                binding.tvEmptyPlaylists.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
                binding.rvPlaylists.visibility = if (models.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showCreatePlaylistDialog(onCreated: ((PlaylistEntity) -> Unit)? = null) {
        val input = EditText(this).apply {
            hint = "Ej. Mis Favoritas, Gym, Para el auto..."
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_muted))
            setPadding(40, 30, 40, 30)
        }
        AlertDialog.Builder(this)
            .setTitle("✨ Crear Nueva Playlist")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        val db = AppDatabase.getInstance(this@MainActivity)
                        val entity = PlaylistEntity(name = name)
                        db.playlistDao().insertPlaylist(entity)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "✓ Playlist '$name' creada", Toast.LENGTH_SHORT).show()
                            refreshPlaylists()
                            onCreated?.invoke(entity)
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showAddToPlaylistDialog(item: DownloadItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(this@MainActivity)
            val playlists = db.playlistDao().getAllPlaylists()
            withContext(Dispatchers.Main) {
                val dialog = BottomSheetDialog(this@MainActivity)
                val view = layoutInflater.inflate(R.layout.dialog_add_to_playlist, null)
                dialog.setContentView(view)

                view.findViewById<TextView>(R.id.tvSongToAddToPlaylist).text = item.title
                val rv = view.findViewById<RecyclerView>(R.id.rvPlaylistsSelection)
                val tvEmpty = view.findViewById<TextView>(R.id.tvNoPlaylistsHint)
                val btnCreate = view.findViewById<View>(R.id.btnCreateNewPlaylistFromDialog)

                if (playlists.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    rv.visibility = View.GONE
                } else {
                    tvEmpty.visibility = View.GONE
                    rv.visibility = View.VISIBLE
                    val selectionAdapter = PlaylistAdapter(
                        onPlaylistClick = { playlist ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.playlistDao().insertPlaylistItem(
                                    PlaylistItemEntity(
                                        playlistId = playlist.id,
                                        trackPath = item.localFilePath ?: "",
                                        title = item.title,
                                        artist = item.artist ?: "OmniVault",
                                        coverPath = item.thumbnailUrl
                                    )
                                )
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "✓ Añadida a '${playlist.name}'", Toast.LENGTH_SHORT).show()
                                    dialog.dismiss()
                                    refreshPlaylists()
                                }
                            }
                        },
                        onPlayClick = {},
                        onDeleteClick = {}
                    )
                    rv.layoutManager = LinearLayoutManager(this@MainActivity)
                    rv.adapter = selectionAdapter
                    lifecycleScope.launch(Dispatchers.IO) {
                        val models = playlists.map { p -> PlaylistUiModel(p, db.playlistDao().getItemCount(p.id)) }
                        withContext(Dispatchers.Main) { selectionAdapter.updateList(models) }
                    }
                }

                btnCreate.setOnClickListener {
                    dialog.dismiss()
                    showCreatePlaylistDialog { newPlaylist ->
                        lifecycleScope.launch(Dispatchers.IO) {
                            db.playlistDao().insertPlaylistItem(
                                PlaylistItemEntity(
                                    playlistId = newPlaylist.id,
                                    trackPath = item.localFilePath ?: "",
                                    title = item.title,
                                    artist = item.artist ?: "OmniVault",
                                    coverPath = item.thumbnailUrl
                                )
                            )
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "✓ Añadida a '${newPlaylist.name}'", Toast.LENGTH_SHORT).show()
                                refreshPlaylists()
                            }
                        }
                    }
                }
                dialog.show()
            }
        }
    }

    private fun playPlaylistTracks(playlist: PlaylistEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(this@MainActivity)
            val items = db.playlistDao().getItemsForPlaylist(playlist.id)
            withContext(Dispatchers.Main) {
                if (items.isNotEmpty()) {
                    val tracks = items.map {
                        NowPlayingTrack(
                            filePath = it.trackPath,
                            title = it.title,
                            artist = it.artist,
                            coverPath = it.coverPath
                        )
                    }
                    AudioPlayerManager.setPlaylist(tracks, startIndex = 0)
                    val firstItem = downloadManager.getHistory().firstOrNull { it.localFilePath == tracks.first().filePath }
                    if (firstItem != null) {
                        showFullPlayerBottomSheet(firstItem)
                    }
                    Toast.makeText(this@MainActivity, "▶ Reproduciendo playlist '${playlist.name}' (${items.size} canciones)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "La playlist '${playlist.name}' está vacía. Añade canciones con el botón '+ Playlist'", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun deletePlaylist(playlist: PlaylistEntity) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Playlist")
            .setMessage("¿Deseas eliminar '${playlist.name}'? Las canciones continuarán en tu celular.")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getInstance(this@MainActivity)
                    db.playlistDao().clearPlaylistItems(playlist.id)
                    db.playlistDao().deletePlaylist(playlist)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "✓ Playlist eliminada", Toast.LENGTH_SHORT).show()
                        refreshPlaylists()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPlaylistDetailsDialog(playlist: PlaylistEntity) {
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(this@MainActivity)
            val items = db.playlistDao().getItemsForPlaylist(playlist.id)
            withContext(Dispatchers.Main) {
                val dialog = BottomSheetDialog(this@MainActivity)
                val view = layoutInflater.inflate(R.layout.dialog_add_to_playlist, null)
                dialog.setContentView(view)

                view.findViewById<TextView>(R.id.tvSongToAddToPlaylist).text = "📑 ${playlist.name} (${items.size} canciones)"
                val rv = view.findViewById<RecyclerView>(R.id.rvPlaylistsSelection)
                val tvEmpty = view.findViewById<TextView>(R.id.tvNoPlaylistsHint)
                val btnAction = view.findViewById<View>(R.id.btnCreateNewPlaylistFromDialog)
                (btnAction as? android.widget.LinearLayout)?.getChildAt(0)?.let {
                    (it as? TextView)?.text = "▶ Reproducir Playlist Completa"
                }

                btnAction.setOnClickListener {
                    dialog.dismiss()
                    playPlaylistTracks(playlist)
                }

                if (items.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "Esta playlist está vacía.\nVe a la lista de canciones y toca '+ Playlist' para agregar."
                    rv.visibility = View.GONE
                } else {
                    tvEmpty.visibility = View.GONE
                    rv.visibility = View.VISIBLE
                    // Usar HistoryAdapter para mostrar canciones dentro de la playlist
                    val songItems = items.map {
                        DownloadItem(
                            id = it.trackPath,
                            url = it.trackPath,
                            title = it.title,
                            format = MediaFormat.M4A,
                            status = com.omnivault.media.model.DownloadStatus.COMPLETED,
                            localFilePath = it.trackPath,
                            thumbnailUrl = it.coverPath,
                            artist = it.artist
                        )
                    }
                    val playlistSongsAdapter = HistoryAdapter(
                        onPlayClick = { playMediaFile(it) },
                        onShareClick = { shareMediaFile(it) },
                        onDeleteClick = { song ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                db.playlistDao().removePlaylistItem(playlist.id, song.localFilePath ?: "")
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(this@MainActivity, "Canción removida de la playlist", Toast.LENGTH_SHORT).show()
                                    dialog.dismiss()
                                    refreshPlaylists()
                                }
                            }
                        }
                    )
                    rv.layoutManager = LinearLayoutManager(this@MainActivity)
                    rv.adapter = playlistSongsAdapter
                    playlistSongsAdapter.updateList(songItems)
                }
                dialog.show()
            }
        }
    }

    private fun showSortDialog(isAudio: Boolean) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_sort_options, null)
        dialog.setContentView(view)

        val setSort: (SortOption) -> Unit = { opt ->
            if (isAudio) {
                musicAdapter.setSortOption(opt)
                recentAdapter.setSortOption(opt)
            } else {
                videoAdapter.setSortOption(opt)
            }
            getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE)
                .edit().putString(if (isAudio) "sort_music" else "sort_video", opt.name).apply()
            Toast.makeText(this, "Ordenado por: ${opt.displayName}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        view.findViewById<View>(R.id.sortDateDesc).setOnClickListener { setSort(SortOption.DATE_DESC) }
        view.findViewById<View>(R.id.sortDateAsc).setOnClickListener { setSort(SortOption.DATE_ASC) }
        view.findViewById<View>(R.id.sortMostPlayed).setOnClickListener { setSort(SortOption.MOST_PLAYED) }
        view.findViewById<View>(R.id.sortNameAsc).setOnClickListener { setSort(SortOption.NAME_ASC) }
        view.findViewById<View>(R.id.sortNameDesc).setOnClickListener { setSort(SortOption.NAME_DESC) }
        view.findViewById<View>(R.id.sortSizeDesc).setOnClickListener { setSort(SortOption.SIZE_DESC) }
        view.findViewById<View>(R.id.sortSizeAsc).setOnClickListener { setSort(SortOption.SIZE_ASC) }

        dialog.show()
    }

    private fun setupListeners() {
        // Switch Auto Clipboard
        val prefs = getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE)
        binding.switchAutoClipboard.isChecked = prefs.getBoolean("auto_clipboard_detection", true)
        binding.switchAutoClipboard.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("auto_clipboard_detection", isChecked).apply()
            if (isChecked) {
                ClipboardMonitorService.startService(this)
                Toast.makeText(this, "Monitor de enlaces activado", Toast.LENGTH_SHORT).show()
            } else {
                ClipboardMonitorService.stopService(this)
                Toast.makeText(this, "Monitor de enlaces desactivado", Toast.LENGTH_SHORT).show()
            }
        }

        // Tocar subtítulo para comprobar actualizaciones
        binding.tvAppSubtitle.setOnClickListener {
            Toast.makeText(this, "Buscando actualizaciones de OmniVault...", Toast.LENGTH_SHORT).show()
            checkForUpdates(silent = false)
        }

        // Botón Actualizar Banner
        binding.btnUpdateNow.setOnClickListener {
            val update = pendingUpdateInfo ?: return@setOnClickListener
            startUpdateDownload(update.apkUrl, update.version)
        }

        // Botones Top-Bar (Ecualizador & Búsqueda)
        binding.btnTopBarEqualizer.setOnClickListener {
            it.bounce()
            val history = downloadManager.getHistory()
            val audioItem = history.firstOrNull { it.format.isAudio && !it.localFilePath.isNullOrBlank() }
            if (audioItem != null) {
                showFullPlayerBottomSheet(audioItem)
            } else {
                Toast.makeText(this, "🎛️ Reproduce una canción para ecualizar y subir hasta 300% de volumen", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnTopBarSearch.setOnClickListener {
            it.bounce()
            when {
                binding.layoutTabDownload.visibility == View.VISIBLE -> {
                    binding.etUrlInput.requestFocus()
                }
                binding.layoutTabMusic.visibility == View.VISIBLE -> {
                    binding.etMusicSearch.requestFocus()
                }
                binding.layoutTabVideos.visibility == View.VISIBLE -> {
                    binding.etVideoSearch.requestFocus()
                }
            }
        }

        // Botón "Ver Todas" canciones en pantalla principal -> Salta a la pestaña Música
        binding.btnViewAllSongs.setOnClickListener {
            it.bounce()
            switchTab(1)
        }

        // Paste Button
        binding.btnPasteUrl.setOnClickListener {
            it.bounce()
            val clipMgr = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipMgr.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                val extracted = UrlUtils.extractUrlFromText(text)
                if (!extracted.isNullOrBlank()) {
                    binding.etUrlInput.setText(extracted)
                    updatePlatformBadge(extracted)
                    analyzeUrl(extracted)
                } else {
                    binding.etUrlInput.setText(text)
                }
            }
        }

        // Analyze Button
        binding.btnAnalyzeUrl.setOnClickListener {
            it.bounce()
            val raw = binding.etUrlInput.text.toString().trim()
            val extracted = UrlUtils.extractUrlFromText(raw) ?: raw
            if (extracted.isNotBlank()) {
                analyzeUrl(extracted)
            } else {
                Toast.makeText(this, "Pega un enlace antes de analizar", Toast.LENGTH_SHORT).show()
            }
        }

        // Clear Button
        binding.btnClearUrl.setOnClickListener {
            it.bounce()
            binding.etUrlInput.setText("")
            hideCardPreview()
        }

        // TextWatcher con detección y análisis automático inteligente
        binding.etUrlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString() ?: ""
                binding.btnClearUrl.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
                updatePlatformBadge(text)
                val clean = UrlUtils.extractUrlFromText(text)
                if (!clean.isNullOrBlank() && UrlUtils.isSupportedMediaUrl(clean) && binding.cardPreview.visibility != View.VISIBLE) {
                    analyzeUrl(clean)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Selección de Formatos 2x2 (Diseño Idéntico al Mockup)
        binding.btnFormatM4A.setOnClickListener {
            it.bounce()
            selectFormat(MediaFormat.M4A)
        }
        binding.btnFormatMP3.setOnClickListener {
            it.bounce()
            selectFormat(MediaFormat.MP3_320)
        }
        binding.btnFormat1080p.setOnClickListener {
            it.bounce()
            selectFormat(MediaFormat.MP4_1080)
        }
        binding.btnFormat720p.setOnClickListener {
            it.bounce()
            selectFormat(MediaFormat.MP4_720)
        }

        // Start Download Button (Soporta canciones individuales y Álbumes/Playlists completos de YouTube)
        binding.btnStartDownload.setOnClickListener {
            it.bounce()
            val url = binding.etUrlInput.text.toString().trim()
            val extracted = UrlUtils.extractUrlFromText(url) ?: url

            if (extracted.isBlank()) {
                Toast.makeText(this, "Por favor ingresa o pega un enlace válido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (MediaExtractor.isYouTubePlaylist(extracted)) {
                Toast.makeText(this, "✦ OmniVault: Analizando álbum / lista de reproducción...", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch(Dispatchers.IO) {
                    val videoUrls = MediaExtractor.extractPlaylistVideoUrls(extracted)
                    withContext(Dispatchers.Main) {
                        if (videoUrls.isNotEmpty()) {
                            for (vUrl in videoUrls) {
                                MediaDownloadService.startDownload(this@MainActivity, vUrl, selectedFormat)
                            }
                            binding.etUrlInput.setText("")
                            hideCardPreview()
                            Toast.makeText(this@MainActivity, "✦ Álbum añadido: ${videoUrls.size} canciones en descarga (${selectedFormat.extension.uppercase()})", Toast.LENGTH_LONG).show()
                            refreshLists()
                        } else {
                            MediaDownloadService.startDownload(this@MainActivity, extracted, selectedFormat)
                            binding.etUrlInput.setText("")
                            hideCardPreview()
                            Toast.makeText(this@MainActivity, "✦ Descarga añadida a la cola", Toast.LENGTH_SHORT).show()
                            refreshLists()
                        }
                    }
                }
                return@setOnClickListener
            }

            MediaDownloadService.startDownload(this, extracted, selectedFormat)
            binding.etUrlInput.setText("")
            hideCardPreview()
            Toast.makeText(this, "✦ OmniVault: Descarga añadida a la cola (${selectedFormat.extension.uppercase()})", Toast.LENGTH_SHORT).show()
            refreshLists()
        }

        // Ubicación de Almacenamiento y MicroSD (Pestaña Ajustes)
        binding.tvStorageLocationSummary.text = StorageHelper.getStorageSummary(this)
        binding.btnToggleStorageLocation.setOnClickListener {
            val currentCustom = StorageHelper.getCustomStoragePath(this)
            if (currentCustom.isNullOrBlank()) {
                val extAppDir = getExternalFilesDir(null)?.absolutePath
                if (extAppDir != null) {
                    StorageHelper.setCustomStoragePath(this, extAppDir)
                    Toast.makeText(this, "💾 Carpeta de descargas cambiada a almacenamiento MicroSD / App", Toast.LENGTH_SHORT).show()
                }
            } else {
                StorageHelper.setCustomStoragePath(this, null)
                Toast.makeText(this, "💾 Ubicación restablecida a carpetas estándar de Música y Videos", Toast.LENGTH_SHORT).show()
            }
            binding.tvStorageLocationSummary.text = StorageHelper.getStorageSummary(this)
        }

        // Buscador interactivo en Pestaña Música
        binding.etMusicSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                binding.btnClearMusicSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                musicAdapter.filter(q)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearMusicSearch.setOnClickListener {
            it.bounce()
            binding.etMusicSearch.setText("")
            musicAdapter.filter("")
        }

        // Botón Ordenar Música
        binding.btnSortMusic.setOnClickListener {
            it.bounce()
            showSortDialog(isAudio = true)
        }

        // Botón Reproducir Todo en Modo Aleatorio (Pestaña Música)
        binding.btnPlayAllMusic.setOnClickListener {
            it.bounce()
            val audioItems = downloadManager.getHistory().filter { it.format.isAudio && !it.localFilePath.isNullOrBlank() }
            if (audioItems.isNotEmpty()) {
                val randomSong = audioItems.random()
                playAudioInApp(randomSong)
                Toast.makeText(this, "▶ Reproduciendo de forma aleatoria (${audioItems.size} canciones)", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Aún no tienes canciones en tu biblioteca", Toast.LENGTH_SHORT).show()
            }
        }

        // Buscador interactivo en Pestaña Videos
        binding.etVideoSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                binding.btnClearVideoSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                videoAdapter.filter(q)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearVideoSearch.setOnClickListener {
            it.bounce()
            binding.etVideoSearch.setText("")
            videoAdapter.filter("")
        }

        // Botón Ordenar Videos
        binding.btnSortVideos.setOnClickListener {
            it.bounce()
            showSortDialog(isAudio = false)
        }

        // Botón Estandarizar Nombres en Ajustes
        binding.btnStandardizeLibrary.setOnClickListener {
            it.bounce()
            val count = downloadManager.standardizeAllLibrary()
            refreshLists()
            Toast.makeText(this, "✨ Se estandarizaron $count nombres de canciones y videos", Toast.LENGTH_LONG).show()
        }

        // Botón Sincronizar Medios del Celular en Ajustes
        binding.btnSyncDeviceMedia.setOnClickListener {
            it.bounce()
            Toast.makeText(this, "🔍 Escaneando música y videos de tu celular...", Toast.LENGTH_SHORT).show()
            checkStoragePermissions()
        }

        // Botón Manual de Actualizaciones (Pestaña Ajustes)
        binding.btnCheckUpdatesManual.setOnClickListener {
            Toast.makeText(this, "Buscando actualizaciones de OmniVault...", Toast.LENGTH_SHORT).show()
            checkForUpdates(silent = false)
        }
    }

    private fun selectFormat(format: MediaFormat) {
        selectedFormat = format
        val selectedBg = R.drawable.bg_chip_selected
        val unselectedBg = R.drawable.bg_chip_unselected
        val cyan = ContextCompat.getColor(this, R.color.vault_cyan)
        val muted = ContextCompat.getColor(this, R.color.text_secondary)

        // M4A
        binding.btnFormatM4A.setBackgroundResource(if (format == MediaFormat.M4A) selectedBg else unselectedBg)
        binding.btnFormatM4A.setTextColor(if (format == MediaFormat.M4A) cyan else muted)
        binding.btnFormatM4A.text = if (format == MediaFormat.M4A) "✓ M4A • Original HQ" else "M4A • Original HQ"

        // MP3
        binding.btnFormatMP3.setBackgroundResource(if (format == MediaFormat.MP3_320) selectedBg else unselectedBg)
        binding.btnFormatMP3.setTextColor(if (format == MediaFormat.MP3_320) cyan else muted)
        binding.btnFormatMP3.text = if (format == MediaFormat.MP3_320) "✓ MP3 • 320 kbps" else "MP3 • 320 kbps"

        // 1080p
        binding.btnFormat1080p.setBackgroundResource(if (format == MediaFormat.MP4_1080) selectedBg else unselectedBg)
        binding.btnFormat1080p.setTextColor(if (format == MediaFormat.MP4_1080) cyan else muted)
        binding.btnFormat1080p.text = if (format == MediaFormat.MP4_1080) "✓ 1080p • Full HD" else "1080p • Full HD"

        // 720p
        binding.btnFormat720p.setBackgroundResource(if (format == MediaFormat.MP4_720) selectedBg else unselectedBg)
        binding.btnFormat720p.setTextColor(if (format == MediaFormat.MP4_720) cyan else muted)
        binding.btnFormat720p.text = if (format == MediaFormat.MP4_720) "✓ 720p • HD" else "720p • HD"

        val formatLabel = when (selectedFormat) {
            MediaFormat.M4A -> "AUDIO HQ (M4A)"
            MediaFormat.MP3_320 -> "AUDIO MP3 (320 KBPS)"
            MediaFormat.MP4_1080 -> "VIDEO FHD (1080P)"
            MediaFormat.MP4_720 -> "VIDEO HD (720P)"
            MediaFormat.MP4_480 -> "VIDEO SD (480P)"
            else -> "MULTIMEDIA"
        }
        binding.btnStartDownload.text = "⚡ DESCARGAR $formatLabel"
    }

    private fun setupBottomNavigation() {
        binding.tabBtnDownload.setOnClickListener { switchTab(0) }
        binding.tabBtnMusic.setOnClickListener { switchTab(1) }
        binding.tabBtnVideos.setOnClickListener { switchTab(2) }
        binding.tabBtnSettings.setOnClickListener { switchTab(3) }
    }

    private fun switchTab(index: Int) {
        val cyan = ContextCompat.getColor(this, R.color.vault_cyan)
        val muted = ContextCompat.getColor(this, R.color.text_muted)

        binding.layoutTabDownload.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.layoutTabMusic.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.layoutTabVideos.visibility = if (index == 2) View.VISIBLE else View.GONE
        binding.layoutTabSettings.visibility = if (index == 3) View.VISIBLE else View.GONE

        binding.iconNavDownload.setColorFilter(if (index == 0) cyan else muted)
        binding.iconNavMusic.setColorFilter(if (index == 1) cyan else muted)
        binding.iconNavVideos.setColorFilter(if (index == 2) cyan else muted)
        binding.iconNavSettings.setColorFilter(if (index == 3) cyan else muted)

        binding.labelNavDownload.setTextColor(if (index == 0) cyan else muted)
        binding.labelNavMusic.setTextColor(if (index == 1) cyan else muted)
        binding.labelNavVideos.setTextColor(if (index == 2) cyan else muted)
        binding.labelNavSettings.setTextColor(if (index == 3) cyan else muted)

        refreshLists()
        if (index == 1) refreshPlaylists()
    }

    private fun analyzeUrl(url: String) {
        val cleanUrl = UrlUtils.extractUrlFromText(url) ?: url
        if (cleanUrl.isBlank()) return

        binding.pbAnalyzing.visibility = View.VISIBLE
        binding.btnAnalyzeUrl.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val preview = MediaExtractor.analyze(cleanUrl)
                withContext(Dispatchers.Main) {
                    binding.pbAnalyzing.visibility = View.GONE
                    binding.btnAnalyzeUrl.isEnabled = true

                    binding.tvPreviewTitle.text = preview.title
                    binding.tvPreviewAuthor.text = "👤 ${preview.author}"
                    binding.tvPreviewBadge.text = preview.platform.displayName

                    if (preview.durationText.isNotBlank()) {
                        binding.tvPreviewDuration.text = "⏱ ${preview.durationText}"
                        binding.tvPreviewDuration.visibility = View.VISIBLE
                    } else {
                        binding.tvPreviewDuration.visibility = View.GONE
                    }

                    showCardPreview()
                    loadThumbnailImage(preview.thumbnailUrl)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.pbAnalyzing.visibility = View.GONE
                    binding.btnAnalyzeUrl.isEnabled = true
                }
            }
        }
    }

    private fun loadThumbnailImage(thumbUrl: String) {
        if (thumbUrl.isBlank()) {
            binding.imgPreviewThumbnail.setImageResource(R.mipmap.ic_launcher)
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = OkHttpClient()
                val req = Request.Builder().url(thumbUrl).build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val stream = resp.body?.byteStream()
                        val bitmap = BitmapFactory.decodeStream(stream)
                        if (bitmap != null) {
                            withContext(Dispatchers.Main) {
                                binding.imgPreviewThumbnail.alpha = 0f
                                binding.imgPreviewThumbnail.setImageBitmap(bitmap)
                                binding.imgPreviewThumbnail.animate().alpha(1f).setDuration(180).start()
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private fun checkForUpdates(silent: Boolean) {
        UpdateManager.checkForUpdates(
            this,
            silent = silent,
            onUpdateFound = { info ->
                pendingUpdateInfo = info
                binding.bannerUpdateAvailable.visibility = View.VISIBLE
                binding.tvUpdateTitle.text = "¡OmniVault v${info.version} disponible!"
                binding.tvUpdateSubtitle.text = "Nueva versión oficial lista para actualizar con 1 toque"
            },
            onNoUpdate = {
                if (!silent) {
                    Toast.makeText(this, "✓ Ya tienes la versión más reciente (v${UpdateManager.CURRENT_VERSION_NAME})", Toast.LENGTH_LONG).show()
                }
            },
            onError = { msg ->
                if (!silent) {
                    Toast.makeText(this, "No se pudo verificar actualizaciones: $msg", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun startUpdateDownload(apkUrl: String, version: String) {
        Toast.makeText(this, "⚡ Descargando actualización OmniVault v$version...", Toast.LENGTH_LONG).show()
        binding.btnUpdateNow.isEnabled = false
        binding.btnUpdateNow.text = "Descargando..."

        UpdateManager.downloadAndInstallUpdate(
            context = this,
            apkUrl = apkUrl,
            version = version,
            onProgress = { progress, speed ->
                binding.tvUpdateSubtitle.text = "Descargando: $progress% ($speed)"
            },
            onComplete = {
                binding.btnUpdateNow.isEnabled = true
                binding.btnUpdateNow.text = "Instalar"
                Toast.makeText(this, "✦ Descarga lista. Iniciando instalador...", Toast.LENGTH_SHORT).show()
            },
            onError = { err ->
                binding.btnUpdateNow.isEnabled = true
                binding.btnUpdateNow.text = "Reintentar"
                Toast.makeText(this, "Error al actualizar: $err", Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun checkClipboardForMediaUrl() {
        try {
            val clipMgr = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipMgr.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: ""
                val extracted = UrlUtils.extractUrlFromText(text)
                if (!extracted.isNullOrBlank() && UrlUtils.isSupportedMediaUrl(extracted) && binding.etUrlInput.text.isEmpty()) {
                    binding.etUrlInput.setText(extracted)
                    updatePlatformBadge(extracted)
                    analyzeUrl(extracted)
                    Toast.makeText(this, "✦ Enlace auto-detectado del portapapeles", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {}
    }

    private fun updatePlatformBadge(url: String) {
        val extracted = UrlUtils.extractUrlFromText(url)
        if (!extracted.isNullOrBlank()) {
            val platform = PlatformType.fromUrl(extracted)
            binding.tvDetectedPlatform.text = platform.displayName
        } else {
            binding.tvDetectedPlatform.text = "Listo para pegar"
        }
    }

    private fun refreshLists() {
        val active = downloadManager.getActiveDownloads()
        val history = downloadManager.getHistory()

        // 1. Descargas activas
        if (active.isNotEmpty()) {
            binding.tvActiveDownloadsHeader.visibility = View.VISIBLE
            binding.rvActiveDownloads.visibility = View.VISIBLE
            downloadAdapter.updateList(active)
        } else {
            binding.tvActiveDownloadsHeader.visibility = View.GONE
            binding.rvActiveDownloads.visibility = View.GONE
        }

        // 2. Historial en Pestaña Descargas
        val audioTracks = history.filter { it.format.isAudio }
        val videoTracks = history.filter { !it.format.isAudio }

        binding.btnViewAllSongs.text = "Ver Todas (${audioTracks.size})"

        if (history.isNotEmpty()) {
            binding.rvDownloadHistory.visibility = View.VISIBLE
            binding.tvEmptyHistory.visibility = View.GONE
            recentAdapter.updateList(history.take(8))
        } else {
            binding.rvDownloadHistory.visibility = View.GONE
            binding.tvEmptyHistory.visibility = View.VISIBLE
        }

        // 3. Pestaña Música
        binding.tvTotalMusicCount.text = "${audioTracks.size} canciones"
        if (audioTracks.isNotEmpty()) {
            binding.rvMusicLibrary.visibility = View.VISIBLE
            binding.tvEmptyMusic.visibility = View.GONE
            musicAdapter.updateList(audioTracks)
        } else {
            binding.rvMusicLibrary.visibility = View.GONE
            binding.tvEmptyMusic.visibility = View.VISIBLE
        }

        // 4. Pestaña Videos
        binding.tvTotalVideosCount.text = "${videoTracks.size} videos"
        if (videoTracks.isNotEmpty()) {
            binding.rvVideoLibrary.visibility = View.VISIBLE
            binding.tvEmptyVideos.visibility = View.GONE
            videoAdapter.updateList(history)
        } else {
            binding.rvVideoLibrary.visibility = View.GONE
            binding.tvEmptyVideos.visibility = View.VISIBLE
        }
    }

    private var activePlayerDialog: BottomSheetDialog? = null

    private fun playMediaFile(item: DownloadItem) {
        val path = item.localFilePath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "El archivo ya no se encuentra en el dispositivo", Toast.LENGTH_SHORT).show()
            return
        }

        downloadManager.recordPlay(item)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AppDatabase.getInstance(this@MainActivity).trackDao().incrementPlayCount("track_${item.id}")
            } catch (_: Exception) {}
        }

        if (item.format.isAudio) {
            playAudioInApp(item)
        } else {
            val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.format.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "No se encontró un reproductor de video compatible", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playAudioInApp(item: DownloadItem) {
        val path = item.localFilePath ?: return
        val current = AudioPlayerManager.getCurrentTrack()
        if (current?.filePath != path) {
            val track = NowPlayingTrack(
                filePath = path,
                title = item.title,
                artist = if (!item.artist.isNullOrBlank()) item.artist!! else "OmniVault",
                coverPath = item.thumbnailUrl
            )
            AudioPlayerManager.playTrack(track)
        } else if (!AudioPlayerManager.isPlaying()) {
            AudioPlayerManager.togglePlayPause()
        }
        showFullPlayerBottomSheet(item)
    }

    private fun showFullPlayerBottomSheet(item: DownloadItem) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_full_player, null)
        dialog.setContentView(view)
        activePlayerDialog = dialog

        val imgCover = view.findViewById<ShapeableImageView>(R.id.imgFullPlayerCover)
        val tvTitle = view.findViewById<TextView>(R.id.tvFullPlayerTitle)
        val tvArtist = view.findViewById<TextView>(R.id.tvFullPlayerArtist)
        val seekBar = view.findViewById<SeekBar>(R.id.seekBarPlayer)
        val tvCurrentTime = view.findViewById<TextView>(R.id.tvCurrentTime)
        val tvTotalDuration = view.findViewById<TextView>(R.id.tvTotalDuration)
        val fabPlayPause = view.findViewById<FloatingActionButton>(R.id.fabPlayPause)
        val btnPrev = view.findViewById<ImageButton>(R.id.btnPlayerPrev)
        val btnNext = view.findViewById<ImageButton>(R.id.btnPlayerNext)
        val btnShuffle = view.findViewById<ImageButton>(R.id.btnPlayerShuffle)
        val btnRepeat = view.findViewById<ImageButton>(R.id.btnPlayerRepeat)
        val btnToggleLyrics = view.findViewById<MaterialButton>(R.id.btnToggleLyrics)
        val btnToggleEqualizer = view.findViewById<MaterialButton>(R.id.btnToggleEqualizer)
        val btnShare = view.findViewById<MaterialButton>(R.id.btnFullPlayerShare)
        val scrollLyrics = view.findViewById<NestedScrollView>(R.id.scrollLyrics)
        val tvLyrics = view.findViewById<TextView>(R.id.tvLyricsContent)

        // Controles de Super Volumen 300%
        val seekBarVol = view.findViewById<SeekBar>(R.id.seekBarVolumeBoost)
        val tvVolBadge = view.findViewById<TextView>(R.id.tvVolumeBoostBadge)

        // Panel de Ecualizador
        val layoutEq = view.findViewById<View>(R.id.layoutEqualizerPanel)
        val seekBass = view.findViewById<SeekBar>(R.id.seekBassBoost)
        val tvBass = view.findViewById<TextView>(R.id.tvBassBoostLabel)
        val seekVirt = view.findViewById<SeekBar>(R.id.seekVirtualizer)
        val tvVirt = view.findViewById<TextView>(R.id.tvVirtualizerLabel)

        tvTitle.text = item.title
        tvArtist.text = if (!item.artist.isNullOrBlank()) item.artist else "OmniVault Audio"

        // Carga de Carátula Optimizada con Coil + Palette API para Fondo Dinámico
        val coverBitmap = getCoverBitmapForItem(item)
        if (coverBitmap != null) {
            imgCover.setImageBitmap(coverBitmap)
            try {
                Palette.from(coverBitmap).generate { palette ->
                    val dominant = palette?.getDarkVibrantColor(ContextCompat.getColor(this, R.color.vault_bg_dark))
                        ?: ContextCompat.getColor(this, R.color.vault_bg_dark)
                    view.findViewById<View>(R.id.layoutFullPlayerRoot)?.setBackgroundColor(dominant)
                }
            } catch (_: Exception) {}
        } else {
            val thumb = item.thumbnailUrl
            if (!thumb.isNullOrBlank()) {
                imgCover.load(File(thumb)) {
                    crossfade(true)
                    error(R.mipmap.ic_launcher)
                }
            } else {
                imgCover.setImageResource(R.mipmap.ic_launcher)
            }
        }

        val durationMs = AudioPlayerManager.getDuration()
        val posMs = AudioPlayerManager.getCurrentPosition()
        seekBar.max = if (durationMs > 0) durationMs else 1000
        seekBar.progress = posMs
        tvTotalDuration.text = formatMs(durationMs)
        tvCurrentTime.text = formatMs(posMs)

        // Control de Super Volumen (0% a 300%) con LoudnessEnhancer
        val updateVolumeUi: (Int) -> Unit = { pct ->
            seekBarVol.progress = pct
            when {
                pct <= 100 -> {
                    tvVolBadge.text = "$pct% NORMAL"
                    tvVolBadge.setTextColor(ContextCompat.getColor(this, R.color.vault_cyan))
                    seekBarVol.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vault_cyan))
                    seekBarVol.thumbTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vault_cyan))
                }
                pct <= 200 -> {
                    val extraDb = Math.round((pct - 100) * 0.1)
                    tvVolBadge.text = "$pct% (+${extraDb}dB BOOST)"
                    tvVolBadge.setTextColor(ContextCompat.getColor(this, R.color.vault_purple))
                    seekBarVol.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vault_purple))
                    seekBarVol.thumbTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vault_purple))
                }
                else -> {
                    val extraDb = Math.round((pct - 100) * 0.15)
                    tvVolBadge.text = "$pct% SUPER BOOST (+${extraDb}dB)"
                    tvVolBadge.setTextColor(ContextCompat.getColor(this, R.color.vault_magenta))
                    seekBarVol.progressTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vault_magenta))
                    seekBarVol.thumbTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.vault_magenta))
                }
            }
        }

        updateVolumeUi(AudioPlayerManager.getVolumePercent())

        seekBarVol.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    AudioPlayerManager.setVolumeBoost(progress)
                    updateVolumeUi(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Ecualizador y Bass Boost
        btnToggleEqualizer.setOnClickListener {
            layoutEq.visibility = if (layoutEq.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        seekBass.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    AudioPlayerManager.setBassBoost(progress.toShort())
                    tvBass.text = "Refuerzo de Graves (Bass Boost): +${progress / 100} dB"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        seekVirt.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    AudioPlayerManager.setVirtualizer(progress.toShort())
                    tvVirt.text = "Sonido Envolvente 3D (Virtualizer): ${progress / 10}%"
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        // Shuffle y Repeat
        btnShuffle.setOnClickListener {
            val enabled = AudioPlayerManager.toggleShuffle()
            btnShuffle.imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (enabled) R.color.vault_cyan else R.color.text_muted)
            )
        }

        btnRepeat.setOnClickListener {
            val mode = AudioPlayerManager.toggleRepeat()
            val color = if (mode != androidx.media3.common.Player.REPEAT_MODE_OFF) R.color.vault_cyan else R.color.text_muted
            btnRepeat.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, color))
        }

        val updatePlayIcon = {
            if (AudioPlayerManager.isPlaying()) {
                fabPlayPause.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                fabPlayPause.setImageResource(android.R.drawable.ic_media_play)
            }
        }
        updatePlayIcon()

        fabPlayPause.setOnClickListener {
            AudioPlayerManager.togglePlayPause()
            updatePlayIcon()
        }

        btnPrev.setOnClickListener {
            AudioPlayerManager.playPreviousTrack()
        }

        btnNext.setOnClickListener {
            AudioPlayerManager.playNextTrack()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    AudioPlayerManager.seekTo(progress)
                    tvCurrentTime.text = formatMs(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnToggleLyrics.setOnClickListener {
            if (scrollLyrics.visibility == View.VISIBLE) {
                scrollLyrics.visibility = View.GONE
                btnToggleLyrics.text = "🎤 Ver Letras"
            } else {
                scrollLyrics.visibility = View.VISIBLE
                btnToggleLyrics.text = "🎤 Ocultar Letras"
                loadLyricsForSong(item, tvLyrics)
            }
        }

        btnShare.setOnClickListener {
            shareMediaFile(item)
        }

        AudioPlayerManager.onProgressListener = { current, total ->
            if (dialog.isShowing) {
                seekBar.max = total
                seekBar.progress = current
                tvCurrentTime.text = formatMs(current)
                tvTotalDuration.text = formatMs(total)
            }
        }

        AudioPlayerManager.onStateChangedListener = { _, _ ->
            updatePlayIcon()
        }

        AudioPlayerManager.onTrackChangedListener = { newTrack ->
            if (newTrack != null && dialog.isShowing) {
                tvTitle.text = newTrack.title
                tvArtist.text = newTrack.artist
            }
        }

        dialog.setOnDismissListener {
            activePlayerDialog = null
        }

        dialog.show()
    }

    private fun loadLyricsForSong(item: DownloadItem, tvLyrics: TextView) {
        val path = item.localFilePath ?: return
        val audioFile = File(path)
        val lrcFile = File(audioFile.parentFile, "${audioFile.nameWithoutExtension}.lrc")
        if (lrcFile.exists()) {
            try {
                tvLyrics.text = lrcFile.readText(Charsets.UTF_8)
                return
            } catch (_: Exception) {}
        }

        tvLyrics.text = "Buscando letras en línea..."
        lifecycleScope.launch(Dispatchers.IO) {
            val lyrics = LyricsHelper.fetchLyrics(item.title, item.artist ?: "")
            withContext(Dispatchers.Main) {
                if (!lyrics.isNullOrBlank()) {
                    tvLyrics.text = lyrics
                } else {
                    tvLyrics.text = "No se encontraron letras disponibles para esta canción."
                }
            }
        }
    }

    private fun getCoverBitmapForItem(item: DownloadItem): android.graphics.Bitmap? {
        val thumb = item.thumbnailUrl
        if (!thumb.isNullOrBlank()) {
            val f = File(thumb)
            if (f.exists()) {
                try {
                    val bm = BitmapFactory.decodeFile(f.absolutePath)
                    if (bm != null) return bm
                } catch (_: Exception) {}
            }
        }
        val cached = item.id.let { CoverArtHelper.getCachedCoverFile(this, it) }
        if (cached != null && cached.exists()) {
            try {
                val bm = BitmapFactory.decodeFile(cached.absolutePath)
                if (bm != null) return bm
            } catch (_: Exception) {}
        }
        return null
    }

    private fun formatMs(ms: Int): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return String.format("%02d:%02d", min, sec)
    }

    private fun shareMediaFile(item: DownloadItem) {
        val path = item.localFilePath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "El archivo no existe", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        val mimeType = if (file.name.endsWith(".m4a")) "audio/mp4" else item.format.mimeType

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Compartir con"))
    }

    private fun showCardPreview() {
        binding.cardPreview.alpha = 0f
        binding.cardPreview.translationY = 15f
        binding.cardPreview.visibility = View.VISIBLE
        binding.cardPreview.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }

    private fun hideCardPreview() {
        if (binding.cardPreview.visibility == View.VISIBLE) {
            binding.cardPreview.animate()
                .alpha(0f)
                .translationY(-10f)
                .setDuration(160)
                .withEndAction {
                    binding.cardPreview.visibility = View.GONE
                    binding.cardPreview.alpha = 1f
                    binding.cardPreview.translationY = 0f
                }.start()
        } else {
            binding.cardPreview.visibility = View.GONE
        }
    }

    private fun View.bounce() {
        try {
            performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        } catch (_: Exception) {}
        animate().scaleX(0.93f).scaleY(0.93f).setDuration(70).withEndAction {
            animate().scaleX(1f).scaleY(1f).setDuration(70).start()
        }.start()
    }
}

package com.omnivault.media.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import com.omnivault.media.R
import com.omnivault.media.databinding.ActivityMainBinding
import com.omnivault.media.downloader.DownloadManager
import com.omnivault.media.downloader.MediaExtractor
import com.omnivault.media.downloader.MediaPreview
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.PlatformType
import com.omnivault.media.player.AudioPlayerManager
import com.omnivault.media.player.NowPlayingTrack
import com.omnivault.media.service.ClipboardMonitorService
import com.omnivault.media.utils.CoverArtHelper
import com.omnivault.media.utils.LyricsHelper
import com.omnivault.media.utils.NotificationHelper
import com.omnivault.media.utils.StorageHelper
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.omnivault.media.service.MediaDownloadService
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

    private lateinit var binding: ActivityMainBinding
    private lateinit var downloadManager: DownloadManager
    private lateinit var downloadAdapter: DownloadAdapter
    private lateinit var historyAdapter: HistoryAdapter

    private var selectedFormat: MediaFormat = MediaFormat.M4A
    private var pendingUpdateInfo: UpdateInfo? = null

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
        binding.tvAppSubtitle.text = "OmniVault v${UpdateManager.CURRENT_VERSION_NAME} • Audio-First Edition"

        downloadManager = DownloadManager.getInstance(this)
        NotificationHelper.createNotificationChannels(this)
        checkNotificationPermission()

        setupRecyclerViews()
        setupListeners()
        handlePreloadedUrl(intent)
        checkClipboardForMediaUrl()

        // Comprobación de actualizaciones en segundo plano
        checkForUpdates(silent = true)
        handleUpdateIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshLists()
        checkClipboardForMediaUrl()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePreloadedUrl(intent)
        handleUpdateIntent(intent)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
        // Active Downloads
        downloadAdapter = DownloadAdapter { item ->
            downloadManager.cancelDownload(item.id)
            refreshLists()
        }
        binding.rvActiveDownloads.layoutManager = LinearLayoutManager(this)
        binding.rvActiveDownloads.adapter = downloadAdapter

        // History
        historyAdapter = HistoryAdapter(
            onPlayClick = { item -> playMediaFile(item) },
            onShareClick = { item -> shareMediaFile(item) },
            onDeleteClick = { item ->
                downloadManager.removeHistoryItem(item)
                refreshLists()
            }
        )
        binding.rvDownloadHistory.layoutManager = LinearLayoutManager(this)
        binding.rvDownloadHistory.adapter = historyAdapter

        downloadManager.onDownloadUpdatedListener = {
            runOnUiThread { refreshLists() }
        }
        downloadManager.onHistoryUpdatedListener = {
            runOnUiThread { refreshLists() }
        }
        refreshLists()
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

        // Helper para texto contextual del botón de descarga
        fun updateDownloadButtonText() {
            val formatLabel = when (selectedFormat) {
                MediaFormat.M4A -> "AUDIO M4A (ORIGINAL HQ)"
                MediaFormat.MP3_320 -> "AUDIO MP3 (320 KBPS)"
                MediaFormat.MP4_1080 -> "VIDEO FHD (1080P)"
                MediaFormat.MP4_720 -> "VIDEO HD (720P)"
                MediaFormat.MP4_480 -> "VIDEO SD (480P)"
                else -> "MULTIMEDIA"
            }
            binding.btnStartDownload.text = "⚡ DESCARGAR $formatLabel"
        }
        updateDownloadButtonText()

        // Format Chips con respuesta háptica y actualización dinámica
        binding.chipGroupFormats.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedFormat = when {
                checkedIds.contains(binding.chipM4a.id) -> MediaFormat.M4A
                checkedIds.contains(binding.chipMp3.id) -> MediaFormat.MP3_320
                checkedIds.contains(binding.chipMp4Full.id) -> MediaFormat.MP4_1080
                checkedIds.contains(binding.chipMp4Hd.id) -> MediaFormat.MP4_720
                else -> MediaFormat.M4A
            }
            updateDownloadButtonText()
            try {
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            } catch (_: Exception) {}
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

        // Clear History
        binding.tvClearHistory.setOnClickListener {
            downloadManager.clearAllHistory()
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

        // Buscador interactivo en Mi Bóveda (Biblioteca)
        binding.etLibrarySearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString() ?: ""
                binding.btnClearLibrarySearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                historyAdapter.filter(q)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.btnClearLibrarySearch.setOnClickListener {
            it.bounce()
            binding.etLibrarySearch.setText("")
            historyAdapter.filter("")
        }

        // Navegación Inferior (Pestañas Concept 3: Spotify Vault)
        setupBottomNavigation()

        // Botón Manual de Actualizaciones (Pestaña Ajustes)
        binding.btnCheckUpdatesManual.setOnClickListener {
            Toast.makeText(this, "Buscando actualizaciones de OmniVault...", Toast.LENGTH_SHORT).show()
            checkForUpdates(silent = false)
        }
    }

    private fun setupBottomNavigation() {
        binding.tabBtnDownload.setOnClickListener { switchTab(0) }
        binding.tabBtnLibrary.setOnClickListener { switchTab(1) }
        binding.tabBtnSettings.setOnClickListener { switchTab(2) }
    }

    private fun switchTab(index: Int) {
        val colorActive = getColor(R.color.spotify_green)
        val colorMuted = getColor(R.color.text_muted)

        when (index) {
            0 -> {
                binding.layoutTabDownload.visibility = View.VISIBLE
                binding.layoutTabLibrary.visibility = View.GONE
                binding.layoutTabSettings.visibility = View.GONE

                binding.labelNavDownload.setTextColor(colorActive)
                binding.labelNavLibrary.setTextColor(colorMuted)
                binding.labelNavSettings.setTextColor(colorMuted)

                binding.iconNavDownload.setColorFilter(colorActive)
                binding.iconNavLibrary.setColorFilter(colorMuted)
                binding.iconNavSettings.setColorFilter(colorMuted)
            }
            1 -> {
                binding.layoutTabDownload.visibility = View.GONE
                binding.layoutTabLibrary.visibility = View.VISIBLE
                binding.layoutTabSettings.visibility = View.GONE

                binding.labelNavDownload.setTextColor(colorMuted)
                binding.labelNavLibrary.setTextColor(colorActive)
                binding.labelNavSettings.setTextColor(colorMuted)

                binding.iconNavDownload.setColorFilter(colorMuted)
                binding.iconNavLibrary.setColorFilter(colorActive)
                binding.iconNavSettings.setColorFilter(colorMuted)
                refreshLists()
            }
            2 -> {
                binding.layoutTabDownload.visibility = View.GONE
                binding.layoutTabLibrary.visibility = View.GONE
                binding.layoutTabSettings.visibility = View.VISIBLE

                binding.labelNavDownload.setTextColor(colorMuted)
                binding.labelNavLibrary.setTextColor(colorMuted)
                binding.labelNavSettings.setTextColor(colorActive)

                binding.iconNavDownload.setColorFilter(colorMuted)
                binding.iconNavLibrary.setColorFilter(colorMuted)
                binding.iconNavSettings.setColorFilter(colorActive)
            }
        }
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

        if (active.isNotEmpty()) {
            binding.tvActiveDownloadsHeader.visibility = View.VISIBLE
            binding.rvActiveDownloads.visibility = View.VISIBLE
            downloadAdapter.updateList(active)
        } else {
            binding.tvActiveDownloadsHeader.visibility = View.GONE
            binding.rvActiveDownloads.visibility = View.GONE
        }

        if (history.isNotEmpty()) {
            binding.rvDownloadHistory.visibility = View.VISIBLE
            binding.tvEmptyHistory.visibility = View.GONE
            historyAdapter.updateList(history)
        } else {
            binding.rvDownloadHistory.visibility = View.GONE
            binding.tvEmptyHistory.visibility = View.VISIBLE
        }

        // Mini-reproductor flotante persistente estilo Spotify Vault
        val latest = history.firstOrNull()
        if (latest != null && !latest.localFilePath.isNullOrBlank()) {
            binding.cardMiniPlayer.visibility = View.VISIBLE
            binding.tvMiniPlayerTitle.text = latest.title
            val artistOrPlatform = if (!latest.artist.isNullOrBlank()) latest.artist else latest.platform.displayName
            binding.tvMiniPlayerSubtitle.text = "$artistOrPlatform • Toca para reproducir con carátula"

            // Carátula del mini-reproductor
            var coverSet = false
            val thumb = latest.thumbnailUrl
            if (!thumb.isNullOrBlank()) {
                val f = File(thumb)
                if (f.exists()) {
                    try {
                        val bm = BitmapFactory.decodeFile(f.absolutePath)
                        if (bm != null) {
                            binding.imgMiniPlayerCover.setImageBitmap(bm)
                            coverSet = true
                        }
                    } catch (_: Exception) {}
                }
            }
            if (!coverSet) {
                val cached = latest.id?.let { CoverArtHelper.getCachedCoverFile(this, it) }
                if (cached != null && cached.exists()) {
                    try {
                        val bm = BitmapFactory.decodeFile(cached.absolutePath)
                        if (bm != null) {
                            binding.imgMiniPlayerCover.setImageBitmap(bm)
                            coverSet = true
                        }
                    } catch (_: Exception) {}
                }
            }
            if (!coverSet) {
                binding.imgMiniPlayerCover.setImageResource(R.mipmap.ic_launcher)
            }

            val isCurrentTrackPlaying = AudioPlayerManager.isPlaying() && AudioPlayerManager.getCurrentTrack()?.filePath == latest.localFilePath
            binding.btnMiniPlayerPlay.setImageResource(
                if (isCurrentTrackPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )

            binding.cardMiniPlayer.setOnClickListener {
                if (latest.format.isAudio) {
                    playAudioInApp(latest)
                } else {
                    playMediaFile(latest)
                }
            }
            binding.btnMiniPlayerPlay.setOnClickListener {
                if (latest.format.isAudio) {
                    if (AudioPlayerManager.getCurrentTrack()?.filePath == latest.localFilePath) {
                        AudioPlayerManager.togglePlayPause()
                    } else {
                        playAudioInApp(latest)
                    }
                } else {
                    playMediaFile(latest)
                }
            }
            binding.btnMiniPlayerShare.setOnClickListener { shareMediaFile(latest) }
        } else {
            binding.cardMiniPlayer.visibility = View.GONE
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
        val btnToggleLyrics = view.findViewById<MaterialButton>(R.id.btnToggleLyrics)
        val btnShare = view.findViewById<MaterialButton>(R.id.btnFullPlayerShare)
        val scrollLyrics = view.findViewById<NestedScrollView>(R.id.scrollLyrics)
        val tvLyrics = view.findViewById<TextView>(R.id.tvLyricsContent)

        tvTitle.text = item.title
        tvArtist.text = if (!item.artist.isNullOrBlank()) item.artist else "OmniVault Audio"

        val coverBitmap = getCoverBitmapForItem(item)
        if (coverBitmap != null) {
            imgCover.setImageBitmap(coverBitmap)
        } else {
            imgCover.setImageResource(R.mipmap.ic_launcher)
        }

        val durationMs = AudioPlayerManager.getDuration()
        val posMs = AudioPlayerManager.getCurrentPosition()
        seekBar.max = if (durationMs > 0) durationMs else 1000
        seekBar.progress = posMs
        tvTotalDuration.text = formatMs(durationMs)
        tvCurrentTime.text = formatMs(posMs)

        val updatePlayIcon = {
            if (AudioPlayerManager.isPlaying()) {
                fabPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                binding.btnMiniPlayerPlay.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                fabPlayPause.setImageResource(android.R.drawable.ic_media_play)
                binding.btnMiniPlayerPlay.setImageResource(android.R.drawable.ic_media_play)
            }
        }
        updatePlayIcon()

        fabPlayPause.setOnClickListener {
            AudioPlayerManager.togglePlayPause()
            updatePlayIcon()
        }

        btnPrev.setOnClickListener {
            val cur = AudioPlayerManager.getCurrentPosition()
            AudioPlayerManager.seekTo(maxOf(0, cur - 10000))
        }

        btnNext.setOnClickListener {
            val cur = AudioPlayerManager.getCurrentPosition()
            val total = AudioPlayerManager.getDuration()
            AudioPlayerManager.seekTo(minOf(total, cur + 10000))
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

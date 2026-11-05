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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.omnivault.media.R
import com.omnivault.media.databinding.ActivityMainBinding
import com.omnivault.media.downloader.DownloadManager
import com.omnivault.media.downloader.MediaExtractor
import com.omnivault.media.downloader.MediaPreview
import com.omnivault.media.model.DownloadItem
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.PlatformType
import com.omnivault.media.service.ClipboardMonitorService
import com.omnivault.media.utils.CoverArtHelper
import com.omnivault.media.utils.NotificationHelper
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

        downloadManager = DownloadManager.getInstance(this)
        NotificationHelper.createNotificationChannels(this)

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
            binding.etUrlInput.setText("")
            binding.cardPreview.visibility = View.GONE
        }

        // TextWatcher
        binding.etUrlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val text = s?.toString() ?: ""
                binding.btnClearUrl.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
                updatePlatformBadge(text)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Format Chips
        binding.chipGroupFormats.setOnCheckedStateChangeListener { _, checkedIds ->
            selectedFormat = when {
                checkedIds.contains(binding.chipM4a.id) -> MediaFormat.M4A
                checkedIds.contains(binding.chipMp3.id) -> MediaFormat.MP3_320
                checkedIds.contains(binding.chipMp4Full.id) -> MediaFormat.MP4_1080
                checkedIds.contains(binding.chipMp4Hd.id) -> MediaFormat.MP4_720
                else -> MediaFormat.M4A
            }
        }

        // Start Download Button
        binding.btnStartDownload.setOnClickListener {
            val url = binding.etUrlInput.text.toString().trim()
            val extracted = UrlUtils.extractUrlFromText(url) ?: url

            if (extracted.isBlank()) {
                Toast.makeText(this, "Por favor ingresa o pega un enlace válido", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            downloadManager.enqueueDownload(extracted, selectedFormat)
            binding.etUrlInput.setText("")
            binding.cardPreview.visibility = View.GONE
            Toast.makeText(this, "✦ OmniVault: Descarga añadida a la cola (${selectedFormat.extension.uppercase()})", Toast.LENGTH_SHORT).show()
            refreshLists()
        }

        // Clear History
        binding.tvClearHistory.setOnClickListener {
            downloadManager.clearAllHistory()
            refreshLists()
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
            }
            1 -> {
                binding.layoutTabDownload.visibility = View.GONE
                binding.layoutTabLibrary.visibility = View.VISIBLE
                binding.layoutTabSettings.visibility = View.GONE

                binding.labelNavDownload.setTextColor(colorMuted)
                binding.labelNavLibrary.setTextColor(colorActive)
                binding.labelNavSettings.setTextColor(colorMuted)
                refreshLists()
            }
            2 -> {
                binding.layoutTabDownload.visibility = View.GONE
                binding.layoutTabLibrary.visibility = View.GONE
                binding.layoutTabSettings.visibility = View.VISIBLE

                binding.labelNavDownload.setTextColor(colorMuted)
                binding.labelNavLibrary.setTextColor(colorMuted)
                binding.labelNavSettings.setTextColor(colorActive)
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

                    binding.cardPreview.visibility = View.VISIBLE
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
                                binding.imgPreviewThumbnail.setImageBitmap(bitmap)
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

            binding.cardMiniPlayer.setOnClickListener { playMediaFile(latest) }
            binding.btnMiniPlayerPlay.setOnClickListener { playMediaFile(latest) }
            binding.btnMiniPlayerShare.setOnClickListener { shareMediaFile(latest) }
        } else {
            binding.cardMiniPlayer.visibility = View.GONE
        }
    }

    private fun playMediaFile(item: DownloadItem) {
        val path = item.localFilePath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "El archivo ya no se encuentra en el dispositivo", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        val mimeType = if (file.name.endsWith(".m4a")) "audio/mp4" else item.format.mimeType

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "No se encontró un reproductor compatible", Toast.LENGTH_SHORT).show()
        }
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
}

package com.omnivault.media.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.omnivault.media.databinding.ActivityQuickShareBinding
import com.omnivault.media.model.MediaFormat
import com.omnivault.media.model.PlatformType
import com.omnivault.media.service.MediaDownloadService
import com.omnivault.media.utils.UrlUtils

class QuickShareActivity : Activity() {

    private lateinit var binding: ActivityQuickShareBinding
    private var targetUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickShareBinding.inflate(layoutInflater)
        setContentView(binding.root)

        handleIncomingIntent(intent)
        setupClickListeners()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val extracted = UrlUtils.extractUrlFromText(sharedText)

            if (!extracted.isNullOrBlank()) {
                targetUrl = extracted
                binding.tvSharedUrl.text = extracted
                val platform = PlatformType.fromUrl(extracted)
                binding.tvPlatformBadge.text = "Detectado: ${platform.displayName}"
            } else {
                Toast.makeText(this, "No se detectó una URL válida en el contenido compartido", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            finish()
        }
    }

    private fun setupClickListeners() {
        binding.btnClose.setOnClickListener {
            finish()
        }

        binding.btnDownloadMp3.setOnClickListener {
            startDownloadAndFinish(MediaFormat.MP3_320)
        }

        binding.btnDownloadMp4Full.setOnClickListener {
            startDownloadAndFinish(MediaFormat.MP4_1080)
        }

        binding.btnDownloadMp4Hd.setOnClickListener {
            startDownloadAndFinish(MediaFormat.MP4_720)
        }

        binding.btnOpenMain.setOnClickListener {
            val mainIntent = Intent(this, MainActivity::class.java).apply {
                putExtra("preloaded_url", targetUrl)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            startActivity(mainIntent)
            finish()
        }
    }

    private fun startDownloadAndFinish(format: MediaFormat) {
        val url = targetUrl ?: return
        val serviceIntent = Intent(this, MediaDownloadService::class.java).apply {
            action = MediaDownloadService.ACTION_START_DOWNLOAD
            putExtra(MediaDownloadService.EXTRA_URL, url)
            putExtra(MediaDownloadService.EXTRA_FORMAT, format.name)
        }
        startService(serviceIntent)
        Toast.makeText(this, "✦ OmniVault: Descarga iniciada (${format.extension.uppercase()})", Toast.LENGTH_LONG).show()
        finish()
    }
}

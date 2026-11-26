package com.amurayada.music.ui.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amurayada.music.BuildConfig
import com.amurayada.music.data.model.Song
import com.amurayada.music.data.repository.DuplicateDeletionResult
import com.amurayada.music.data.repository.MediaCleanupRepository
import com.amurayada.music.data.repository.PreferencesRepository
import com.amurayada.music.data.repository.ReleaseInfo
import com.amurayada.music.data.repository.UpdateRepository
import com.amurayada.music.data.repository.UpdateRepositoryImpl
import com.omnivault.media.model.DownloadItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

/**
 * ViewModel encargado de la gestión de configuraciones globales de la aplicación,
 * persistencia de preferencias de usuario, limpieza de duplicados y actualizaciones.
 */
class SettingsViewModel(
    private val preferencesRepository: PreferencesRepository,
    private val mediaCleanupRepository: MediaCleanupRepository,
    private val updateRepository: UpdateRepository = UpdateRepositoryImpl(OkHttpClient()),
    private val appCacheDir: File? = null,
    private val appFilesDir: File? = null,
    private val currentVersion: String = BuildConfig.VERSION_NAME
) : ViewModel() {

    private val client = OkHttpClient()

    val CURRENT_VERSION: String
        get() = currentVersion

    // --- Estados de Configuración ---

    private val _themeMode = MutableStateFlow(preferencesRepository.getThemeMode())
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _useDynamicColors = MutableStateFlow(preferencesRepository.getDynamicColors())
    val useDynamicColors: StateFlow<Boolean> = _useDynamicColors.asStateFlow()

    private val _sleepTimerMinutes = MutableStateFlow(preferencesRepository.getSleepTimerMinutes())
    val sleepTimerMinutes: StateFlow<Int> = _sleepTimerMinutes.asStateFlow()

    private val _autoResumeDownloads = MutableStateFlow(preferencesRepository.getAutoResumeDownloads())
    val autoResumeDownloads: StateFlow<Boolean> = _autoResumeDownloads.asStateFlow()

    private val _clipboardMonitorEnabled = MutableStateFlow(preferencesRepository.getClipboardMonitorEnabled())
    val clipboardMonitorEnabled: StateFlow<Boolean> = _clipboardMonitorEnabled.asStateFlow()

    // --- Personalización Visual ---

    private val _customPrimaryColor = MutableStateFlow(preferencesRepository.getCustomPrimaryColor())
    val customPrimaryColor: StateFlow<Int?> = _customPrimaryColor.asStateFlow()

    private val _customBackgroundImageUri = MutableStateFlow(preferencesRepository.getCustomBackgroundImageUri())
    val customBackgroundImageUri: StateFlow<String?> = _customBackgroundImageUri.asStateFlow()

    private val _playerBackgroundType = MutableStateFlow(preferencesRepository.getPlayerBackgroundType())
    val playerBackgroundType: StateFlow<String> = _playerBackgroundType.asStateFlow()

    private val _playerBackgroundColor = MutableStateFlow(preferencesRepository.getPlayerBackgroundColor())
    val playerBackgroundColor: StateFlow<Int?> = _playerBackgroundColor.asStateFlow()

    private val _playerAlbumArtScale = MutableStateFlow(preferencesRepository.getPlayerAlbumArtScale())
    val playerAlbumArtScale: StateFlow<Float> = _playerAlbumArtScale.asStateFlow()

    private val _isDarkThemeOverride = MutableStateFlow<Boolean?>(
        when (_themeMode.value) {
            "dark" -> true
            "light" -> false
            else -> null
        }
    )
    val isDarkThemeOverride: StateFlow<Boolean?> = _isDarkThemeOverride.asStateFlow()

    val isDarkTheme: StateFlow<Boolean> = MutableStateFlow(_isDarkThemeOverride.value ?: false)

    private val _isAmoledMode = MutableStateFlow(preferencesRepository.getAmoledMode())
    val isAmoledMode: StateFlow<Boolean> = _isAmoledMode.asStateFlow()

    private val _filterShortAudio = MutableStateFlow(preferencesRepository.getFilterShortAudio())
    val filterShortAudio: StateFlow<Boolean> = _filterShortAudio.asStateFlow()

    private val _onlineMode = MutableStateFlow(preferencesRepository.getOnlineMode())
    val onlineMode: StateFlow<Boolean> = _onlineMode.asStateFlow()

    private val _applyBackgroundToPlayer = MutableStateFlow(preferencesRepository.getApplyBackgroundToPlayer())
    val applyBackgroundToPlayer: StateFlow<Boolean> = _applyBackgroundToPlayer.asStateFlow()

    private val _isCanvasEnabled = MutableStateFlow(preferencesRepository.getIsCanvasEnabled())
    val isCanvasEnabled: StateFlow<Boolean> = _isCanvasEnabled.asStateFlow()

    // --- Eventos one-shot de eliminación de duplicados ---
    private val _duplicateDeletionEvents = Channel<DuplicateDeletionResult>(Channel.BUFFERED)
    val duplicateDeletionEvents = _duplicateDeletionEvents.receiveAsFlow()

    // --- Sistema de Actualizaciones ---

    sealed class UpdateStatus {
        object Idle : UpdateStatus()
        object Checking : UpdateStatus()
        data class UpdateAvailable(val release: ReleaseInfo) : UpdateStatus()
        data class Downloading(val progress: Float) : UpdateStatus()
        data class ReadyToInstall(val apkFile: File) : UpdateStatus()
        object UpToDate : UpdateStatus()
        data class Error(val message: String) : UpdateStatus()
    }

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    var latestRelease: ReleaseInfo? = null
        private set

    fun checkForUpdates() {
        viewModelScope.launch {
            _updateStatus.value = UpdateStatus.Checking
            val result = updateRepository.getLatestRelease()

            result.onSuccess { release ->
                latestRelease = release
                if (isNewerVersion(CURRENT_VERSION, release.versionName)) {
                    _updateStatus.value = UpdateStatus.UpdateAvailable(release)
                } else {
                    _updateStatus.value = UpdateStatus.UpToDate
                }
            }.onFailure { error ->
                _updateStatus.value = UpdateStatus.Error(error.message ?: "Error desconocido")
            }
        }
    }

    fun downloadAndInstallApk(downloadUrl: String) {
        val cacheDir = appCacheDir ?: run {
            _updateStatus.value = UpdateStatus.Error("Directorio de caché no disponible")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _updateStatus.value = UpdateStatus.Downloading(0f)
                val request = Request.Builder().url(downloadUrl).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _updateStatus.value = UpdateStatus.Error("Error al descargar APK (${response.code})")
                        return@launch
                    }
                    val body = response.body ?: run {
                        _updateStatus.value = UpdateStatus.Error("Respuesta vacía del servidor")
                        return@launch
                    }
                    val totalBytes = body.contentLength()
                    val apkFile = File(cacheDir, "omnivault_update.apk")
                    if (apkFile.exists()) apkFile.delete()

                    body.byteStream().use { input ->
                        FileOutputStream(apkFile).use { output ->
                            val buffer = ByteArray(65_536)
                            var bytesRead: Int
                            var downloaded = 0L
                            var lastReportedPct = -1
                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloaded += bytesRead
                                if (totalBytes > 0) {
                                    val progress = (downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                    val pct = (progress * 100).toInt()
                                    if (pct != lastReportedPct) {
                                        lastReportedPct = pct
                                        _updateStatus.value = UpdateStatus.Downloading(progress)
                                    }
                                } else {
                                    val approxPct = ((downloaded / 1_048_576L).toInt() * 3).coerceIn(5, 95)
                                    if (approxPct != lastReportedPct) {
                                        lastReportedPct = approxPct
                                        _updateStatus.value = UpdateStatus.Downloading(approxPct / 100f)
                                    }
                                }
                            }
                            output.flush()
                        }
                    }

                    if (!apkFile.exists() || apkFile.length() == 0L) {
                        _updateStatus.value = UpdateStatus.Error("El archivo APK descargado está vacío")
                        return@launch
                    }
                    _updateStatus.value = UpdateStatus.ReadyToInstall(apkFile)
                }
            } catch (e: Exception) {
                _updateStatus.value = UpdateStatus.Error(e.localizedMessage ?: "Error al descargar la actualización")
            }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        com.omnivault.media.utils.UpdateManager.installApk(context, apkFile)
    }

    private fun isNewerVersion(current: String, latest: String): Boolean =
        com.omnivault.media.utils.VersionUtils.isRemoteNewer(latest, current)

    // --- Métodos de Setter con Persistencia ---

    fun setThemeMode(mode: String) {
        _themeMode.value = mode
        _isDarkThemeOverride.value = when (mode) {
            "dark" -> true
            "light" -> false
            else -> null
        }
        preferencesRepository.setThemeMode(mode)
    }

    fun setDynamicColors(enabled: Boolean) {
        _useDynamicColors.value = enabled
        preferencesRepository.setDynamicColors(enabled)
    }

    fun setFilterShortAudio(enabled: Boolean) {
        _filterShortAudio.value = enabled
        preferencesRepository.setFilterShortAudio(enabled)
    }

    fun setSleepTimer(minutes: Int) {
        _sleepTimerMinutes.value = minutes
        preferencesRepository.setSleepTimerMinutes(minutes)
    }

    fun setCustomPrimaryColor(color: Int?) {
        _customPrimaryColor.value = color
        preferencesRepository.setCustomPrimaryColor(color)
    }

    fun setCustomBackgroundImage(uri: String?) {
        _customBackgroundImageUri.value = uri
        preferencesRepository.setCustomBackgroundImageUri(uri)
    }

    fun setPlayerBackgroundType(type: String) {
        _playerBackgroundType.value = type
        preferencesRepository.setPlayerBackgroundType(type)
    }

    fun setPlayerBackgroundColor(color: Int) {
        _playerBackgroundColor.value = color
        preferencesRepository.setPlayerBackgroundColor(color)
    }

    fun setPlayerAlbumArtScale(scale: Float) {
        _playerAlbumArtScale.value = scale
        preferencesRepository.setPlayerAlbumArtScale(scale)
    }

    fun copyImageToInternalStorage(context: Context, uri: Uri): String? {
        return try {
            context.filesDir.listFiles { _, name -> name.startsWith("custom_bg_") && name.endsWith(".jpg") }
                ?.forEach { it.delete() }
            val inputStream = context.contentResolver.openInputStream(uri)
            val fileName = "custom_bg_${System.currentTimeMillis()}.jpg"
            val file = File(context.filesDir, fileName)
            FileOutputStream(file).use { output ->
                inputStream?.use { input ->
                    input.copyTo(output)
                }
            }
            file.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun setAmoledMode(enabled: Boolean) {
        _isAmoledMode.value = enabled
        preferencesRepository.setAmoledMode(enabled)
    }

    fun setOnlineMode(enabled: Boolean) {
        _onlineMode.value = enabled
        preferencesRepository.setOnlineMode(enabled)
    }

    fun setApplyBackgroundToPlayer(enabled: Boolean) {
        _applyBackgroundToPlayer.value = enabled
        preferencesRepository.setApplyBackgroundToPlayer(enabled)
    }

    fun setCanvasEnabled(enabled: Boolean) {
        _isCanvasEnabled.value = enabled
        preferencesRepository.setIsCanvasEnabled(enabled)
    }

    fun setClipboardMonitorEnabled(enabled: Boolean) {
        _clipboardMonitorEnabled.value = enabled
        preferencesRepository.setClipboardMonitorEnabled(enabled)
    }

    fun findDuplicateVideos(onResult: (List<DownloadItem>) -> Unit) {
        viewModelScope.launch {
            onResult(mediaCleanupRepository.findDuplicateVideos())
        }
    }

    fun findDuplicateAudios(songs: List<Song>, onResult: (List<Pair<Song, Long>>) -> Unit) {
        viewModelScope.launch {
            onResult(mediaCleanupRepository.findDuplicateAudios(songs))
        }
    }

    fun deleteDuplicateVideos(items: List<DownloadItem>) {
        viewModelScope.launch {
            val result = mediaCleanupRepository.deleteDuplicateVideos(items)
            _duplicateDeletionEvents.send(result)
        }
    }

    fun deleteDuplicateAudios(songs: List<Song>) {
        viewModelScope.launch {
            val result = mediaCleanupRepository.deleteDuplicateAudios(songs)
            _duplicateDeletionEvents.send(result)
        }
    }

    fun getThemeModeDisplay(): String {
        return when (_themeMode.value) {
            "light" -> "Claro"
            "dark" -> "Oscuro"
            else -> "Sistema"
        }
    }

    fun setAutoResumeDownloads(enabled: Boolean) {
        _autoResumeDownloads.value = enabled
        preferencesRepository.setAutoResumeDownloads(enabled)
    }

    fun resetPersonalization() {
        _customPrimaryColor.value = null
        _customBackgroundImageUri.value = null
        _playerBackgroundType.value = "auto"
        _playerBackgroundColor.value = null
        _playerAlbumArtScale.value = 1.0f

        try {
            appFilesDir
                ?.listFiles { _, name -> name.startsWith("custom_bg_") && name.endsWith(".jpg") }
                ?.forEach { it.delete() }
        } catch (_: Exception) {}

        preferencesRepository.setCustomPrimaryColor(null)
        preferencesRepository.setCustomBackgroundImageUri(null)
        preferencesRepository.setPlayerBackgroundType("auto")
        preferencesRepository.setPlayerBackgroundColor(null)
        preferencesRepository.setPlayerAlbumArtScale(1.0f)
    }
}

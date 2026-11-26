package com.amurayada.music.di

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.amurayada.music.BuildConfig
import com.amurayada.music.data.repository.AndroidMediaCleanupRepository
import com.amurayada.music.data.repository.MediaCleanupRepository
import com.amurayada.music.data.repository.PreferencesRepository
import com.amurayada.music.data.repository.SharedPreferencesRepositoryImpl
import com.amurayada.music.data.repository.UpdateRepository
import com.amurayada.music.data.repository.UpdateRepositoryImpl
import com.amurayada.music.ui.viewmodel.DownloadViewModel
import com.amurayada.music.ui.viewmodel.PlaybackViewModel
import com.amurayada.music.ui.viewmodel.SettingsViewModel
import com.amurayada.music.ui.viewmodel.VideosViewModel
import okhttp3.OkHttpClient

/**
 * Contenedor manual de dependencias (AppContainer) con verificación en tiempo de compilación.
 */
class AppContainer(application: Application) {

    private val app = application

    val preferencesRepository: PreferencesRepository by lazy {
        SharedPreferencesRepositoryImpl(app)
    }

    val mediaCleanupRepository: MediaCleanupRepository by lazy {
        AndroidMediaCleanupRepository(app)
    }

    val updateRepository: UpdateRepository by lazy {
        UpdateRepositoryImpl(OkHttpClient())
    }

    val settingsViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(
                    preferencesRepository = preferencesRepository,
                    mediaCleanupRepository = mediaCleanupRepository,
                    updateRepository = updateRepository,
                    appCacheDir = app.cacheDir,
                    appFilesDir = app.filesDir,
                    currentVersion = BuildConfig.VERSION_NAME
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    val downloadViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(DownloadViewModel::class.java)) {
                return DownloadViewModel(app, preferencesRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    val videosViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(VideosViewModel::class.java)) {
                return VideosViewModel(app, preferencesRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }

    val playbackViewModelFactory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(PlaybackViewModel::class.java)) {
                return PlaybackViewModel(app, preferencesRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

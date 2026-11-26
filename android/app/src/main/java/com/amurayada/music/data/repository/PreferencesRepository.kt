package com.amurayada.music.data.repository

/**
 * Repositorio unificado de preferencias de usuario.
 * Desacopla UI y ViewModels de SharedPreferences manteniendo compatibilidad con las claves existentes.
 */
interface PreferencesRepository {
    // --- music_settings ---
    fun getThemeMode(): String
    fun setThemeMode(mode: String)

    fun getDynamicColors(): Boolean
    fun setDynamicColors(enabled: Boolean)

    fun getSleepTimerMinutes(): Int
    fun setSleepTimerMinutes(minutes: Int)

    fun getAutoResumeDownloads(): Boolean
    fun setAutoResumeDownloads(enabled: Boolean)

    fun getCustomPrimaryColor(): Int?
    fun setCustomPrimaryColor(color: Int?)

    fun getCustomBackgroundImageUri(): String?
    fun setCustomBackgroundImageUri(uri: String?)

    fun getPlayerBackgroundType(): String
    fun setPlayerBackgroundType(type: String)

    fun getPlayerBackgroundColor(): Int?
    fun setPlayerBackgroundColor(color: Int?)

    fun getPlayerAlbumArtScale(): Float
    fun setPlayerAlbumArtScale(scale: Float)

    fun getAmoledMode(): Boolean
    fun setAmoledMode(enabled: Boolean)

    fun getOnlineMode(): Boolean
    fun setOnlineMode(enabled: Boolean)

    fun getApplyBackgroundToPlayer(): Boolean
    fun setApplyBackgroundToPlayer(enabled: Boolean)

    fun getIsCanvasEnabled(): Boolean
    fun setIsCanvasEnabled(enabled: Boolean)

    fun getFilterShortAudio(): Boolean
    fun setFilterShortAudio(enabled: Boolean)

    fun clearMusicSettings()

    // --- omnivault_prefs ---
    fun getClipboardMonitorEnabled(): Boolean
    fun setClipboardMonitorEnabled(enabled: Boolean)

    fun getMusicSearchHistory(): List<String>
    fun saveMusicSearchQuery(query: String): List<String>

    fun getPlaylistSort(playlistId: Long, defaultName: String): String
    fun setPlaylistSort(playlistId: Long, sortName: String)

    fun getVideoGridView(): Boolean
    fun setVideoGridView(enabled: Boolean)

    fun getVideoSortOrder(defaultKey: String): String
    fun setVideoSortOrder(key: String)

    fun getHideShortVideos(): Boolean
    fun setHideShortVideos(enabled: Boolean)

    fun getHideWhatsAppVideos(): Boolean
    fun setHideWhatsAppVideos(enabled: Boolean)

    fun setDownloadLocation(mode: String, uri: String?)

    fun getSongSortOrder(defaultName: String): String
    fun setSongSortOrder(name: String)

    fun getFilterCallRecordings(): Boolean
    fun setFilterCallRecordings(enabled: Boolean)

    fun getOnlineSearchHistory(): String
    fun setOnlineSearchHistory(history: String)

    // --- playback_data ---
    fun getShuffleEnabled(): Boolean
    fun setShuffleEnabled(enabled: Boolean)

    fun getRepeatModeName(defaultName: String): String
    fun setRepeatModeName(name: String)

    fun getFavoritesJson(): String?
    fun setFavoritesJson(json: String)

    fun getHistoryJson(): String?
    fun setHistoryJson(json: String)

    fun getPlayCountsJson(): String?
    fun setPlayCountsJson(json: String)

    fun savePlaybackQueue(queueJson: String, index: Int, positionMs: Long)
    fun savePlaybackPosition(index: Int, positionMs: Long)
}

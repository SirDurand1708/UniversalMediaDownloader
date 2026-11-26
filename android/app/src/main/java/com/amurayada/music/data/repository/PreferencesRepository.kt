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
}

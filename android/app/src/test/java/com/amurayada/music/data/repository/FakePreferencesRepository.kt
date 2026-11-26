package com.amurayada.music.data.repository

class FakePreferencesRepository : PreferencesRepository {
    var storedThemeMode: String = "system"
    var storedDynamicColors: Boolean = true
    var storedSleepTimerMinutes: Int = 0
    var storedAutoResumeDownloads: Boolean = false
    var storedCustomPrimaryColor: Int? = null
    var storedCustomBgUri: String? = null
    var storedPlayerBgType: String = "auto"
    var storedPlayerBgColor: Int? = null
    var storedPlayerArtScale: Float = 1.0f
    var storedAmoledMode: Boolean = false
    var storedOnlineMode: Boolean = false
    var storedApplyBgToPlayer: Boolean = false
    var storedIsCanvasEnabled: Boolean = false
    var storedFilterShortAudio: Boolean = true
    var storedClipboardMonitorEnabled: Boolean = true
    var storedSearchHistory: List<String> = emptyList()
    val storedPlaylistSorts = mutableMapOf<Long, String>()
    var storedVideoGridView: Boolean = false
    var storedVideoSortOrder: String = "DATE_DESC"
    var storedHideShortVideos: Boolean = false
    var storedHideWhatsAppVideos: Boolean = true
    var storedDownloadPathMode: String? = null
    var storedDownloadPathUri: String? = null
    var storedSongSortOrder: String = "RECENT"
    var storedFilterCallRecordings: Boolean = false
    var storedOnlineSearchHistory: String = ""
    var storedShuffleEnabled: Boolean = false
    var storedRepeatModeName: String = "OFF"
    var storedFavoritesJson: String? = null
    var storedHistoryJson: String? = null
    var storedPlayCountsJson: String? = null
    var storedQueueJson: String? = null
    var storedQueueIndex: Int = 0
    var storedQueuePositionMs: Long = 0L

    override fun getThemeMode() = storedThemeMode
    override fun setThemeMode(mode: String) { storedThemeMode = mode }
    override fun getDynamicColors() = storedDynamicColors
    override fun setDynamicColors(enabled: Boolean) { storedDynamicColors = enabled }
    override fun getSleepTimerMinutes() = storedSleepTimerMinutes
    override fun setSleepTimerMinutes(minutes: Int) { storedSleepTimerMinutes = minutes }
    override fun getAutoResumeDownloads() = storedAutoResumeDownloads
    override fun setAutoResumeDownloads(enabled: Boolean) { storedAutoResumeDownloads = enabled }
    override fun getCustomPrimaryColor() = storedCustomPrimaryColor
    override fun setCustomPrimaryColor(color: Int?) { storedCustomPrimaryColor = color }
    override fun getCustomBackgroundImageUri() = storedCustomBgUri
    override fun setCustomBackgroundImageUri(uri: String?) { storedCustomBgUri = uri }
    override fun getPlayerBackgroundType() = storedPlayerBgType
    override fun setPlayerBackgroundType(type: String) { storedPlayerBgType = type }
    override fun getPlayerBackgroundColor() = storedPlayerBgColor
    override fun setPlayerBackgroundColor(color: Int?) { storedPlayerBgColor = color }
    override fun getPlayerAlbumArtScale() = storedPlayerArtScale
    override fun setPlayerAlbumArtScale(scale: Float) { storedPlayerArtScale = scale }
    override fun getAmoledMode() = storedAmoledMode
    override fun setAmoledMode(enabled: Boolean) { storedAmoledMode = enabled }
    override fun getOnlineMode() = storedOnlineMode
    override fun setOnlineMode(enabled: Boolean) { storedOnlineMode = enabled }
    override fun getApplyBackgroundToPlayer() = storedApplyBgToPlayer
    override fun setApplyBackgroundToPlayer(enabled: Boolean) { storedApplyBgToPlayer = enabled }
    override fun getIsCanvasEnabled() = storedIsCanvasEnabled
    override fun setIsCanvasEnabled(enabled: Boolean) { storedIsCanvasEnabled = enabled }
    override fun getFilterShortAudio() = storedFilterShortAudio
    override fun setFilterShortAudio(enabled: Boolean) { storedFilterShortAudio = enabled }
    override fun clearMusicSettings() {
        storedThemeMode = "system"
        storedDynamicColors = true
        storedAmoledMode = false
        storedCustomPrimaryColor = null
        storedCustomBgUri = null
    }
    override fun getClipboardMonitorEnabled() = storedClipboardMonitorEnabled
    override fun setClipboardMonitorEnabled(enabled: Boolean) { storedClipboardMonitorEnabled = enabled }
    override fun getMusicSearchHistory() = storedSearchHistory
    override fun saveMusicSearchQuery(query: String): List<String> {
        val clean = query.trim()
        if (clean.length < 2) return storedSearchHistory
        storedSearchHistory = (listOf(clean) + storedSearchHistory.filterNot { it.equals(clean, ignoreCase = true) }).take(5)
        return storedSearchHistory
    }
    override fun getPlaylistSort(playlistId: Long, defaultName: String) = storedPlaylistSorts[playlistId] ?: defaultName
    override fun setPlaylistSort(playlistId: Long, sortName: String) { storedPlaylistSorts[playlistId] = sortName }
    override fun getVideoGridView() = storedVideoGridView
    override fun setVideoGridView(enabled: Boolean) { storedVideoGridView = enabled }
    override fun getVideoSortOrder(defaultKey: String) = storedVideoSortOrder
    override fun setVideoSortOrder(key: String) { storedVideoSortOrder = key }
    override fun getHideShortVideos() = storedHideShortVideos
    override fun setHideShortVideos(enabled: Boolean) { storedHideShortVideos = enabled }
    override fun getHideWhatsAppVideos() = storedHideWhatsAppVideos
    override fun setHideWhatsAppVideos(enabled: Boolean) { storedHideWhatsAppVideos = enabled }
    override fun setDownloadLocation(mode: String, uri: String?) {
        storedDownloadPathMode = mode
        storedDownloadPathUri = uri
    }
    override fun getSongSortOrder(defaultName: String) = storedSongSortOrder
    override fun setSongSortOrder(name: String) { storedSongSortOrder = name }
    override fun getFilterCallRecordings() = storedFilterCallRecordings
    override fun setFilterCallRecordings(enabled: Boolean) { storedFilterCallRecordings = enabled }
    override fun getOnlineSearchHistory() = storedOnlineSearchHistory
    override fun setOnlineSearchHistory(history: String) { storedOnlineSearchHistory = history }
    override fun getShuffleEnabled() = storedShuffleEnabled
    override fun setShuffleEnabled(enabled: Boolean) { storedShuffleEnabled = enabled }
    override fun getRepeatModeName(defaultName: String) = storedRepeatModeName
    override fun setRepeatModeName(name: String) { storedRepeatModeName = name }
    override fun getFavoritesJson() = storedFavoritesJson
    override fun setFavoritesJson(json: String) { storedFavoritesJson = json }
    override fun getHistoryJson() = storedHistoryJson
    override fun setHistoryJson(json: String) { storedHistoryJson = json }
    override fun getPlayCountsJson() = storedPlayCountsJson
    override fun setPlayCountsJson(json: String) { storedPlayCountsJson = json }
    override fun savePlaybackQueue(queueJson: String, index: Int, positionMs: Long) {
        storedQueueJson = queueJson
        storedQueueIndex = index
        storedQueuePositionMs = positionMs
    }
    override fun savePlaybackPosition(index: Int, positionMs: Long) {
        storedQueueIndex = index
        storedQueuePositionMs = positionMs
    }
}

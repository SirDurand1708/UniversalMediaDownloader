package com.amurayada.music.data.repository

import android.content.Context

class SharedPreferencesRepositoryImpl(context: Context) : PreferencesRepository {

    private val appContext = context.applicationContext
    private val musicPrefs = appContext.getSharedPreferences("music_settings", Context.MODE_PRIVATE)
    private val omniPrefs = appContext.getSharedPreferences("omnivault_prefs", Context.MODE_PRIVATE)

    override fun getThemeMode(): String =
        musicPrefs.getString("theme_mode", "system") ?: "system"

    override fun setThemeMode(mode: String) {
        musicPrefs.edit().putString("theme_mode", mode).apply()
    }

    override fun getDynamicColors(): Boolean =
        musicPrefs.getBoolean("dynamic_colors", true)

    override fun setDynamicColors(enabled: Boolean) {
        musicPrefs.edit().putBoolean("dynamic_colors", enabled).apply()
    }

    override fun getSleepTimerMinutes(): Int =
        musicPrefs.getInt("sleep_timer", 0)

    override fun setSleepTimerMinutes(minutes: Int) {
        musicPrefs.edit().putInt("sleep_timer", minutes).apply()
    }

    override fun getAutoResumeDownloads(): Boolean =
        musicPrefs.getBoolean("auto_resume_downloads", false)

    override fun setAutoResumeDownloads(enabled: Boolean) {
        musicPrefs.edit().putBoolean("auto_resume_downloads", enabled).apply()
    }

    override fun getCustomPrimaryColor(): Int? =
        if (musicPrefs.contains("custom_primary_color")) musicPrefs.getInt("custom_primary_color", -1) else null

    override fun setCustomPrimaryColor(color: Int?) {
        musicPrefs.edit().apply {
            if (color != null) putInt("custom_primary_color", color) else remove("custom_primary_color")
        }.apply()
    }

    override fun getCustomBackgroundImageUri(): String? =
        musicPrefs.getString("custom_bg_uri", null)

    override fun setCustomBackgroundImageUri(uri: String?) {
        musicPrefs.edit().apply {
            if (uri != null) putString("custom_bg_uri", uri) else remove("custom_bg_uri")
        }.apply()
    }

    override fun getPlayerBackgroundType(): String =
        musicPrefs.getString("player_bg_type", "auto") ?: "auto"

    override fun setPlayerBackgroundType(type: String) {
        musicPrefs.edit().putString("player_bg_type", type).apply()
    }

    override fun getPlayerBackgroundColor(): Int? =
        if (musicPrefs.contains("player_bg_color")) musicPrefs.getInt("player_bg_color", -1) else null

    override fun setPlayerBackgroundColor(color: Int?) {
        musicPrefs.edit().apply {
            if (color != null) putInt("player_bg_color", color) else remove("player_bg_color")
        }.apply()
    }

    override fun getPlayerAlbumArtScale(): Float =
        musicPrefs.getFloat("player_art_scale", 1.0f)

    override fun setPlayerAlbumArtScale(scale: Float) {
        musicPrefs.edit().putFloat("player_art_scale", scale).apply()
    }

    override fun getAmoledMode(): Boolean =
        musicPrefs.getBoolean("amoled_mode", false)

    override fun setAmoledMode(enabled: Boolean) {
        musicPrefs.edit().putBoolean("amoled_mode", enabled).apply()
    }

    override fun getOnlineMode(): Boolean =
        musicPrefs.getBoolean("online_mode", false)

    override fun setOnlineMode(enabled: Boolean) {
        musicPrefs.edit().putBoolean("online_mode", enabled).apply()
    }

    override fun getApplyBackgroundToPlayer(): Boolean =
        musicPrefs.getBoolean("apply_bg_to_player", false)

    override fun setApplyBackgroundToPlayer(enabled: Boolean) {
        musicPrefs.edit().putBoolean("apply_bg_to_player", enabled).apply()
    }

    override fun getIsCanvasEnabled(): Boolean =
        musicPrefs.getBoolean("is_canvas_enabled", false)

    override fun setIsCanvasEnabled(enabled: Boolean) {
        musicPrefs.edit().putBoolean("is_canvas_enabled", enabled).apply()
    }

    override fun getFilterShortAudio(): Boolean {
        return if (omniPrefs.contains("filter_short_audio")) {
            omniPrefs.getBoolean("filter_short_audio", false)
        } else {
            musicPrefs.getBoolean("filter_short_audio", true)
        }
    }

    override fun setFilterShortAudio(enabled: Boolean) {
        musicPrefs.edit().putBoolean("filter_short_audio", enabled).apply()
        omniPrefs.edit().putBoolean("filter_short_audio", enabled).apply()
    }

    override fun clearMusicSettings() {
        musicPrefs.edit().apply {
            remove("theme_mode")
            remove("dynamic_colors")
            remove("amoled_mode")
            remove("custom_primary_color")
            remove("custom_bg_uri")
            remove("player_bg_type")
            remove("player_bg_color")
            remove("player_art_scale")
            remove("apply_bg_to_player")
            remove("is_canvas_enabled")
            remove("online_mode")
            remove("sleep_timer")
            remove("filter_short_audio")
            remove("auto_resume_downloads")
        }.apply()
    }

    override fun getClipboardMonitorEnabled(): Boolean =
        omniPrefs.getBoolean("clipboard_monitor_enabled", true)

    override fun setClipboardMonitorEnabled(enabled: Boolean) {
        omniPrefs.edit().putBoolean("clipboard_monitor_enabled", enabled).apply()
    }

    override fun getMusicSearchHistory(): List<String> =
        omniPrefs.getString("music_search_history", "")
            ?.split("|")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    override fun saveMusicSearchQuery(query: String): List<String> {
        val clean = query.trim()
        val current = getMusicSearchHistory()
        if (clean.length < 2) return current
        val updated = (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) }).take(5)
        omniPrefs.edit().putString("music_search_history", updated.joinToString("|")).apply()
        return updated
    }

    override fun getPlaylistSort(playlistId: Long, defaultName: String): String =
        omniPrefs.getString("playlist_sort_$playlistId", defaultName) ?: defaultName

    override fun setPlaylistSort(playlistId: Long, sortName: String) {
        omniPrefs.edit().putString("playlist_sort_$playlistId", sortName).apply()
    }

    override fun getVideoGridView(): Boolean =
        omniPrefs.getBoolean("video_grid_view", false)

    override fun setVideoGridView(enabled: Boolean) {
        omniPrefs.edit().putBoolean("video_grid_view", enabled).apply()
    }
}

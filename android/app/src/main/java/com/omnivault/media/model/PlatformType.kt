package com.omnivault.media.model

enum class PlatformType(val displayName: String, val badgeColorHex: String) {
    YOUTUBE("YouTube", "#EF4444"),
    TIKTOK("TikTok", "#06B6D4"),
    INSTAGRAM("Instagram", "#E1306C"),
    TWITTER("X / Twitter", "#38BDF8"),
    FACEBOOK("Facebook", "#3B82F6"),
    SOUNDCLOUD("SoundCloud", "#F97316"),
    SPOTIFY("Spotify Track", "#10B981"),
    GENERIC("Enlace Multimedia", "#94A3B8");

    companion object {
        fun fromUrl(url: String): PlatformType {
            val lower = url.lowercase()
            return when {
                lower.contains("youtube.com") || lower.contains("youtu.be") -> YOUTUBE
                lower.contains("tiktok.com") -> TIKTOK
                lower.contains("instagram.com") -> INSTAGRAM
                lower.contains("twitter.com") || lower.contains("x.com") -> TWITTER
                lower.contains("facebook.com") || lower.contains("fb.watch") -> FACEBOOK
                lower.contains("soundcloud.com") -> SOUNDCLOUD
                lower.contains("spotify.com") -> SPOTIFY
                else -> GENERIC
            }
        }
    }
}

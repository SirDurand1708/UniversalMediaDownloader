package com.omnivault.media.model

enum class MediaFormat(
    val extension: String,
    val isAudio: Boolean,
    val label: String,
    val mimeType: String
) {
    MP3_320("mp3", true, "Audio MP3 (320 kbps)", "audio/mpeg"),
    M4A("m4a", true, "Audio M4A (Original)", "audio/mp4"),
    MP4_1080("mp4", false, "Video MP4 (1080p FHD)", "video/mp4"),
    MP4_720("mp4", false, "Video MP4 (720p HD)", "video/mp4"),
    MP4_480("mp4", false, "Video MP4 (480p SD)", "video/mp4");

    companion object {
        fun fromKey(key: String): MediaFormat {
            return when (key.uppercase()) {
                "MP3", "MP3_320" -> MP3_320
                "M4A" -> M4A
                "MP4_1080", "1080P", "FULL_HD" -> MP4_1080
                "MP4_720", "720P", "HD" -> MP4_720
                "MP4_480", "480P" -> MP4_480
                else -> MP3_320
            }
        }
    }
}

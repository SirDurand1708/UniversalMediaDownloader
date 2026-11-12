package com.amurayada.music.data.model

enum class SongSort(val label: String) {
    RECENT("Más reciente (Fecha)"),
    NAME_ASC("Título: A a Z"),
    NAME_DESC("Título: Z a A"),
    ARTIST_ASC("Artista: A a Z"),
    DURATION_DESC("Mayor duración");

    companion object {
        fun fromKey(key: String?): SongSort {
            return try {
                if (key != null) valueOf(key) else RECENT
            } catch (_: Exception) {
                RECENT
            }
        }
    }
}

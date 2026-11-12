package com.omnivault.media.model

enum class SortOption(val displayName: String, val isAscending: Boolean) {
    DATE_DESC("Más recientes primero", false),
    DATE_ASC("Más antiguos primero", true),
    MOST_PLAYED("Más reproducidas", false),
    NAME_ASC("Título: A a Z", true),
    NAME_DESC("Título: Z a A", false),
    SIZE_DESC("Mayor tamaño", false),
    SIZE_ASC("Menor tamaño", true);

    companion object {
        fun fromKey(key: String): SortOption {
            return try {
                valueOf(key)
            } catch (_: Exception) {
                DATE_DESC
            }
        }
    }
}

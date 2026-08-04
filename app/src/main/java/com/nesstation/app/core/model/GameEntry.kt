package com.nesstation.app.core.model

import androidx.compose.ui.graphics.Color

/**
 * Game platform type.
 * NES = NES/Famicom games (FCEUmm core)
 * JAVA = J2ME/Java ME games (J2ME-Loader engine)
 */
enum class GamePlatform(val displayName: String) {
    NES("NES"),
    JAVA("Java");

    companion object {
        fun fromString(value: String?): GamePlatform =
            entries.firstOrNull { it.name == value } ?: NES
    }
}

data class GameEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val accent: Color = Color(0xFFE74C3C),
    val romPath: String? = null,
    val coverPath: String? = null,
    val lastPlayedAt: Long = 0L,
    val playTimeMs: Long = 0L,
    val isFavorite: Boolean = false,
    val platform: GamePlatform = GamePlatform.NES,
    val customIconPath: String? = null
)

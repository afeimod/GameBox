package com.nesstation.app.core.model

import androidx.compose.ui.graphics.Color

/**
 * Game platform type.
 * NES  = NES/Famicom games (FCEUmm core)
 * SFC  = SNES/Super Famicom games (snes9x core)
 * GB   = Game Boy / Game Boy Color games (mGBA core)
 * GBA  = Game Boy Advance games (mGBA core)
 * JAVA = J2ME/Java ME games (J2ME-Loader engine)
 */
enum class GamePlatform(val displayName: String) {
    NES("NES"),
    SFC("SFC"),
    GB("GB/GBC"),
    GBA("GBA"),
    JAVA("Java");

    companion object {
        fun fromString(value: String?): GamePlatform = when (value) {
            "GBC" -> GB  // Migration: GBC merged into GB
            else -> entries.firstOrNull { it.name == value } ?: NES
        }

        /**
         * Determine platform from a ROM file extension.
         * GB and GBC are merged into a single GB category.
         */
        fun fromExtension(ext: String): GamePlatform? {
            return when (ext.lowercase()) {
                "nes", "unf", "unif", "fds", "nez", "unh" -> NES
                "smc", "sfc", "swc", "fig", "bs" -> SFC
                "gb", "sgb", "gbc" -> GB
                "gba" -> GBA
                "jar", "jad" -> JAVA
                else -> null
            }
        }
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

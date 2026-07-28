package com.nesstation.app.core.model

import androidx.compose.ui.graphics.Color

data class GameEntry(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val accent: Color = Color(0xFFE74C3C),
    val romPath: String? = null,
    val coverPath: String? = null,
    val lastPlayedAt: Long = 0L,
    val playTimeMs: Long = 0L,
    val isFavorite: Boolean = false
)

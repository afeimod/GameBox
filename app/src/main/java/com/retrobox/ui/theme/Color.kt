package com.retrobox.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * RetroBox color palette.
 *
 * The visual identity is a cyberpunk "arcade at midnight" look: deep near-black
 * surfaces lit by saturated neon purple, cyan and pink. Bright neons are used as
 * the accent colors, while high-contrast near-white text rides on top of the
 * dark surfaces for readability.
 */

// ---- Neon core palette ----
val NeonPurple = Color(0xFFB14AED)
val NeonViolet = Color(0xFF7B2FBE)
val NeonCyan = Color(0xFF00F0FF)
val NeonPink = Color(0xFFFF3D81)
val NeonMagenta = Color(0xFFFF0080)
val NeonBlue = Color(0xFF3B82F6)
val NeonGreen = Color(0xFF39FF14)
val NeonYellow = Color(0xFFFFE600)

// ---- Dark surfaces (the "arcade cabinet" backdrop) ----
val CyberBlack = Color(0xFF0B0B14)
val CyberBackground = Color(0xFF0B0B14)
val CyberSurface = Color(0xFF12101F)
val CyberSurfaceVariant = Color(0xFF1B1830)
val CyberSurfaceHigh = Color(0xFF221F38)
val CyberOutline = Color(0xFF2A2740)
val CyberOutlineHigh = Color(0xFF4A4561)
val CyberOnSurface = Color(0xFFECE7F6)
val CyberOnSurfaceVariant = Color(0xFFCAC4D0)

// ---- Soft glow tones (for gradients & disabled states) ----
val GlowPurple = Color(0xFFC77DFF)
val GlowCyan = Color(0xFF80FBFF)
val GlowPink = Color(0xFFFF6BA0)
val DimPurple = Color(0xFF3A1A5E)
val DimCyan = Color(0xFF073B43)

// ---- Semantic ----
val ErrorRed = Color(0xFFFF5252)
val SuccessGreen = Color(0xFF39FF14)
val WarningAmber = Color(0xFFFFB300)

// ---- Light scheme tokens (kept low priority; app is dark-first) ----
val LightBackground = Color(0xFFF6F3FC)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1B1230)
val LightOnSurfaceVariant = Color(0xFF4B4561)

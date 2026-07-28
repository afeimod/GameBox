package com.retrobox.ui.components

import androidx.compose.ui.graphics.Color

/**
 * 手柄主题预设枚举。
 *
 * 每个预设对应一套完整的视觉风格，可通过 [GamepadTheme.fromPreset] 获取。
 */
enum class GamepadPreset {
    /** 霓虹赛博：深色背景 + 霓虹紫/青/粉高饱和发光。 */
    NEON_CYBER,

    /** 复古游戏：偏暖的像素风配色，低饱和度。 */
    RETRO_GAMING,

    /** 极简暗黑：极低发光、接近纯黑的低调风格。 */
    MINIMAL_DARK,

    /** 发光紫色：以霓虹紫为主色调的单色高发光风格。 */
    GLOW_PURPLE
}

/**
 * 手柄主题数据类。
 *
 * 定义手柄各部分的颜色、背景透明度以及发光强度等视觉参数。
 * 通过 [fromPreset] 可快速获取预设主题，也可自定义每个字段。
 *
 * @property primaryColor   主色（用于 DPad 中心、Start/Select 等通用元素）。
 * @property accentColor    辅助强调色（用于发光光晕、描边高光）。
 * @property buttonAColor   A 按钮颜色。
 * @property buttonBColor   B 按钮颜色。
 * @property buttonXColor   X 按钮颜色。
 * @property buttonYColor   Y 按钮颜色。
 * @property shoulderColor  肩键主色。
 * @property backgroundColor 手柄整体背景色（通常带 alpha）。
 * @property backgroundAlpha 整体背景透明度，0f~1f。
 * @property glowIntensity   发光强度系数，0f~1f，影响所有发光半径与外发光透明度。
 * @property borderColor     按钮描边颜色。
 * @property labelColor      按钮文字颜色。
 * @property shadowColor     阴影颜色。
 */
data class GamepadTheme(
    val primaryColor: Color,
    val accentColor: Color,
    val buttonAColor: Color,
    val buttonBColor: Color,
    val buttonXColor: Color,
    val buttonYColor: Color,
    val shoulderColor: Color,
    val backgroundColor: Color,
    val backgroundAlpha: Float,
    val glowIntensity: Float,
    val borderColor: Color,
    val labelColor: Color,
    val shadowColor: Color
) {
    companion object {
        /** 霓虹紫 #B388FF */
        val NeonPurple = Color(0xFFB388FF)

        /** 霓虹青 #18FFFF */
        val NeonCyan = Color(0xFF18FFFF)

        /** 霓虹粉 #FF4081 */
        val NeonPink = Color(0xFFFF4081)

        /** 霓虹蓝（X 按钮用） */
        val NeonBlue = Color(0xFF448AFF)

        /** 深空黑背景 */
        val DarkBackground = Color(0xFF0A0E1A)

        /**
         * 根据预设返回对应的主题配置。
         */
        fun fromPreset(preset: GamepadPreset): GamepadTheme = when (preset) {
            GamepadPreset.NEON_CYBER -> GamepadTheme(
                primaryColor = NeonPurple,
                accentColor = NeonCyan,
                buttonAColor = NeonPink,
                buttonBColor = NeonCyan,
                buttonXColor = NeonBlue,
                buttonYColor = NeonPurple,
                shoulderColor = NeonCyan,
                backgroundColor = DarkBackground,
                backgroundAlpha = 0.55f,
                glowIntensity = 1.0f,
                borderColor = NeonPurple,
                labelColor = Color.White,
                shadowColor = Color.Black
            )

            GamepadPreset.RETRO_GAMING -> GamepadTheme(
                primaryColor = Color(0xFFE0A040),
                accentColor = Color(0xFFE04050),
                buttonAColor = Color(0xFFE04050),
                buttonBColor = Color(0xFF40C0E0),
                buttonXColor = Color(0xFF4060C0),
                buttonYColor = Color(0xFFE0A040),
                shoulderColor = Color(0xFF806040),
                backgroundColor = Color(0xFF1A1410),
                backgroundAlpha = 0.7f,
                glowIntensity = 0.35f,
                borderColor = Color(0xFF604020),
                labelColor = Color(0xFFFFF0D0),
                shadowColor = Color.Black
            )

            GamepadPreset.MINIMAL_DARK -> GamepadTheme(
                primaryColor = Color(0xFFAAAAAA),
                accentColor = Color(0xFF888888),
                buttonAColor = Color(0xFFCCCCCC),
                buttonBColor = Color(0xFF999999),
                buttonXColor = Color(0xFF777777),
                buttonYColor = Color(0xFFAAAAAA),
                shoulderColor = Color(0xFF666666),
                backgroundColor = Color(0xFF050505),
                backgroundAlpha = 0.4f,
                glowIntensity = 0.15f,
                borderColor = Color(0xFF444444),
                labelColor = Color(0xFFDDDDDD),
                shadowColor = Color.Black
            )

            GamepadPreset.GLOW_PURPLE -> GamepadTheme(
                primaryColor = NeonPurple,
                accentColor = Color(0xFFD1A3FF),
                buttonAColor = Color(0xFFC870FF),
                buttonBColor = Color(0xFF9A4DFF),
                buttonXColor = Color(0xFF7B2FE0),
                buttonYColor = NeonPurple,
                shoulderColor = Color(0xFFA06BFF),
                backgroundColor = Color(0xFF0D0820),
                backgroundAlpha = 0.6f,
                glowIntensity = 0.85f,
                borderColor = NeonPurple,
                labelColor = Color.White,
                shadowColor = Color.Black
            )
        }

        /** 默认主题：霓虹赛博风格。 */
        val Default: GamepadTheme = fromPreset(GamepadPreset.NEON_CYBER)
    }

    /**
     * 根据 [glowIntensity] 计算实际发光半径系数（乘以按钮尺寸得到像素半径）。
     */
    fun glowRadiusFactor(pressed: Boolean): Float {
        val base = 0.18f + glowIntensity * 0.22f
        return if (pressed) base * 1.6f else base
    }

    /**
     * 计算发光层的透明度。
     */
    fun glowAlpha(pressed: Boolean): Float {
        val base = 0.25f + glowIntensity * 0.35f
        return if (pressed) (base + 0.3f).coerceAtMost(0.95f) else base
    }
}

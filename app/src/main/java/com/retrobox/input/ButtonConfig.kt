package com.retrobox.input

import org.json.JSONArray
import org.json.JSONObject

/**
 * 手柄布局模式。
 *
 * @property STANDARD 标准——完整布局，间距宽松，适合大屏/横屏。
 * @property COMPACT  紧凑——缩小尺寸与间距，适合小屏或需要更多游戏可视区域。
 * @property CUSTOM   自定义——完全由 [GamepadConfig] 中的每个按钮参数决定。
 */
enum class ButtonLayout {
    STANDARD,
    COMPACT,
    CUSTOM
}

/**
 * 支持的模拟器平台，每个平台有不同的物理按键映射。
 */
enum class EmulatorPlatform(val displayName: String) {
    FC("Family Computer (FC/NES)"),
    SFC("Super Famicom (SFC/SNES)"),
    MD("Mega Drive (MD/Genesis)"),
    ARCADE("Arcade");

    companion object {
        /** 默认平台。 */
        val DEFAULT = FC
    }
}

/**
 * 手柄上所有逻辑按键的唯一标识。
 *
 * 该标识与平台无关，具体的按键码由 [KeyMapping] 提供。
 */
enum class GamepadButtonId(val label: String) {
    // 方向键
    DPAD_UP("U"),
    DPAD_DOWN("D"),
    DPAD_LEFT("L"),
    DPAD_RIGHT("R"),

    // 动作键
    A("A"),
    B("B"),
    X("X"),
    Y("Y"),

    // 肩键
    L1("L1"),
    R1("R1"),
    L2("L2"),
    R2("R2"),

    // 系统键
    START("Start"),
    SELECT("Select");

    companion object {
        /** 方向键集合。 */
        val DPAD_BUTTONS = listOf(DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT)

        /** 动作键集合。 */
        val ACTION_BUTTONS = listOf(A, B, X, Y)

        /** 肩键集合。 */
        val SHOULDER_BUTTONS = listOf(L1, R1, L2, R2)

        /** 系统键集合。 */
        val SYSTEM_BUTTONS = listOf(START, SELECT)
    }
}

/**
 * 单个按键的自定义配置（仅在 [ButtonLayout.CUSTOM] 下生效）。
 *
 * @property buttonId   按键标识。
 * @property sizeDp     按钮尺寸（dp）。
 * @property offsetXDp  相对于标准位置的 X 偏移（dp）。
 * @property offsetYDp  相对于标准位置的 Y 偏移（dp）。
 * @property opacity    该按钮独立透明度，0f~1f，1f 表示跟随全局透明度。
 */
data class ButtonConfig(
    val buttonId: GamepadButtonId,
    val sizeDp: Float = 0f,
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 0f,
    val opacity: Float = 1f
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("buttonId", buttonId.name)
        put("sizeDp", sizeDp)
        put("offsetXDp", offsetXDp)
        put("offsetYDp", offsetYDp)
        put("opacity", opacity)
    }

    companion object {
        fun fromJson(json: JSONObject): ButtonConfig = ButtonConfig(
            buttonId = GamepadButtonId.valueOf(json.getString("buttonId")),
            sizeDp = json.optDouble("sizeDp", 0.0).toFloat(),
            offsetXDp = json.optDouble("offsetXDp", 0.0).toFloat(),
            offsetYDp = json.optDouble("offsetYDp", 0.0).toFloat(),
            opacity = json.optDouble("opacity", 1.0).toFloat()
        )
    }
}

/**
 * 按键映射表，将逻辑按键 [GamepadButtonId] 映射为指定平台的物理/模拟器按键码。
 *
 * 按键码采用 Android KeyEvent 约定（正值）或自定义负值（由模拟器核心解释）。
 */
data class KeyMapping(
    val platform: EmulatorPlatform,
    val mapping: Map<GamepadButtonId, Int>
) {
    /** 查询某个逻辑按键对应的按键码，未映射时返回 0。 */
    fun keyCodeFor(buttonId: GamepadButtonId): Int = mapping[buttonId] ?: 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("platform", platform.name)
        val arr = JSONArray()
        mapping.forEach { (id, code) ->
            arr.put(JSONObject().apply {
                put("button", id.name)
                put("code", code)
            })
        }
        put("mapping", arr)
    }

    companion object {
        /**
         * 获取指定平台的默认按键映射。
         *
         * 这里使用一套合理的默认按键码（参考 Android KeyEvent 常量），
         * 实际项目可替换为对应模拟器核心所需的码值。
         */
        fun defaultFor(platform: EmulatorPlatform): KeyMapping {
            val map = when (platform) {
                EmulatorPlatform.FC -> mapOf(
                    GamepadButtonId.DPAD_UP to 19,      // KEYCODE_DPAD_UP
                    GamepadButtonId.DPAD_DOWN to 20,    // KEYCODE_DPAD_DOWN
                    GamepadButtonId.DPAD_LEFT to 21,    // KEYCODE_DPAD_LEFT
                    GamepadButtonId.DPAD_RIGHT to 22,   // KEYCODE_DPAD_RIGHT
                    GamepadButtonId.A to 96,            // KEYCODE_BUTTON_A
                    GamepadButtonId.B to 97,            // KEYCODE_BUTTON_B
                    GamepadButtonId.START to 108,       // KEYCODE_BUTTON_START
                    GamepadButtonId.SELECT to 109       // KEYCODE_BUTTON_SELECT
                )

                EmulatorPlatform.SFC -> mapOf(
                    GamepadButtonId.DPAD_UP to 19,
                    GamepadButtonId.DPAD_DOWN to 20,
                    GamepadButtonId.DPAD_LEFT to 21,
                    GamepadButtonId.DPAD_RIGHT to 22,
                    GamepadButtonId.A to 96,            // A
                    GamepadButtonId.B to 97,            // B
                    GamepadButtonId.X to 99,            // X
                    GamepadButtonId.Y to 100,           // Y
                    GamepadButtonId.L1 to 102,          // L
                    GamepadButtonId.R1 to 103,          // R
                    GamepadButtonId.START to 108,
                    GamepadButtonId.SELECT to 109
                )

                EmulatorPlatform.MD -> mapOf(
                    GamepadButtonId.DPAD_UP to 19,
                    GamepadButtonId.DPAD_DOWN to 20,
                    GamepadButtonId.DPAD_LEFT to 21,
                    GamepadButtonId.DPAD_RIGHT to 22,
                    GamepadButtonId.A to 96,
                    GamepadButtonId.B to 97,
                    GamepadButtonId.X to 99,
                    GamepadButtonId.Y to 100,
                    GamepadButtonId.START to 108
                )

                EmulatorPlatform.ARCADE -> mapOf(
                    GamepadButtonId.DPAD_UP to 19,
                    GamepadButtonId.DPAD_DOWN to 20,
                    GamepadButtonId.DPAD_LEFT to 21,
                    GamepadButtonId.DPAD_RIGHT to 22,
                    GamepadButtonId.A to 96,
                    GamepadButtonId.B to 97,
                    GamepadButtonId.X to 99,
                    GamepadButtonId.Y to 100,
                    GamepadButtonId.L1 to 102,
                    GamepadButtonId.R1 to 103,
                    GamepadButtonId.START to 108,
                    GamepadButtonId.SELECT to 109
                )
            }
            return KeyMapping(platform, map)
        }

        fun fromJson(json: JSONObject): KeyMapping {
            val platform = EmulatorPlatform.valueOf(json.getString("platform"))
            val arr = json.getJSONArray("mapping")
            val map = LinkedHashMap<GamepadButtonId, Int>()
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val id = GamepadButtonId.valueOf(entry.getString("button"))
                map[id] = entry.getInt("code")
            }
            return KeyMapping(platform, map)
        }
    }
}

/**
 * 手柄整体配置，包含布局模式、尺寸/间距/透明度参数、自定义按钮配置及各平台按键映射。
 *
 * 该类可被序列化为 JSON 以持久化用户设置，使用 [toJson] / [fromJson] 进行转换。
 *
 * @property layout            当前布局模式。
 * @property buttonSizeDp      标准模式下动作按钮的尺寸（dp）。
 * @property dpadSizeDp        方向键整体尺寸（dp）。
 * @property shoulderWidthDp   肩键宽度（dp）。
 * @property shoulderHeightDp  肩键高度（dp）。
 * @property buttonSpacingDp   按钮间距（dp）。
 * @property globalOpacity     全局透明度，0f~1f。
 * @property hapticEnabled     是否启用触觉反馈。
 * @property customButtons     自定义按钮配置列表（仅 CUSTOM 模式生效）。
 * @property keyMappings       各平台的按键映射表。
 * @property currentPlatform   当前激活的平台。
 */
data class GamepadConfig(
    val layout: ButtonLayout = ButtonLayout.STANDARD,
    val buttonSizeDp: Float = 64f,
    val dpadSizeDp: Float = 200f,
    val shoulderWidthDp: Float = 72f,
    val shoulderHeightDp: Float = 40f,
    val buttonSpacingDp: Float = 16f,
    val globalOpacity: Float = 1f,
    val hapticEnabled: Boolean = true,
    val customButtons: List<ButtonConfig> = emptyList(),
    val keyMappings: Map<EmulatorPlatform, KeyMapping> = EmulatorPlatform.values()
        .associateWith { KeyMapping.defaultFor(it) },
    val currentPlatform: EmulatorPlatform = EmulatorPlatform.DEFAULT
) {
    /** 根据当前布局模式与全局尺寸推导出实际渲染尺寸。 */
    fun resolvedButtonSize(buttonId: GamepadButtonId): Float {
        if (layout == ButtonLayout.CUSTOM) {
            val custom = customButtons.firstOrNull { it.buttonId == buttonId }
            if (custom != null && custom.sizeDp > 0f) return custom.sizeDp
        }
        val factor = when (layout) {
            ButtonLayout.STANDARD -> 1.0f
            ButtonLayout.COMPACT -> 0.72f
            ButtonLayout.CUSTOM -> 0.9f
        }
        val base = when (buttonId) {
            in GamepadButtonId.DPAD_BUTTONS -> dpadSizeDp / 3f
            in GamepadButtonId.ACTION_BUTTONS -> buttonSizeDp
            in GamepadButtonId.SHOULDER_BUTTONS -> shoulderWidthDp
            in GamepadButtonId.SYSTEM_BUTTONS -> buttonSizeDp * 0.55f
            else -> buttonSizeDp
        }
        return base * factor
    }

    /** 根据布局模式推导实际间距。 */
    fun resolvedSpacing(): Float = when (layout) {
        ButtonLayout.STANDARD -> buttonSpacingDp
        ButtonLayout.COMPACT -> buttonSpacingDp * 0.6f
        ButtonLayout.CUSTOM -> buttonSpacingDp * 0.8f
    }

    /** 获取当前平台的按键映射。 */
    fun currentKeyMapping(): KeyMapping =
        keyMappings[currentPlatform] ?: KeyMapping.defaultFor(currentPlatform)

    /** 获取指定平台的按键映射，若不存在则返回默认。 */
    fun keyMappingFor(platform: EmulatorPlatform): KeyMapping =
        keyMappings[platform] ?: KeyMapping.defaultFor(platform)

    fun toJson(): JSONObject = JSONObject().apply {
        put("layout", layout.name)
        put("buttonSizeDp", buttonSizeDp)
        put("dpadSizeDp", dpadSizeDp)
        put("shoulderWidthDp", shoulderWidthDp)
        put("shoulderHeightDp", shoulderHeightDp)
        put("buttonSpacingDp", buttonSpacingDp)
        put("globalOpacity", globalOpacity)
        put("hapticEnabled", hapticEnabled)
        put("currentPlatform", currentPlatform.name)

        val customArr = JSONArray()
        customButtons.forEach { customArr.put(it.toJson()) }
        put("customButtons", customArr)

        val mappingObj = JSONObject()
        keyMappings.forEach { (platform, mapping) ->
            mappingObj.put(platform.name, mapping.toJson())
        }
        put("keyMappings", mappingObj)
    }

    /** 将配置序列化为 JSON 字符串，便于持久化存储。 */
    fun toJsonString(): String = toJson().toString()

    companion object {
        /** 创建一份默认配置。 */
        fun default(): GamepadConfig = GamepadConfig()

        fun fromJson(json: JSONObject): GamepadConfig {
            val keyMappings = LinkedHashMap<EmulatorPlatform, KeyMapping>()
            val mappingObj = json.optJSONObject("keyMappings")
            if (mappingObj != null) {
                val keys = mappingObj.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val platform = EmulatorPlatform.valueOf(name)
                    keyMappings[platform] = KeyMapping.fromJson(mappingObj.getJSONObject(name))
                }
            }
            // 确保所有平台都有映射
            EmulatorPlatform.values().forEach { p ->
                if (p !in keyMappings) keyMappings[p] = KeyMapping.defaultFor(p)
            }

            val customArr = json.optJSONArray("customButtons")
            val customButtons = mutableListOf<ButtonConfig>()
            if (customArr != null) {
                for (i in 0 until customArr.length()) {
                    customButtons.add(ButtonConfig.fromJson(customArr.getJSONObject(i)))
                }
            }

            return GamepadConfig(
                layout = runCatching {
                    ButtonLayout.valueOf(json.optString("layout", "STANDARD"))
                }.getOrDefault(ButtonLayout.STANDARD),
                buttonSizeDp = json.optDouble("buttonSizeDp", 64.0).toFloat(),
                dpadSizeDp = json.optDouble("dpadSizeDp", 200.0).toFloat(),
                shoulderWidthDp = json.optDouble("shoulderWidthDp", 72.0).toFloat(),
                shoulderHeightDp = json.optDouble("shoulderHeightDp", 40.0).toFloat(),
                buttonSpacingDp = json.optDouble("buttonSpacingDp", 16.0).toFloat(),
                globalOpacity = json.optDouble("globalOpacity", 1.0).toFloat(),
                hapticEnabled = json.optBoolean("hapticEnabled", true),
                customButtons = customButtons,
                keyMappings = keyMappings,
                currentPlatform = runCatching {
                    EmulatorPlatform.valueOf(json.optString("currentPlatform", "FC"))
                }.getOrDefault(EmulatorPlatform.DEFAULT)
            )
        }

        /** 便捷方法：从 JSON 字符串反序列化配置。 */
        fun fromJsonString(json: String): GamepadConfig = fromJson(JSONObject(json))
    }
}

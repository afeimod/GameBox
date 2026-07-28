package com.retrobox.emulator.cores

import com.retrobox.emulator.CoreInfo
import com.retrobox.emulator.CoreStatus
import com.retrobox.emulator.EmulatorCore

/**
 * 街机模拟器核心
 *
 * 引擎说明：基于 MAME 引擎
 * 支持格式：.zip, .7z
 * 按键映射：可配置（街机按键较多），默认提供 1P 常用布局：
 *           Up, Down, Left, Right, Button1~Button6, Start, Coin
 */
class ArcadeCore : EmulatorCore {

    companion object {
        // ===== 默认按键映射（1P） =====
        const val BUTTON_UP = 0
        const val BUTTON_DOWN = 1
        const val BUTTON_LEFT = 2
        const val BUTTON_RIGHT = 3
        const val BUTTON_1 = 4
        const val BUTTON_2 = 5
        const val BUTTON_3 = 6
        const val BUTTON_4 = 7
        const val BUTTON_5 = 8
        const val BUTTON_6 = 9
        const val BUTTON_START = 10
        const val BUTTON_COIN = 11

        // 支持的文件格式（不含点号）
        val SUPPORTED_FORMATS = listOf("zip", "7z")

        // 默认按键名称列表（顺序即按键 ID）
        val DEFAULT_BUTTON_NAMES = listOf(
            "Up", "Down", "Left", "Right",
            "Button1", "Button2", "Button3",
            "Button4", "Button5", "Button6",
            "Start", "Coin"
        )

        // 帧缓冲尺寸 (320 x 240，街机分辨率多样，使用通用值)
        private const val FRAME_WIDTH = 320
        private const val FRAME_HEIGHT = 240
        private const val FRAME_BUFFER_SIZE = FRAME_WIDTH * FRAME_HEIGHT

        // 音频参数（44100Hz，单声道）
        private const val AUDIO_SAMPLE_RATE = 44100
        private const val FPS = 60
        private const val AUDIO_BUFFER_SIZE = AUDIO_SAMPLE_RATE / FPS
    }

    private var status: CoreStatus = CoreStatus.IDLE
    private var romPath: String? = null
    private val frameBuffer = IntArray(FRAME_BUFFER_SIZE)
    private val audioBuffer = ShortArray(AUDIO_BUFFER_SIZE)

    // 可配置的按键名称列表
    private var buttonNames: List<String> = DEFAULT_BUTTON_NAMES
    // 按键按下状态，长度随 buttonNames 变化
    private var buttonStates: BooleanArray = BooleanArray(buttonNames.size)

    override fun loadRom(path: String): Boolean {
        return try {
            status = CoreStatus.LOADING
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED_FORMATS) {
                status = CoreStatus.ERROR
                return false
            }
            // TODO: 调用 MAME 原生引擎加载 ROM（zip/7z 需先解压或交由 MAME 处理）
            // NativeBridge.arcadeLoadRom(path)
            romPath = path
            buttonStates.fill(false)
            status = CoreStatus.IDLE
            true
        } catch (e: Exception) {
            status = CoreStatus.ERROR
            false
        }
    }

    override fun run() {
        if (status == CoreStatus.IDLE || status == CoreStatus.PAUSED) {
            // TODO: 启动 MAME 引擎执行循环
            status = CoreStatus.RUNNING
        }
    }

    override fun pause() {
        if (status == CoreStatus.RUNNING) {
            status = CoreStatus.PAUSED
        }
    }

    override fun stop() {
        // TODO: 停止并释放原生引擎资源
        status = CoreStatus.STOPPED
    }

    override fun reset() {
        // TODO: 重置引擎内部状态
        buttonStates.fill(false)
        status = CoreStatus.IDLE
    }

    override fun saveState(slot: Int): Boolean {
        return try {
            // TODO: NativeBridge.arcadeSaveState(slot)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun loadState(slot: Int): Boolean {
        return try {
            // TODO: NativeBridge.arcadeLoadState(slot)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun setButtonState(button: Int, pressed: Boolean) {
        if (button in buttonStates.indices) {
            buttonStates[button] = pressed
            // TODO: NativeBridge.arcadeSetInput(button, pressed)
        }
    }

    override fun getFrameBuffer(): IntArray {
        // TODO: 从原生引擎获取一帧像素数据填充到 frameBuffer
        // NativeBridge.arcadeGetFrameBuffer(frameBuffer)
        return frameBuffer
    }

    override fun getAudioBuffer(): ShortArray {
        // TODO: 从原生引擎获取音频采样填充到 audioBuffer
        // NativeBridge.arcadeGetAudioBuffer(audioBuffer)
        return audioBuffer
    }

    override fun getCoreInfo(): CoreInfo = CoreInfo(
        name = "ArcadeCore (MAME)",
        version = "1.0.0",
        supportedPlatforms = listOf("ARCADE", "MAME"),
        supportedFormats = SUPPORTED_FORMATS
    )

    override fun getFrameWidth(): Int = Companion.FRAME_WIDTH
    override fun getFrameHeight(): Int = Companion.FRAME_HEIGHT

    /**
     * 配置按键布局（街机按键较多，支持自定义）
     * @param names 按键名称列表，顺序即按键 ID
     */
    fun configureButtons(names: List<String>) {
        require(names.isNotEmpty()) { "按键列表不能为空" }
        buttonNames = names
        buttonStates = BooleanArray(names.size)
    }

    /** 获取当前核心状态 */
    fun getStatus(): CoreStatus = status

    /** 获取按键映射表（按键名 -> 按键 ID） */
    fun getButtonMapping(): Map<String, Int> {
        return buttonNames.mapIndexed { index, name -> name to index }.toMap()
    }

    /** 获取当前按键名称列表 */
    fun getButtonNames(): List<String> = buttonNames
}

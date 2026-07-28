package com.retrobox.emulator.cores

import com.retrobox.emulator.CoreInfo
import com.retrobox.emulator.CoreStatus
import com.retrobox.emulator.EmulatorCore

/**
 * SNES / SFC 模拟器核心
 *
 * 引擎说明：基于 Snes9x 引擎
 * 支持格式：.smc, .sfc, .fig, .bs
 * 按键映射：A, B, X, Y, L, R, Start, Select, Up, Down, Left, Right
 */
class SNECore : EmulatorCore {

    companion object {
        // ===== 按键映射 =====
        const val BUTTON_A = 0
        const val BUTTON_B = 1
        const val BUTTON_X = 2
        const val BUTTON_Y = 3
        const val BUTTON_L = 4
        const val BUTTON_R = 5
        const val BUTTON_START = 6
        const val BUTTON_SELECT = 7
        const val BUTTON_UP = 8
        const val BUTTON_DOWN = 9
        const val BUTTON_LEFT = 10
        const val BUTTON_RIGHT = 11

        // 支持的文件格式（不含点号）
        val SUPPORTED_FORMATS = listOf("smc", "sfc", "fig", "bs")

        // 帧缓冲尺寸 (256 x 224)
        private const val FRAME_WIDTH = 256
        private const val FRAME_HEIGHT = 224
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
    private val buttonStates = BooleanArray(12)

    override fun loadRom(path: String): Boolean {
        return try {
            status = CoreStatus.LOADING
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED_FORMATS) {
                status = CoreStatus.ERROR
                return false
            }
            // TODO: 调用 Snes9x 原生引擎加载 ROM
            // NativeBridge.snesLoadRom(path)
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
            // TODO: 启动 Snes9x 引擎执行循环
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
            // TODO: NativeBridge.snesSaveState(slot)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun loadState(slot: Int): Boolean {
        return try {
            // TODO: NativeBridge.snesLoadState(slot)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun setButtonState(button: Int, pressed: Boolean) {
        if (button in BUTTON_A..BUTTON_RIGHT) {
            buttonStates[button] = pressed
            // TODO: NativeBridge.snesSetInput(button, pressed)
        }
    }

    override fun getFrameBuffer(): IntArray {
        // TODO: 从原生引擎获取一帧像素数据填充到 frameBuffer
        // NativeBridge.snesGetFrameBuffer(frameBuffer)
        return frameBuffer
    }

    override fun getAudioBuffer(): ShortArray {
        // TODO: 从原生引擎获取音频采样填充到 audioBuffer
        // NativeBridge.snesGetAudioBuffer(audioBuffer)
        return audioBuffer
    }

    override fun getCoreInfo(): CoreInfo = CoreInfo(
        name = "SNECore (Snes9x)",
        version = "1.0.0",
        supportedPlatforms = listOf("SNES", "SFC"),
        supportedFormats = SUPPORTED_FORMATS
    )

    override fun getFrameWidth(): Int = Companion.FRAME_WIDTH
    override fun getFrameHeight(): Int = Companion.FRAME_HEIGHT

    /** 获取当前核心状态 */
    fun getStatus(): CoreStatus = status

    /** 获取按键映射表（按键名 -> 按键 ID） */
    fun getButtonMapping(): Map<String, Int> = mapOf(
        "A" to BUTTON_A,
        "B" to BUTTON_B,
        "X" to BUTTON_X,
        "Y" to BUTTON_Y,
        "L" to BUTTON_L,
        "R" to BUTTON_R,
        "Start" to BUTTON_START,
        "Select" to BUTTON_SELECT,
        "Up" to BUTTON_UP,
        "Down" to BUTTON_DOWN,
        "Left" to BUTTON_LEFT,
        "Right" to BUTTON_RIGHT
    )
}

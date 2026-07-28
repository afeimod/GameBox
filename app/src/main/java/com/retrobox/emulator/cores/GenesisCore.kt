package com.retrobox.emulator.cores

import com.retrobox.emulator.CoreInfo
import com.retrobox.emulator.CoreStatus
import com.retrobox.emulator.EmulatorCore

/**
 * MegaDrive / Genesis 模拟器核心
 *
 * 引擎说明：基于 Genesis Plus GX 引擎
 * 支持格式：.md, .gen, .smd, .bin
 * 按键映射：A, B, C, X, Y, Z, Start, Mode, Up, Down, Left, Right
 */
class GenesisCore : EmulatorCore {

    companion object {
        // ===== 按键映射 =====
        const val BUTTON_A = 0
        const val BUTTON_B = 1
        const val BUTTON_C = 2
        const val BUTTON_X = 3
        const val BUTTON_Y = 4
        const val BUTTON_Z = 5
        const val BUTTON_START = 6
        const val BUTTON_MODE = 7
        const val BUTTON_UP = 8
        const val BUTTON_DOWN = 9
        const val BUTTON_LEFT = 10
        const val BUTTON_RIGHT = 11

        // 支持的文件格式（不含点号）
        val SUPPORTED_FORMATS = listOf("md", "gen", "smd", "bin")

        // 帧缓冲尺寸 (320 x 240)
        private const val FRAME_WIDTH = 320
        private const val FRAME_HEIGHT = 240
        private const val FRAME_BUFFER_SIZE = FRAME_WIDTH * FRAME_HEIGHT

        // 音频参数（44100Hz，立体声按两通道交错，这里按单声道帧采样）
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
            // TODO: 调用 Genesis Plus GX 原生引擎加载 ROM
            // NativeBridge.mdLoadRom(path)
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
            // TODO: 启动 Genesis Plus GX 引擎执行循环
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
            // TODO: NativeBridge.mdSaveState(slot)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun loadState(slot: Int): Boolean {
        return try {
            // TODO: NativeBridge.mdLoadState(slot)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun setButtonState(button: Int, pressed: Boolean) {
        if (button in BUTTON_A..BUTTON_RIGHT) {
            buttonStates[button] = pressed
            // TODO: NativeBridge.mdSetInput(button, pressed)
        }
    }

    override fun getFrameBuffer(): IntArray {
        // TODO: 从原生引擎获取一帧像素数据填充到 frameBuffer
        // NativeBridge.mdGetFrameBuffer(frameBuffer)
        return frameBuffer
    }

    override fun getAudioBuffer(): ShortArray {
        // TODO: 从原生引擎获取音频采样填充到 audioBuffer
        // NativeBridge.mdGetAudioBuffer(audioBuffer)
        return audioBuffer
    }

    override fun getCoreInfo(): CoreInfo = CoreInfo(
        name = "GenesisCore (Genesis Plus GX)",
        version = "1.0.0",
        supportedPlatforms = listOf("GENESIS", "MD", "MEGADRIVE"),
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
        "C" to BUTTON_C,
        "X" to BUTTON_X,
        "Y" to BUTTON_Y,
        "Z" to BUTTON_Z,
        "Start" to BUTTON_START,
        "Mode" to BUTTON_MODE,
        "Up" to BUTTON_UP,
        "Down" to BUTTON_DOWN,
        "Left" to BUTTON_LEFT,
        "Right" to BUTTON_RIGHT
    )
}

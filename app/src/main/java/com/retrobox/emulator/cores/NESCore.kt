package com.retrobox.emulator.cores

import com.retrobox.emulator.CoreInfo
import com.retrobox.emulator.CoreStatus
import com.retrobox.emulator.EmulatorCore

/**
 * NES / FC 模拟器核心
 *
 * 引擎说明：基于 FCEUX / Nestopia 引擎
 * 支持格式：.nes, .fds, .unf, .nez
 * 按键映射：A, B, Start, Select, Up, Down, Left, Right
 */
class NESCore : EmulatorCore {

    companion object {
        // ===== 按键映射 =====
        const val BUTTON_A = 0
        const val BUTTON_B = 1
        const val BUTTON_START = 2
        const val BUTTON_SELECT = 3
        const val BUTTON_UP = 4
        const val BUTTON_DOWN = 5
        const val BUTTON_LEFT = 6
        const val BUTTON_RIGHT = 7

        // 支持的文件格式（不含点号）
        val SUPPORTED_FORMATS = listOf("nes", "fds", "unf", "nez")

        // 帧缓冲尺寸 (256 x 240)
        private const val FRAME_WIDTH = 256
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
    private val buttonStates = BooleanArray(8)

    override fun loadRom(path: String): Boolean {
        return try {
            status = CoreStatus.LOADING
            // 校验文件格式
            val ext = path.substringAfterLast('.', "").lowercase()
            if (ext !in SUPPORTED_FORMATS) {
                status = CoreStatus.ERROR
                return false
            }
            // TODO: 调用 FCEUX / Nestopia 原生引擎加载 ROM
            // NativeBridge.nesLoadRom(path)
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
            // TODO: 启动 FCEUX / Nestopia 引擎执行循环
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
            // TODO: NativeBridge.nesSaveState(slot)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun loadState(slot: Int): Boolean {
        return try {
            // TODO: NativeBridge.nesLoadState(slot)
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun setButtonState(button: Int, pressed: Boolean) {
        if (button in BUTTON_A..BUTTON_RIGHT) {
            buttonStates[button] = pressed
            // TODO: 将按键状态同步到原生引擎
            // NativeBridge.nesSetInput(button, pressed)
        }
    }

    override fun getFrameBuffer(): IntArray {
        // TODO: 从原生引擎获取一帧像素数据填充到 frameBuffer
        // NativeBridge.nesGetFrameBuffer(frameBuffer)
        return frameBuffer
    }

    override fun getAudioBuffer(): ShortArray {
        // TODO: 从原生引擎获取音频采样填充到 audioBuffer
        // NativeBridge.nesGetAudioBuffer(audioBuffer)
        return audioBuffer
    }

    override fun getCoreInfo(): CoreInfo = CoreInfo(
        name = "NESCore (FCEUX/Nestopia)",
        version = "1.0.0",
        supportedPlatforms = listOf("NES", "FC"),
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
        "Start" to BUTTON_START,
        "Select" to BUTTON_SELECT,
        "Up" to BUTTON_UP,
        "Down" to BUTTON_DOWN,
        "Left" to BUTTON_LEFT,
        "Right" to BUTTON_RIGHT
    )
}

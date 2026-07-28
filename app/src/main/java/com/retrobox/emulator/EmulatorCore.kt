package com.retrobox.emulator

/**
 * 模拟器核心状态枚举
 */
enum class CoreStatus {
    /** 空闲 */
    IDLE,
    /** 加载中 */
    LOADING,
    /** 运行中 */
    RUNNING,
    /** 已暂停 */
    PAUSED,
    /** 已停止 */
    STOPPED,
    /** 错误 */
    ERROR
}

/**
 * 模拟器核心信息
 *
 * @property name               核心名称
 * @property version            核心版本
 * @property supportedPlatforms 支持的平台列表
 * @property supportedFormats   支持的文件格式列表（不含点号，小写）
 */
data class CoreInfo(
    val name: String,
    val version: String,
    val supportedPlatforms: List<String>,
    val supportedFormats: List<String>
)

/**
 * 模拟器核心接口
 *
 * 所有模拟器核心必须实现此接口，定义了加载 ROM、运行控制、
 * 存档管理、输入处理以及帧 / 音频缓冲获取等基础能力。
 */
interface EmulatorCore {

    /**
     * 加载 ROM 文件
     * @param path ROM 文件的本地路径
     * @return 是否加载成功
     */
    fun loadRom(path: String): Boolean

    /** 运行模拟器 */
    fun run()

    /** 暂停模拟器 */
    fun pause()

    /** 停止模拟器 */
    fun stop()

    /** 重置模拟器 */
    fun reset()

    /**
     * 保存当前状态到指定存档槽
     * @param slot 存档槽位
     * @return 是否保存成功
     */
    fun saveState(slot: Int): Boolean

    /**
     * 从指定存档槽加载状态
     * @param slot 存档槽位
     * @return 是否加载成功
     */
    fun loadState(slot: Int): Boolean

    /**
     * 设置按键状态
     * @param button 按键 ID（参见各核心的按键常量）
     * @param pressed 是否按下
     */
    fun setButtonState(button: Int, pressed: Boolean)

    /**
     * 获取帧缓冲区（ARGB_8888 格式的像素数组）
     * @return 帧缓冲数组
     */
    fun getFrameBuffer(): IntArray

    /**
     * 获取音频缓冲区（16 位 PCM 采样数组）
     * @return 音频缓冲数组
     */
    fun getAudioBuffer(): ShortArray

    /**
     * 获取核心信息
     * @return 核心信息对象
     */
    fun getCoreInfo(): CoreInfo

    // ---- 以下为渲染辅助方法（带默认实现，核心可按需覆写） ----

    /** 帧缓冲宽度（像素） */
    fun getFrameWidth(): Int = 256

    /** 帧缓冲高度（像素） */
    fun getFrameHeight(): Int = 240
}

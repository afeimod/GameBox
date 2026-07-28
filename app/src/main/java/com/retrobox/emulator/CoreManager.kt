package com.retrobox.emulator

import com.retrobox.emulator.cores.ArcadeCore
import com.retrobox.emulator.cores.GenesisCore
import com.retrobox.emulator.cores.NESCore
import com.retrobox.emulator.cores.SNECore
import java.util.concurrent.ConcurrentHashMap

/**
 * 模拟器核心管理器
 *
 * 负责所有模拟器核心的注册、加载与生命周期管理：
 * - 维护已注册核心列表
 * - 根据文件扩展名自动选择核心
 * - 核心优先级排序（同一格式可被多个核心支持，优先级高者优先）
 * - 当前活跃核心的生命周期管理
 */
class CoreManager {

    // 已注册的核心列表
    private val registeredCores = mutableListOf<EmulatorCore>()

    // 扩展名 -> 支持该格式的核心列表（已按优先级降序排序）
    private val extensionMap = ConcurrentHashMap<String, MutableList<EmulatorCore>>()

    // 核心名称 -> 优先级
    private val corePriorities = mutableMapOf<String, Int>()

    // 当前活跃的核心
    @Volatile
    private var activeCore: EmulatorCore? = null

    init {
        // 注册内置核心
        registerCore(NESCore(), priority = 100)
        registerCore(SNECore(), priority = 100)
        registerCore(GenesisCore(), priority = 100)
        registerCore(ArcadeCore(), priority = 90)
    }

    /**
     * 注册一个核心
     *
     * @param core     要注册的核心实例
     * @param priority 优先级，数值越大优先级越高（同一格式冲突时取优先级高者）
     */
    fun registerCore(core: EmulatorCore, priority: Int = 0) {
        synchronized(registeredCores) {
            registeredCores.add(core)
            val info = core.getCoreInfo()
            corePriorities[info.name] = priority
            // 将核心注册到其支持的所有格式下
            for (format in info.supportedFormats) {
                val key = format.lowercase()
                val list = extensionMap.getOrPut(key) { mutableListOf() }
                if (list.none { it.getCoreInfo().name == info.name }) {
                    list.add(core)
                    // 按优先级降序排序
                    list.sortByDescending { corePriorities[it.getCoreInfo().name] ?: 0 }
                }
            }
        }
    }

    /**
     * 注销一个核心
     */
    fun unregisterCore(core: EmulatorCore) {
        synchronized(registeredCores) {
            registeredCores.remove(core)
            corePriorities.remove(core.getCoreInfo().name)
            extensionMap.values.forEach { it.remove(core) }
            if (activeCore === core) {
                activeCore = null
            }
        }
    }

    /**
     * 根据文件路径自动选择核心（按扩展名 + 优先级）
     *
     * @param filePath ROM 文件路径
     * @return 匹配到的核心，未匹配返回 null
     */
    fun selectCore(filePath: String): EmulatorCore? {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return null
        return extensionMap[ext]?.firstOrNull()
    }

    /**
     * 加载 ROM 并激活对应核心（不执行 run）
     *
     * @param filePath ROM 文件路径
     * @return 已加载 ROM 的活跃核心，失败返回 null
     */
    fun loadCore(filePath: String): EmulatorCore? {
        val core = selectCore(filePath) ?: return null
        // 切换核心前先释放旧核心
        if (activeCore != null && activeCore !== core) {
            activeCore?.stop()
        }
        val ok = core.loadRom(filePath)
        if (!ok) return null
        activeCore = core
        return core
    }

    /**
     * 根据平台名称获取核心
     */
    fun getCoreByPlatform(platform: String): EmulatorCore? {
        val target = platform.uppercase()
        return synchronized(registeredCores) {
            registeredCores.firstOrNull { core ->
                core.getCoreInfo().supportedPlatforms.any { it.equals(target, ignoreCase = true) }
            }
        }
    }

    /** 获取所有已注册的核心 */
    fun getAllCores(): List<EmulatorCore> = synchronized(registeredCores) { registeredCores.toList() }

    /** 获取所有受支持的扩展名 */
    fun getSupportedExtensions(): Set<String> = extensionMap.keys.toSet()

    /** 获取当前活跃核心 */
    fun getActiveCore(): EmulatorCore? = activeCore

    /**
     * 释放当前活跃核心（停止并置空）
     */
    fun releaseActiveCore() {
        activeCore?.stop()
        activeCore = null
    }
}

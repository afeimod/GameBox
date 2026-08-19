package com.nesstation.app.battle

import android.os.Handler
import android.os.Looper
import com.nesstation.app.core.engine.NetplayHook
import com.nesstation.app.core.model.GamePlatform
import java.util.concurrent.ConcurrentHashMap

/**
 * 帧同步联机控制器：通过 [NetplayHook] 接入到既有 [com.nesstation.app.core.engine.EmulatorEngine]
 * 的模拟循环中，让本地 / 远端两位玩家在同一 ROM 上同帧同画执行。
 *
 * ## 设计要点（输入延迟 + 非阻塞兜底）
 *
 *  - 双方各自本地运行同一 ROM（FBNeo / FCEUmm / snes9x / mGBA / Genesis-Plus-GX / Geargrafx /
 *    DOSBox-Pure 等确定性核心），服务器只转发输入。
 *  - 双方帧计数从 0 同时起步，**各自按引擎本身的 60fps 节奏独立推进，互不阻塞**。
 *  - 每个"采样帧 N"：采集本地摇杆输入 → 带 frame 号发给服务器 → 存入本地历史。
 *  - 实际执行帧 = `N - inputDelay`：此时对方的该帧输入通常早已到达（网络往返被
 *    inputDelay 缓冲），双方在完全相同的输入序列上运行 → 状态一致。
 *  - **关键：[beforeFrame] 绝不阻塞**。对方输入若没到（极少数抖动场景），用上一帧
 *    输入兜底（`lastRemotePad`），让引擎继续 60fps 跑，记一笔 desync。
 *
 * ## 为什么不阻塞
 *
 * 早期版本会 `waitForRemote(execFrame)` 最多阻塞 500ms 等对方输入。这把引擎从
 * 60fps（16.6ms/帧）退化成「等几十毫秒 → 跑一帧 → 再等 → 再跑」的逐帧播放体验。
 * 正确的设计是：**网络延迟由 inputDelay 缓冲，inputDelay 不够时降级用上一帧输入，
 * 游戏速度永远不变**。如果用户网络 RTT > `inputDelay × 16.6ms`，让对方动作看起来
 * 慢半拍（输入 desync），但游戏仍然 60fps 流畅运行 —— 而不是变成 PPT。解决方案是
 * 在服务器 `config.json` 调高 `inputDelay`（如 6/8），不是阻塞游戏。
 *
 * ## 与旧 NetplayEngine 的区别
 *
 *  - 旧的 NetplayEngine 自己维护一个 CoreDispatcher、自己起线程、自己读音频 / 调 runFrame。
 *    这导致进入对战时重新绘制了一份独立的 SurfaceView + OnScreenController + 加载流程
 *    （「重新绘制游戏界面，启动非常慢，位置都不对」就是这里来的）。
 *  - 新的 [NetplayController] 是一个轻量 hook：不拥有任何线程、不接管音频、不接管 surface，
 *    只在引擎每一帧调用 [beforeFrame] / [afterFrame] 时做输入采样、网络发送、远端输入查询。
 *    实际的 ROM 加载、SurfaceView 绘制、OnScreenController、菜单、布局编辑器、存档……
 *    全部由 [com.nesstation.app.ui.emulator.EmulatorScreen] 中的既有逻辑处理，
 *    和「本地游戏」的启动路径完全一致 —— 唯一不同的是引擎循环里多了这个 hook。
 *
 * ## 线程模型
 *
 *  - 模拟器线程：[EmulatorEngine.loadRom] 起的引擎线程，每帧调用本类的 [beforeFrame] /
 *    [afterFrame]。**本类的方法都在这里被调用，绝不阻塞它**。
 *  - 网络线程：[BattleNetplay.connect] 起的 TCP 读线程，收到对方输入时回调 [onRemoteInput]
 *    把数据写进 [remoteHistory]（线程安全的 ConcurrentHashMap）。
 *  - UI 线程：通过 [UiListener] 接收回调（已通过 main Handler 自动 post 到主线程）。
 */
class NetplayController(
    val platform: GamePlatform = GamePlatform.ARCADE
) : NetplayHook {

    /**
     * UI 回调接口（所有方法保证在主线程被调用）。
     */
    interface UiListener {
        /** 每执行一帧后回调（UI 可刷新延迟/帧号显示） */
        fun onFrameInfo(frame: Long, inputDelay: Int, desyncCount: Int)
        /** 对方加入 */
        fun onPeerJoined(username: String)
        /** 对方断开 */
        fun onPeerLeft(username: String)
        /** 连接被拒绝 / 协议错误 */
        fun onError(message: String)
        /** 连接关闭（非主动） */
        fun onDisconnected()
        /** 对端输入长时间缺失，连接已不可用 */
        fun onNetplayLost(reason: String)
    }

    @Volatile private var primaryListener: UiListener? = null
    private val uiListeners = java.util.concurrent.CopyOnWriteArrayList<UiListener>()

    /** 设置主 UI 监听器（向后兼容旧 API）。建议改用 [addUiListener] / [removeUiListener]。 */
    fun setUiListener(l: UiListener?) { primaryListener = l }

    /** 添加一个 UI 监听器。多个监听器可同时存在（例如 BattleMatchScreen 监听致命错误，同时
     *  EmulatorScreen 监听状态条信息）。所有监听器在主线程被回调。 */
    fun addUiListener(l: UiListener) { uiListeners.add(l) }

    /** 移除一个 UI 监听器。 */
    fun removeUiListener(l: UiListener) { uiListeners.remove(l) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() === Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    private fun dispatch(block: (UiListener) -> Unit) {
        val primary = primaryListener
        val list = uiListeners
        if (primary == null && list.isEmpty()) return
        postToMain {
            primary?.let { block(it) }
            list.forEach { block(it) }
        }
    }

    // === 本地输入缓冲（由 UI 通过 setLocalPad 推送） ===
    @Volatile private var currentLocalPad = 0

    // === 帧历史：frame -> pad（环形容量 1024，足够 inputDelay + 抖动窗口） ===
    private val localHistory = ConcurrentHashMap<Long, Int>()
    private val remoteHistory = ConcurrentHashMap<Long, Int>()

    /** 由服务器在 hello 响应中下发。每帧从 [BattleNetplay.inputDelay] 同步，避免握手竞态。 */
    @Volatile private var inputDelay = 4
    @Volatile private var lastRemotePad = 0
    @Volatile private var netplayLost = false
    @Volatile private var desyncCount = 0

    // === 网络 ===
    @Volatile private var net: BattleNetplay? = null

    private val netListener = object : BattleNetplay.Listener {
        override fun onRemoteInput(frame: Long, pad: Int) {
            // 网络线程：直接写入 remoteHistory（ConcurrentHashMap 线程安全）
            remoteHistory[frame] = pad
        }

        override fun onPeerJoined(username: String) {
            dispatch { it.onPeerJoined(username) }
        }

        override fun onPeerLeft(username: String) {
            dispatch { it.onPeerLeft(username) }
        }

        override fun onError(message: String) {
            dispatch { it.onError(message) }
        }

        override fun onDisconnected() {
            dispatch { it.onDisconnected() }
        }
    }

    /**
     * 连接到中继服务器。非阻塞：[BattleNetplay.connect] 在自己的线程里完成 TCP 握手。
     *
     * 调用本方法前可以先把 [uiListener] 设上，确保不会错过任何回调。
     */
    fun connect(host: String, port: Int, token: String, roomId: String) {
        val n = BattleNetplay(host, port, token, roomId, netListener)
        net = n
        n.connect()
    }

    /** 本地玩家摇杆输入（bit 布局与平台 pad 一致，由 OnScreenController / 物理手柄推送）。 */
    fun setLocalPad(bits: Int) {
        currentLocalPad = bits
    }

    /**
     * 引擎在调用 runFrame 之前回调：采样本地输入 → 发给服务器 → 非阻塞拿对方同帧输入。
     *
     * ## 关键设计：**绝不阻塞引擎线程**
     *
     * 之前这里有 `waitForRemote(execFrame)` 最多阻塞 500ms 等对方输入，导致引擎
     * 从 60fps（16.6ms/帧）退化成「等几十毫秒 → 跑一帧 → 再等 → 再跑」的逐帧
     * 播放体验。改造后：
     *
     * - 采样本地输入 + 发送给服务器：非阻塞
     * - 取对方第 `execFrame = frame - inputDelay` 帧的输入：
     *   - 命中 → 用对方的真实输入
     *   - 未命中 → 短暂自旋等 ≤2ms（`Thread.yield()`，吸收线程调度抖动，绝不会
     *     把单帧时间拉长到超过 16.6ms）
     *   - 2ms 后还没到 → 用上一帧的输入兜底（`lastRemotePad`），让引擎继续按
     *     60fps 跑，记一笔 desync。这是「输入延迟 + 兜底」模型的核心：网络抖动
     *     由 inputDelay 缓冲，inputDelay 不够时降级用上一帧输入，**游戏速度不变**。
     *
     * 如果用户网络 RTT > `inputDelay × 16.6ms`，会持续出现 desync（对方动作看起来
     * 慢半拍）；解决方案是在服务器 `config.json` 调高 `inputDelay`（如 6/8），
     * 而不是阻塞游戏。
     *
     * @param frame 引擎内部单调递增的帧号（0-based，hook attach 时被重置为 0）。
     * @return (pad1, pad2) 用于推送给核心；warmup 阶段（frame < inputDelay）返回 (0, 0)。
     */
    override fun beforeFrame(frame: Long): Pair<Int, Int>? {
        val localPad = currentLocalPad
        localHistory[frame] = localPad

        val n = net
        if (n == null) {
            // 网络未就绪 —— 直接用本地输入跑（warmup 单机模式，不阻塞）
            return localPad to lastRemotePad
        }

        // 每帧从服务器握手响应里同步 inputDelay（hello 到达后会自动更新；
        // 到达之前用默认值 4，与服务器默认一致）
        inputDelay = n.inputDelay.coerceIn(0, 8)

        // 把本地这一帧的输入送给服务器（非阻塞，TCP 发送缓冲）
        try { n.sendInput(frame, localPad) } catch (_: Throwable) {}

        // 实际执行帧 = 采样帧 - inputDelay（warmup 阶段不执行真实输入）
        val execFrame = frame - inputDelay
        if (execFrame < 0) {
            // 预热：什么都不按，避免输入乱序
            return 0 to 0
        }

        val localForExec = localHistory[execFrame] ?: localPad

        // === 非阻塞拿对方输入 ===
        // 先直接查：99% 的情况下对方的该帧输入早就在 remoteHistory 里了
        // （因为 inputDelay 缓冲了 RTT）。
        var remoteForExec = remoteHistory[execFrame]
        if (remoteForExec == null) {
            // 没命中。短暂自旋等 2ms —— 这是给线程调度一点时间，吸收
            // "对方刚发出来、网络线程刚 put 进 map、引擎线程正好这一刻查"
            // 的极小竞态。2ms 远小于 16.6ms 帧预算，绝不会拖慢游戏。
            remoteForExec = spinWaitRemote(execFrame, maxMs = 2L)
        }

        if (remoteForExec != null) {
            // 命中：更新 lastRemotePad，清掉 netplayLost 标志
            lastRemotePad = remoteForExec
            if (netplayLost) {
                netplayLost = false  // 恢复了
            }
        } else {
            // 还是没拿到 —— 用上一帧输入兜底，**不阻塞引擎**
            desyncCount++
            // 第一次持续丢失时通知 UI（之后只在状态变化时通知，避免每帧都 post）
            if (!netplayLost) {
                netplayLost = true
                val dc = desyncCount
                dispatch { it.onNetplayLost("网络抖动：对方输入延迟到达，已用上一帧输入兜底（不阻塞游戏）· desync $dc") }
            }
        }
        return localForExec to lastRemotePad
    }

    /**
     * 极短的自旋等待（≤ maxMs 毫秒）：用 `Thread.yield()` 让出 CPU 给网络线程，
     * 等它把 [targetFrame] 帧的输入 put 进 [remoteHistory]。
     *
     * 用于吸收「网络已收到对方输入，但还没 put 进 map」的线程调度竞态。
     * **绝不能用来等真正的网络延迟** —— 真正的 RTT 由 inputDelay 缓冲，超出的
     * 部分用 [lastRemotePad] 兜底，不阻塞。
     *
     * @return 收到的输入；超时返回 null（调用方负责兜底）。
     */
    private fun spinWaitRemote(targetFrame: Long, maxMs: Long): Int? {
        val deadlineNs = System.nanoTime() + maxMs * 1_000_000L
        while (true) {
            remoteHistory[targetFrame]?.let { return it }
            if (System.nanoTime() >= deadlineNs) return null
            Thread.yield()
        }
    }

    override fun afterFrame(frame: Long) {
        // 在引擎线程触发。
        //
        // **节流**：UI 状态条每秒更新 4 次足够（每 15 帧 dispatch 一次）。
        // 之前每帧都 post 到主线程触发 Compose recomposition，60 次/秒的重组
        // 反而会让 UI 卡顿（因为 recomposition 比 runFrame 还重）。
        if (frame % 15 == 0L) {
            val il = inputDelay
            val dc = desyncCount
            dispatch { it.onFrameInfo(frame, il, dc) }
        }
        if (frame % 256 == 0L) {
            cleanupHistory(frame)
        }
    }

    private fun cleanupHistory(frame: Long) {
        val cutoff = frame - 1024
        if (cutoff > 0) {
            localHistory.keys.removeAll { it < cutoff }
            remoteHistory.keys.removeAll { it < cutoff }
        }
    }

    /**
     * 主动断开网络并清理状态。不会卸载 ROM —— 那是 [com.nesstation.app.core.engine.EmulatorEngine]
     * 的事。本方法只清掉自己的帧历史和 TCP 连接。
     *
     * 推荐在 EmulatorScreen 的 onDispose 里先 [EmulatorEngine].frameHook = null，再 stop()。
     */
    fun stop() {
        try { net?.close() } catch (_: Throwable) {}
        net = null
        localHistory.clear()
        remoteHistory.clear()
        currentLocalPad = 0
        lastRemotePad = 0
        netplayLost = false
        desyncCount = 0
    }

    /** 当前是否还在和对方同帧同画同步。 */
    val isAlive: Boolean get() = !netplayLost && net != null
}

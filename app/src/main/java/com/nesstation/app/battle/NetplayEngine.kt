package com.nesstation.app.battle

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface
import com.nesstation.app.core.jni.FbNeoNative
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 帧同步联机引擎：直接驱动 FbNeoNative，按帧推进模拟器。
 *
 * 设计（锁步 lockstep + 输入延迟）：
 *  - 双方各自本地运行同一 ROM（FBNeo 确定性核心），服务器只转发输入。
 *  - 双方帧计数从 0 同时起步，各以 60fps 节奏推进。
 *  - 每个"采样帧"：采集本地摇杆输入 -> 带 frame 号发给服务器 -> 存入本地历史。
 *  - 实际执行帧 = 采样帧 - inputDelay：此时对方的该帧输入早已到达（网络往返被
 *    输入延迟吸收），因此双方在完全相同的输入序列上运行 -> 状态一致。
 *  - 若对方某帧输入因抖动未到，短暂等待；超时则复用上一帧输入（橡皮筋），
 *    记录 desync 计数用于 UI 提示。
 *
 * 线程模型（与 FbNeoEngine 一致）：
 *  - emu 线程：采样 + 发送 + 按帧执行模拟器 + 60fps 节拍
 *  - audio 线程：从原生环形缓冲读音频写 AudioTrack
 *  - net 线程（BattleNetplay）：收对方输入，填充 remoteHistory
 */
class NetplayEngine(
    private val romFile: File,
    private val systemDir: String,
    private val saveDir: String,
    val net: BattleNetplay
) {

    interface Listener {
        /** 每执行一帧后回调（UI 可刷新延迟/帧号显示） */
        fun onFrame(frame: Long, inputDelay: Int, desyncCount: Int)
        /** 模拟器退出（正常结束） */
        fun onExit()
        /** 对端输入长时间缺失，连接已不可用 */
        fun onNetplayLost()
    }

    private val running = AtomicBoolean(false)
    private var emuThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private val audioRunning = AtomicBoolean(false)
    private var audioThread: Thread? = null

    // 双方就绪信号：两个客户端都发送 ready 后服务器才真正转发输入，
    // 锁步要求双方帧计数从同一时刻起步，所以用这个闩等待对端。
    @Volatile private var peerReadyLatch: java.util.concurrent.CountDownLatch = newPeerLatch()

    @Volatile private var currentLocalPad = 0
    @Volatile private var lastRemotePad = 0

    // 帧历史：frame -> pad（环形容量 512，够输入延迟 + 抖动窗口）
    private val localHistory = ConcurrentHashMap<Long, Int>()
    private val remoteHistory = ConcurrentHashMap<Long, Int>()

    @Volatile private var inputDelay = 4
    @Volatile private var isLoaded = false
    @Volatile private var hasSurface = false
    @Volatile private var netplayLost = false

    private var listener: Listener? = null
    private val engineLock = Any()

    // 60fps 节拍
    private val frameNs = 1_000_000_000L / 60

    val loaded: Boolean get() = isLoaded

    fun setListener(l: Listener?) { listener = l }

    /** 本地玩家摇杆输入（bit 布局同 FBNeo 12 键） */
    fun setLocalPad(bits: Int) { currentLocalPad = bits }

    fun setSurface(surface: Surface?) {
        hasSurface = surface != null
        FbNeoNative.setSurface(surface)
    }

    fun videoWidth(): Int = if (isLoaded) FbNeoNative.videoWidth() else 320
    fun videoHeight(): Int = if (isLoaded) FbNeoNative.videoHeight() else 240

    fun setVideoFilter(filter: Int) = FbNeoNative.setVideoFilter(filter)
    fun setHighQualityScaling(enabled: Boolean) = FbNeoNative.setHighQualityScaling(enabled)

    /**
     * 加载 ROM 并启动帧同步循环。
     * 需在 [BattleNetplay.onStart] 之后调用（此时已知道 inputDelay）。
     * 本方法不阻塞 UI 线程：ROM 加载、ready 同步都在 emu 线程内完成。
     */
    fun start(listener: Listener? = null): Boolean = synchronized(engineLock) {
        this.listener = listener
        if (!FbNeoNative.ensureLoaded()) {
            return false
        }
        // 清理旧会话
        stopInternal()

        inputDelay = net.inputDelay.coerceIn(0, 8)
        running.set(true)

        emuThread = thread(name = "netplay-emu", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)

            // 1. 在 emu 线程加载 ROM（避免阻塞 UI）
            FbNeoNative.setPaths(systemDir, saveDir)
            FbNeoNative.setSaveName("netplay_" + romFile.nameWithoutExtension)
            if (!FbNeoNative.loadRom(romFile.absolutePath)) {
                android.util.Log.e("NetplayEngine", "loadRom failed: ${FbNeoNative.lastError()}")
                running.set(false)
                listener?.onExit()
                return@thread
            }
            isLoaded = true

            // 2. 通知服务器本地已就绪
            net.sendReady()

            // 3. 等待对方就绪（锁步前提）。超时 15s 直接开跑。
            val peerReady = try {
                peerReadyLatch.await(15, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
            if (!running.get()) return@thread
            if (!peerReady) {
                android.util.Log.w("NetplayEngine", "等待对方就绪超时，仍以当前状态开始")
            }

            // 4. 启动音频
            val rate = FbNeoNative.audioTargetSampleRate().takeIf { it > 0 } ?: 48000
            startAudio(rate)

            // 5. 帧同步循环
            var frame = 0L
            val startNs = System.nanoTime()
            var desync = 0
            try {
                while (running.get()) {
                    val t0 = System.nanoTime()

                    // 1. 采样本地输入，带当前帧号发送（对方 delay 帧后使用）
                    val localPad = currentLocalPad
                    localHistory[frame] = localPad
                    net.sendInput(frame, localPad)

                    // 2. 执行帧 = 采样帧 - inputDelay（预热阶段不执行）
                    val execFrame = frame - inputDelay
                    if (execFrame >= 0) {
                        val localForExec = localHistory[execFrame] ?: localPad
                        val remoteForExec = waitForRemote(execFrame) { desync++ }
                        lastRemotePad = remoteForExec
                        FbNeoNative.setPad1(localForExec)
                        FbNeoNative.setPad2(remoteForExec)
                        FbNeoNative.runFrame()
                        listener?.onFrame(execFrame, inputDelay, desync)
                    }

                    frame++

                    // 清理过期的历史（保留最近 1024 帧）
                    if (frame % 256 == 0L) {
                        cleanupHistory(frame)
                    }

                    // 3. 60fps 节拍
                    val elapsed = System.nanoTime() - t0
                    val target = startNs + frame * frameNs
                    val wait = target - System.nanoTime()
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NetplayEngine", "Emu thread crashed", t)
            } finally {
                running.set(false)
                listener?.onExit()
            }
        }
        return true
    }

    /** 由 BattleNetplay 在收到 peer_ready 时调用 */
    fun onPeerReady() {
        peerReadyLatch.countDown()
    }

    /**
     * 等待对方第 targetFrame 帧的输入。
     * 返回时已就绪的输入；若超时（网络抖动），回调 onMiss 并返回上一帧输入。
     */
    private fun waitForRemote(targetFrame: Long, onMiss: () -> Unit): Int {
        val deadline = System.currentTimeMillis() + 500 // 最多等 500ms
        while (running.get()) {
            remoteHistory[targetFrame]?.let { return it }
            if (System.currentTimeMillis() >= deadline) {
                if (!netplayLost) {
                    netplayLost = true
                    listener?.onNetplayLost()
                }
                onMiss()
                return lastRemotePad
            }
            try {
                Thread.sleep(2)
            } catch (_: InterruptedException) {
                return lastRemotePad
            }
        }
        return lastRemotePad
    }

    private fun cleanupHistory(frame: Long) {
        val cutoff = frame - 1024
        if (cutoff > 0) {
            localHistory.keys.removeAll { it < cutoff }
            remoteHistory.keys.removeAll { it < cutoff }
        }
    }

    // --- 音频（与 FbNeoEngine 相同的管道） ---

    private fun startAudio(sampleRate: Int) {
        stopAudio()
        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufSize = (minBuf * 4).coerceAtLeast(8192)
            audioTrack = AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
                bufSize,
                AudioTrack.MODE_STREAM,
                AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
            )
            audioTrack?.play()
        } catch (e: Exception) {
            audioTrack = null
        }

        audioRunning.set(true)
        audioThread = thread(name = "netplay-audio", isDaemon = true) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buf = ShortArray(4096)
            try {
                while (audioRunning.get()) {
                    try {
                        val n = FbNeoNative.readAudio(buf)
                        if (n > 0) {
                            audioTrack?.write(buf, 0, n * 2, AudioTrack.WRITE_BLOCKING)
                        } else {
                            Thread.sleep(2)
                        }
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("NetplayEngine", "Audio thread crashed", t)
            }
        }
    }

    private fun stopAudio() {
        audioRunning.set(false)
        audioThread?.let {
            it.interrupt()
            try { it.join(200) } catch (_: InterruptedException) {}
        }
        audioThread = null
        audioTrack?.let {
            try { it.stop() } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioTrack = null
    }

    /** 接收对方输入的入口（由 BattleNetplay 回调） */
    fun onRemoteInput(frame: Long, pad: Int) {
        remoteHistory[frame] = pad
    }

    private fun stopInternal() {
        running.set(false)
        emuThread?.let { t ->
            t.interrupt()
            for (attempt in 0 until 6) {
                try { t.join(500) } catch (_: InterruptedException) { break }
                if (!t.isAlive) break
            }
        }
        emuThread = null
        stopAudio()
        if (isLoaded) {
            try { FbNeoNative.unload() } catch (_: Throwable) {}
            isLoaded = false
        }
        localHistory.clear()
        remoteHistory.clear()
        hasSurface = false
        netplayLost = false
        // 重建闩，确保下一局能正确等待对方
        peerReadyLatch = newPeerLatch()
    }

    companion object {
        private fun newPeerLatch() = java.util.concurrent.CountDownLatch(1)
    }

    fun stop() = synchronized(engineLock) {
        stopInternal()
    }

    fun unload() = stop()
}

package com.retrobox.emulator

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.view.Surface

/**
 * 模拟器运行线程
 *
 * 在独立线程中驱动模拟器核心，负责：
 * - 通过 [Surface]（来自 SurfaceView / TextureView）进行画面渲染
 * - 通过 [AudioTrack] 进行音频输出
 * - 帧率控制（默认 60 FPS）
 *
 * 使用方式：
 * ```
 * val thread = EmulatorThread(core)
 * thread.setSurface(surfaceView.holder.surface)
 * thread.startEmulator()
 * // ...
 * thread.pauseEmulator()
 * thread.resumeEmulator()
 * thread.stopEmulator()
 * ```
 *
 * 注意：本类继承 [Thread]，一个实例只能启动一次；需要重新运行时请创建新实例。
 */
class EmulatorThread(
    private val core: EmulatorCore
) : Thread("EmulatorThread") {

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    private var surface: Surface? = null
    private var audioTrack: AudioTrack? = null
    private var renderBitmap: Bitmap? = null

    // 目标帧率
    private val targetFps = 60
    // 每帧理想耗时（纳秒）
    private val frameIntervalNanos = 1_000_000_000L / targetFps

    // 音频参数（44100Hz，单声道，16 位 PCM）
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    /** 设置渲染目标 Surface（来自 SurfaceView 或 TextureView） */
    fun setSurface(surface: Surface?) {
        this.surface = surface
    }

    /** 启动模拟器线程 */
    fun startEmulator() {
        if (!running) {
            running = true
            paused = false
            start()
        }
    }

    /** 暂停渲染与音频 */
    fun pauseEmulator() {
        if (running && !paused) {
            paused = true
            core.pause()
            try {
                audioTrack?.pause()
            } catch (_: Exception) {
            }
        }
    }

    /** 恢复渲染与音频 */
    fun resumeEmulator() {
        if (running && paused) {
            paused = false
            core.run()
            try {
                audioTrack?.play()
            } catch (_: Exception) {
            }
        }
    }

    /** 停止模拟器线程 */
    fun stopEmulator() {
        running = false
        core.stop()
        interrupt()
    }

    override fun run() {
        initAudioTrack()
        core.run()
        while (running) {
            val start = System.nanoTime()
            if (!paused) {
                renderFrame()
                outputAudio()
            }
            // 帧率控制：不足一帧则休眠补齐
            val elapsed = System.nanoTime() - start
            val sleepMs = (frameIntervalNanos - elapsed) / 1_000_000
            if (sleepMs > 0) {
                try {
                    sleep(sleepMs)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        cleanup()
    }

    /**
     * 渲染一帧到 Surface
     * 将核心帧缓冲（ARGB）写入 Bitmap 并缩放绘制到画布。
     */
    private fun renderFrame() {
        val s = surface ?: return
        if (!s.isValid) return
        val frame = core.getFrameBuffer()
        if (frame.isEmpty()) return

        val width = core.getFrameWidth()
        val height = core.getFrameHeight()
        // 仅在尺寸变化时重建 Bitmap
        val current = renderBitmap
        val bmp = if (current != null && current.width == width && current.height == height) {
            current
        } else {
            current?.recycle()
            Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { renderBitmap = it }
        }
        // 将 ARGB 像素数组写入 Bitmap
        if (frame.size >= bmp.width * bmp.height) {
            bmp.setPixels(frame, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        }

        val canvas: Canvas = try {
            s.lockHardwareCanvas()
        } catch (_: Exception) {
            // 部分设备 / API 不支持硬件画布时回退到软件画布
            try {
                s.lockCanvas(null)
            } catch (_: Exception) {
                return
            }
        }
        try {
            canvas.drawColor(android.graphics.Color.BLACK)
            canvas.drawBitmap(
                bmp,
                null,
                RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()),
                null
            )
        } finally {
            try {
                s.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }

    /** 输出音频到 AudioTrack */
    private fun outputAudio() {
        val track = audioTrack ?: return
        val audio = core.getAudioBuffer()
        if (audio.isEmpty()) return
        track.write(audio, 0, audio.size)
    }

    /** 初始化 AudioTrack */
    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (minBuf <= 0) return
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(audioEncoding)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track.play()
            audioTrack = track
        } catch (_: Exception) {
            audioTrack = null
        }
    }

    /** 释放音频与位图资源 */
    private fun cleanup() {
        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
        renderBitmap?.recycle()
        renderBitmap = null
    }
}

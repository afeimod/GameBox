package com.nesstation.app.core.engine

import android.os.Handler
import android.os.Looper
import java.io.File
import javax.microedition.lcdui.Canvas
import javax.microedition.lcdui.Displayable
import javax.microedition.shell.J2meHost
import javax.microedition.shell.MicroLoader
import javax.microedition.shell.MidletThread
import javax.microedition.util.ContextHolder

/**
 * EmulatorEngine implementation for J2ME (MIDP) games.
 *
 * Unlike the libretro-based engines (NES/SNES/GBA/...), J2ME is event-driven
 * rather than frame-pumped: the MIDlet runs on its own HandlerThread
 * ([MidletThread]) and redraws by calling [Canvas.repaint]. There is no
 * native core, no continuous emulation loop, and no direct frame buffer —
 * the Canvas renders to its own internal SurfaceView.
 *
 * This engine therefore:
 *  - boots the MIDlet via [MicroLoader] with [MidletThread.embedded] = true
 *    (so exit does NOT kill the app process)
 *  - acts as the [J2meHost] that the J2ME runtime calls back into
 *    (setCurrent / getCurrent / isVisible / requestExit)
 *  - translates EmulatorScreen's gamepad bit-masks into J2ME key events
 *    ([Canvas.postKeyPressed] / [Canvas.postKeyReleased])
 *  - exposes the current [Displayable]'s Android [android.view.View] so
 *    EmulatorScreen can host it inside the Compose hierarchy
 *
 * Lifecycle (mirrors [NesEngine]):
 *  - [loadRom] boots the MIDlet and sets this engine as the J2meHost.
 *  - [setPaused] forwards to [MidletThread.pauseApp] / [resumeApp].
 *  - [unload] destroys the MIDlet WITHOUT killing the process (embedded
 *    mode). Re-entry is guarded by [isLoaded].
 *  - All other EmulatorEngine methods are no-ops or return safe defaults.
 */
class J2meEngine private constructor() : EmulatorEngine, J2meHost {

        override val frameBuffer = IntArray(0) // J2ME renders to its own SurfaceView

        @Volatile
        override var isLoaded = false
                private set

        @Volatile
        private var _paused = false
        @Volatile
        private var _midletStarted = false

        private var microLoader: MicroLoader? = null
        private var currentDisplayable: Displayable? = null
        @Volatile
        private var displayableView: android.view.View? = null
        private var previousPadBits = 0

        // EmulatorScreen button-bit → J2ME key-code mapping.
        // Bits come from EmulatorScreen.OnScreenController (BTN_* constants).
        // J2ME keys use the Canvas.KEY_* constants (negative values for
        // game-pad keys, ASCII values for numeric keys).
        private val keyMap: Map<Int, Int> = mapOf(
                0x0010 to Canvas.KEY_UP,       // BTN_UP
                0x0020 to Canvas.KEY_DOWN,     // BTN_DOWN
                0x0040 to Canvas.KEY_LEFT,     // BTN_LEFT
                0x0080 to Canvas.KEY_RIGHT,    // BTN_RIGHT
                0x0001 to Canvas.KEY_FIRE,     // BTN_A  → primary fire
                0x0002 to Canvas.KEY_SOFT_LEFT,// BTN_B  → secondary action
                0x0100 to Canvas.KEY_SOFT_RIGHT,// BTN_X → tertiary action
                0x0200 to Canvas.KEY_STAR,     // BTN_Y  → * key
                0x0004 to Canvas.KEY_POUND,    // BTN_SELECT → # key
                0x0008 to Canvas.KEY_END,      // BTN_START → menu/exit
                // Numeric keys (0-9) — used by the 12-key phone overlay layout.
                // Bits 16-25 are free (generic BTN_* constants top out at bit 15).
                0x010000 to '0'.code,          // BTN_NUM_0
                0x020000 to '1'.code,          // BTN_NUM_1
                0x040000 to '2'.code,          // BTN_NUM_2
                0x080000 to '3'.code,          // BTN_NUM_3
                0x100000 to '4'.code,          // BTN_NUM_4
                0x200000 to '5'.code,          // BTN_NUM_5
                0x400000 to '6'.code,          // BTN_NUM_6
                0x800000 to '7'.code,          // BTN_NUM_7
                0x1000000 to '8'.code,         // BTN_NUM_8
                0x2000000 to '9'.code,         // BTN_NUM_9
        )

        // 数字键兼作方向键的双派发表：真实手机上 2/4/6/8 既是数字键也是
        // 方向键，5 是确认键。游戏可能用 getGameAction() 译码（数字键自带
        // 游戏动作，KeyMapper 已映射），也可能直接比较 keyCode == KEY_UP
        // 等原始常量。只发数字键会让后者完全无法操作菜单。keyMap 已发送
        // 数字键本身，这里为同一个 bit 追加发送对应的游戏动作键，与真机
        // 行为一致。
        // 注意：bit 必须与 J2meOverlay 的 J2ME_BTN_NUM_* 严格对应 ——
        // 旧版 8→KEY_DOWN 写成了 0x8000000（bit 27，不对应任何按键），
        // 导致手机键盘模式按 8 永远不会补发方向键，已修正为 0x1000000。
        private val dualKeyMap: Map<Int, Int> = mapOf(
                0x040000 to Canvas.KEY_UP,      // 2 → 上
                0x100000 to Canvas.KEY_LEFT,    // 4 → 左
                0x400000 to Canvas.KEY_RIGHT,   // 6 → 右
                0x1000000 to Canvas.KEY_DOWN,   // 8 → 下
                0x200000 to Canvas.KEY_FIRE,    // 5 → 确认
        )

        /**
         * 数字键兼作方向键（原“手机键盘模式双派发”）：开启后虚拟键盘上的
         * 2/4/6/8/5 在发送数字键的同时追加发送 上/左/右/下/确认 键。
         * 由 UI 层根据 padLayout.javaNumDualDispatch 设置（默认开启），
         * 手柄模式的 "123" 数字小键盘与手机键盘模式同样生效。
         */
        @Volatile
        var phoneDualDispatch: Boolean = true

        /**
         * 用户自定义的 虚拟按键 bit → MIDP 键码 映射（来自设置里的
         * “按键映射”，仅包含被修改过的键位）。setPad1 时优先于内置 keyMap。
         */
        @Volatile
        private var customKeyMap: Map<Int, Int> = emptyMap()

        /**
         * 应用设置里的“按键映射”（PadLayout.javaButtonKeyMap 原始串）。
         * 传空串表示全部使用内置默认映射。
         */
        fun setCustomKeyMap(raw: String) {
                val parsed = mutableMapOf<Int, Int>()
                for (entry in raw.split(',')) {
                        val parts = entry.split('=')
                        if (parts.size != 2) continue
                        val bit = CUSTOM_BUTTON_BITS[parts[0].trim()] ?: continue
                        val code = parts[1].trim().toIntOrNull() ?: continue
                        parsed[bit] = code
                }
                customKeyMap = parsed
        }

        override fun ensureLoaded(): Boolean = true

        override fun loadRom(
                rom: File,
                systemDir: String,
                saveDir: String,
                onFrame: () -> Unit
        ): Boolean {
                // Embedded mode MUST be set before MidletThread.create() so that
                // notifyDestroyed() does not kill the app process on exit.
                MidletThread.embedded = true

                val context = ContextHolder.getAppContext()
                        ?: run { lastErr = "Application context not available"; return false }

                val loader = try {
                        MicroLoader(context, rom.absolutePath)
                } catch (e: Exception) {
                        lastErr = "MicroLoader construction failed: ${e.message}"
                        return false
                }

                if (loader.init() != true) {
                        lastErr = "MicroLoader.init() returned false"
                        return false
                }
                loader.applyConfiguration()

                // GameBox: 强制黑色屏幕底色, 与其他模拟核心(NES/SFC/GBA/...)一致。
                // 原默认 ProfileModel.screenBackgroundColor = 0xD0D0D0 浅灰色, 在
                // 游戏画面宽高比与屏幕不一致时四周出现白色边框。旧存档里的
                // 浅色配置也会被这里覆盖。
                Canvas.setBackgroundColor(0xFF000000.toInt())

                // GameBox: 嵌入式模式禁用 J2ME-Loader 的旧版虚拟键盘。
                // applyConfiguration() 会按 config.json 的 showKeyboard(默认 true)
                // 创建旧版 VirtualKeyboard：它不可见(没有 OverlayView 承载)却会
                // 1) 在 ViewCallbacks 里优先拦截物理按键；2) updateSize() 按
                // 手机布局高度压缩游戏画面。GameBox 有自己的虚拟手柄 overlay，
                // 旧版自带键盘一律不再使用。
                ContextHolder.setVk(null)

                // Register this engine as the J2ME host BEFORE loading the MIDlet,
                // so Display.setCurrent() / MidletThread.resumeApp() find us.
                ContextHolder.setCurrentActivity(this)

                val midlets = try {
                        loader.loadMIDletList()
                } catch (e: Exception) {
                        lastErr = "loadMIDletList failed: ${e.message}"
                        return false
                }

                if (midlets.isEmpty()) {
                        lastErr = "No MIDlets found in ${rom.name}"
                        return false
                }

                val mainClass = midlets.keys.first()
                MidletThread.create(loader, mainClass)
                microLoader = loader
                isLoaded = true
                _midletStarted = false
                // MidletThread.create() posts INIT to the MIDlet's HandlerThread;
                // resumeApp() sends START which requires state==PAUSED. Delay to
                // guarantee INIT has completed before START is queued.
                Handler(Looper.getMainLooper()).postDelayed({
                        MidletThread.resumeApp()
                }, 300)

                return true
        }

        override fun setSurface(surface: android.view.Surface?) {
                // J2ME renders to its own SurfaceView inside the Canvas; the
                // EmulatorScreen Surface is not used.
        }

        override fun setSaveName(name: String) {}
        override fun setCoreOption(key: String, value: String) {}

        override fun videoWidth(): Int =
                (currentDisplayable as? Canvas)?.width ?: 0

        override fun videoHeight(): Int =
                (currentDisplayable as? Canvas)?.height ?: 0

        override fun setVideoFilter(filter: Int) = Canvas.setJ2meFilterMode(filter)
        override fun setHighQualityScaling(enabled: Boolean) {}
        override fun setFastForward(speed: Int) {}

        override fun setPaused(paused: Boolean) {
                _paused = paused
                if (paused) {
                        MidletThread.pauseApp()
                } else if (_midletStarted) {
                        // Subsequent resume after an explicit pause — call directly.
                        // The very first resume is handled by loadRom()'s delayed
                        // postDelayed(resumeApp) to guarantee MIDlet INIT has completed.
                        MidletThread.resumeApp()
                }
                // Track that the MIDlet has been started at least once.
                if (!_paused) _midletStarted = true
        }

        override fun reset(hard: Boolean) {
                // Restart the MIDlet by unloading and reloading.
                val loader = microLoader ?: return
                val romPath = loader.javaClass.declaredFields
                        .find { it.name == "appDir" }
                        ?.apply { isAccessible = true }
                        ?.get(loader) as? File
                        ?: return
                unload()
                // Re-use loadRom with the same path.
                loadRom(romPath, "", "") {}
        }

        override fun unload() {
                // Guard against re-entry: MidletThread.notifyDestroyed() calls
                // host.requestExit() → this.unload() after the MIDlet has already
                // been destroyed. The isLoaded flag prevents the double-unload.
                if (!isLoaded) return
                isLoaded = false

                currentDisplayable = null
                displayableView = null
                previousPadBits = 0
                _midletStarted = false

                // Ask the MIDlet to destroy itself. This posts a DESTROY message
                // to MidletThread; when it completes, notifyDestroyed() will call
                // requestExit() → this.unload() again, which returns immediately
                // because isLoaded is already false.
                // embedded stays true so notifyDestroyed() does NOT kill the
                // process — the app returns to the game library instead.
                MidletThread.destroyApp()
                microLoader = null
        }

        override fun shutdown() = unload()

        /**
         * Translate EmulatorScreen gamepad bit-mask into J2ME key events.
         *
         * The EmulatorScreen overlay pushes a snapshot of all button states
         * via setPad1(bits). We diff against the previous snapshot and post
         * keyPressed / keyReleased for each button whose state changed, so
         * the MIDlet sees proper press/release pairs rather than a stream of
         * repeated presses.
         */
        override fun setPad1(bits: Int) {
                val canvas = currentDisplayable as? Canvas ?: return
                val prev = previousPadBits
                previousPadBits = bits
                val custom = customKeyMap

                for ((bit, defaultKey) in keyMap) {
                        val keyCode = custom[bit] ?: defaultKey
                        val wasPressed = (prev and bit) != 0
                        val isPressed = (bits and bit) != 0
                        if (wasPressed != isPressed) {
                                if (isPressed) {
                                        canvas.postKeyPressed(keyCode)
                                } else {
                                        canvas.postKeyReleased(keyCode)
                                }
                        }
                }

                // 数字键兼作方向键：2/4/6/8/5 追加派发方向/确认键（真机行为），
                // 由 javaNumDualDispatch 设置控制，两种输入模式都生效。
                if (phoneDualDispatch) {
                        for ((bit, keyCode) in dualKeyMap) {
                                val wasPressed = (prev and bit) != 0
                                val isPressed = (bits and bit) != 0
                                if (wasPressed != isPressed) {
                                        if (isPressed) {
                                                canvas.postKeyPressed(keyCode)
                                        } else {
                                                canvas.postKeyReleased(keyCode)
                                        }
                                }
                        }
                }
        }

        override fun setPad2(bits: Int) {} // J2ME is single-player

        // ─── FPS 统计 ────────────────────────────────────────────────────────

        private var lastFpsSampleNanos = 0L

        /**
         * GameBox: 实时帧率 —— 修复 Java 游戏全局 FPS 悬浮窗一直显示 0fps。
         *
         * J2ME 是事件驱动渲染（MIDlet 主动 repaint，无 libretro 式 onFrame
         * 回调），帧计数走 javax.microedition.lcdui.Canvas 的全局帧计数器：
         * 三条绘制路径（CPU repaintScreen / View onDraw / GL 渲染线程）每向
         * 屏幕提交一帧都会 +1。宿主 EmulatorScreen 每约 1 秒采样一次，本方法
         * 读取并清零计数器，按实际采样间隔换算成帧率。
         *
         * 注意：游戏停在静态画面（菜单/过场）不 repaint 时帧率为 0，属正常。
         */
        override fun realtimeFps(): Double {
                val frames = try {
                        Canvas.getAndResetFrameCount()
                } catch (_: Throwable) {
                        0L
                }
                val now = System.nanoTime()
                val last = lastFpsSampleNanos
                lastFpsSampleNanos = now
                val dtNanos = if (last == 0L) 1_000_000_000.0 else (now - last).toDouble()
                if (dtNanos <= 0.0) return 0.0
                return frames * 1_000_000_000.0 / dtNanos
        }

        override var frameHook: NetplayHook? = null
        override fun setRegion(region: Int) {}
        override fun setSampleRate(rate: Int) {}
        override fun saveState(slot: Int, dst: File): Boolean = false
        override fun loadState(slot: Int, src: File): Boolean = false
        override fun captureFrame(): FrameCapture? = null
        override fun lastError(): String = lastErr

        // ─── J2meHost implementation ────────────────────────────────────────

        /**
         * Called by [javax.microedition.lcdui.Display.setCurrent] whenever the
         * MIDlet switches its active screen (Canvas → Form → Alert → …).
         *
         * The Displayable's Android [android.view.View] is created lazily by
         * [Displayable.getDisplayableView] and MUST be built on the UI thread
         * (it constructs LinearLayout / SurfaceView / …). We post the view
         * creation to the main looper and store the result for EmulatorScreen
         * to host.
         */
        override fun setCurrent(displayable: Displayable) {
                currentDisplayable = displayable
                Handler(Looper.getMainLooper()).post {
                        try {
                                displayableView = displayable.getDisplayableView()
                        } catch (e: Exception) {
                                lastErr = "getDisplayableView failed: ${e.message}"
                                displayableView = null
                        }
                }
        }

        override fun getCurrent(): Displayable? = currentDisplayable

        override fun isVisible(): Boolean = isLoaded && !_paused

        /**
         * Called by [MidletThread.notifyDestroyed] when the MIDlet exits.
         * In embedded mode this unloads the engine and returns control to the
         * game library WITHOUT killing the process.
         */
        override fun requestExit() = unload()

        /**
         * @return the Android [android.view.View] for the currently displayed
         * [Displayable], or null. EmulatorScreen hosts this view inside its
         * Compose hierarchy via AndroidView.
         */
        fun getDisplayableView(): android.view.View? = displayableView

        /**
         * 应用 GameBox 设置的虚拟分辨率（游戏内 Canvas 的逻辑分辨率）。
         * javaResolution == "default" 时不干预（保留每游戏 config.json 的值）；
         * "auto" 表示跟随设备屏幕（setVirtualSize(-1, -1)）；
         * 其他值形如 "240x320"，强制指定逻辑分辨率。
         * 调用后立即刷新当前 Canvas，让设置即时生效。
         *
         * @return true 表示应用了非默认分辨率
         */
        fun applyVirtualResolution(resolution: String): Boolean {
                when (resolution) {
                        "default", "" -> return false
                        "auto" -> Displayable.setVirtualSize(-1, -1)
                        else -> {
                                val parts = resolution.lowercase().split('x')
                                val w = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return false
                                val h = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return false
                                if (w <= 0 || h <= 0) return false
                                Displayable.setVirtualSize(w, h)
                        }
                }
                // 已显示的 Canvas 立即重算尺寸，无需等待游戏切换屏幕。
                (currentDisplayable as? Canvas)?.updateSize()
                return true
        }

        private var lastErr: String = ""

        companion object {
                @Volatile
                private var instance: J2meEngine? = null

                /** 设置里的按键映射 id → EmulatorScreen 按钮 bit。 */
                internal val CUSTOM_BUTTON_BITS: Map<String, Int> = mapOf(
                        "up" to 0x0010, "down" to 0x0020,
                        "left" to 0x0040, "right" to 0x0080,
                        "a" to 0x0001, "b" to 0x0002,
                        "x" to 0x0100, "y" to 0x0200,
                        "start" to 0x0008, "select" to 0x0004,
                )

                fun get(): J2meEngine = instance ?: synchronized(this) {
                        instance ?: J2meEngine().also { instance = it }
                }
        }
}

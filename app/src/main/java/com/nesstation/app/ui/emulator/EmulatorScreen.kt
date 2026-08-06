package com.nesstation.app.ui.emulator

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.os.Environment
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nesstation.app.core.engine.EmulatorEngine
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.ButtonLayout
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.storage.PadLayoutStore
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Button types for multi-touch tracking
// ---------------------------------------------------------------------------
private enum class BtnType { DPAD, A, B, TURBO_A, TURBO_B, START, SELECT, L, R, X, Y }

// Bit masks for NES/SNES/GBA controller
// NES/GB/GBC: A B SEL STA U D L R (8 buttons)
// GBA: adds L(bit8) R(bit9) (10 buttons)
// SNES: adds X(bit8) Y(bit9) L(bit10) R(bit11) (12 buttons)
private const val BTN_UP = 0x10
private const val BTN_DOWN = 0x20
private const val BTN_LEFT = 0x40
private const val BTN_RIGHT = 0x80
private const val BTN_A = 0x01
private const val BTN_B = 0x02
private const val BTN_SELECT = 0x04
private const val BTN_START = 0x08
private const val BTN_X = 0x100       // bit8 — SNES X
private const val BTN_Y = 0x200       // bit9 — SNES Y (or GBA L)
private const val BTN_L_SNES = 0x400  // bit10 — SNES L
private const val BTN_R_SNES = 0x800  // bit11 — SNES R
private const val BTN_L_GBA = 0x100   // bit8 — GBA L
private const val BTN_R_GBA = 0x200   // bit9 — GBA R

// ---------------------------------------------------------------------------
// Main Emulator Screen
// ---------------------------------------------------------------------------
@Composable
fun EmulatorScreen(
    game: GameEntry,
    onExit: () -> Unit
) {
    val engine = remember { EmulatorEngine.forPlatform(game.platform) }
    val platform = game.platform
    val context = LocalContext.current
    var running by remember { mutableStateOf(true) }
    var fastForward by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    var showMenu by remember { mutableStateOf(false) }
    var showLayoutEditor by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var saveLoadSlot by remember { mutableStateOf(0) } // 0-9 state slots
    var showSlotPicker by remember { mutableStateOf<String?>(null) } // "save" | "load" | null

    var padLayout by remember { mutableStateOf(PadLayoutStore.load(context)) }

    // Apply core options on load and when they change
    LaunchedEffect(padLayout.ntscFilter, padLayout.palette,
                   padLayout.region, padLayout.cropOverscan,
                   padLayout.videoFilter, padLayout.overclocking,
                   padLayout.aspectRatio,
                   padLayout.sfcReduceSpriteFlicker, padLayout.sfcReduceSlowdown,
                   padLayout.sfcAudioInterpolation, padLayout.sfcGfxTransparency,
                   padLayout.sfcGfxHires, padLayout.sfcUpDownAllowed,
                   padLayout.sfcLayer1, padLayout.sfcLayer2, padLayout.sfcLayer3,
                   padLayout.sfcLayer4, padLayout.sfcLayer5,
                   padLayout.gbcColorPreset, padLayout.gbaColorPreset,
                   padLayout.gbaAudioResampler, padLayout.gbaAudioLowPass,
                   padLayout.gbaFrameskipType, padLayout.gbaForceRTC,
                   padLayout.gbaAllowOpposite) {
        applyCoreOptions(engine, padLayout, platform)
        // Apply video filter (frontend post-processing, not a core option)
        val filterInt = when (padLayout.videoFilter) {
            "scanline" -> 1
            "crt" -> 2
            "dot" -> 3
            "xbr" -> 4
            "hq2x" -> 5
            "hq4x" -> 6
            "xbr_dot" -> 7
            "4xbr" -> 8
            "4xbr_dot" -> 9
            "hq4x_dot" -> 10
            else -> 0
        }
        engine.setVideoFilter(filterInt)
    }

    BackHandler(enabled = !showMenu && !showLayoutEditor && !showSettings) {
        showMenu = true
    }
    BackHandler(enabled = showMenu && !showLayoutEditor && !showSettings) {
        showMenu = false
    }
    BackHandler(enabled = showLayoutEditor) { showLayoutEditor = false }
    BackHandler(enabled = showSettings) { showSettings = false }

    // Load ROM
    LaunchedEffect(game) {
        val romPath = game.romPath
        if (romPath.isNullOrEmpty()) {
            errorMsg = "该游戏未关联 ROM 文件"
            return@LaunchedEffect
        }
        val romFile = java.io.File(romPath)
        if (romFile.exists()) {
            // FDS BIOS is auto-extracted from assets by NesApp on startup.
            // If missing, the core will report the error; user can import via Settings.
            val filesDir = context.filesDir.absolutePath
            val ok = engine.loadRom(romFile, filesDir, filesDir) { }
            if (!ok) {
                val err = engine.lastError()
                errorMsg = err.ifEmpty { "ROM 加载失败" }
            } else {
                loaded = true
            }
        } else {
            try {
                val input = context.contentResolver.openInputStream(android.net.Uri.parse(romPath))
                if (input != null) {
                    val origName = game.title.ifBlank { romPath.substringAfterLast('/') }
                    val ext = when {
                        origName.endsWith(".fds", ignoreCase = true) -> ".fds"
                        origName.endsWith(".unf", ignoreCase = true) || origName.endsWith(".unif", ignoreCase = true) -> ".unf"
                        origName.endsWith(".smc", ignoreCase = true) -> ".smc"
                        origName.endsWith(".sfc", ignoreCase = true) -> ".sfc"
                        origName.endsWith(".swc", ignoreCase = true) -> ".swc"
                        origName.endsWith(".fig", ignoreCase = true) -> ".fig"
                        origName.endsWith(".gbc", ignoreCase = true) -> ".gbc"
                        origName.endsWith(".gba", ignoreCase = true) -> ".gba"
                        origName.endsWith(".gb", ignoreCase = true) -> ".gb"
                        origName.endsWith(".sgb", ignoreCase = true) -> ".sgb"
                        romPath.contains(".fds", ignoreCase = true) -> ".fds"
                        romPath.contains(".unf", ignoreCase = true) -> ".unf"
                        romPath.contains(".sfc", ignoreCase = true) -> ".sfc"
                        romPath.contains(".smc", ignoreCase = true) -> ".smc"
                        romPath.contains(".gba", ignoreCase = true) -> ".gba"
                        romPath.contains(".gbc", ignoreCase = true) -> ".gbc"
                        romPath.contains(".gb", ignoreCase = true) -> ".gb"
                        else -> ".nes"
                    }
                    val tempFile = java.io.File(context.cacheDir, "temp_rom$ext")
                    tempFile.outputStream().use { out -> input.copyTo(out) }
                    input.close()
                    val filesDir = context.filesDir.absolutePath
                    val ok = engine.loadRom(tempFile, filesDir, filesDir) { }
                    if (!ok) {
                        val err = engine.lastError()
                        errorMsg = err.ifEmpty { "ROM 加载失败" }
                    } else {
                        loaded = true
                    }
                } else {
                    errorMsg = "无法读取ROM文件: $romPath"
                }
            } catch (e: Exception) {
                errorMsg = "ROM 加载失败: ${e.message}"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { engine.unload() }
    }

    LaunchedEffect(fastForward) { engine.setFastForward(fastForward) }
    LaunchedEffect(running) { engine.setPaused(!running) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (loaded) {
            GameSurfaceView(
                engine = engine,
                videoScale = padLayout.videoScale,
                videoFilter = padLayout.videoFilter,
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { surfaceSize = it }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = errorMsg ?: "正在加载…",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }

        // On-screen controller with multi-touch
        if (loaded && padLayout.showPad && !showMenu && !showLayoutEditor && !showSettings && surfaceSize != IntSize.Zero) {
            OnScreenController(
                padLayout = padLayout,
                surfaceSize = surfaceSize,
                platform = platform,
                onPadBits = { bits -> engine.setPad1(bits) }
            )
        }

        if (loaded && showMenu && !showLayoutEditor && !showSettings) {
            MenuOverlay(
                gameTitle = game.title,
                running = running,
                fastForward = fastForward,
                currentSlot = saveLoadSlot,
                onTogglePause = { running = !running },
                onToggleFastForward = { fastForward = !fastForward },
                onScreenshot = {
                    val capture = engine.captureFrame()
                    if (capture != null) {
                        try {
                            val bitmap = Bitmap.createBitmap(
                                capture.pixels, capture.width, capture.height, Bitmap.Config.ARGB_8888
                            )
                            val screenshotsDir = java.io.File(
                                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                                    ?: context.filesDir,
                                "screenshots"
                            )
                            screenshotsDir.mkdirs()
                            val timestamp = java.text.SimpleDateFormat(
                                "yyyyMMdd_HHmmss", java.util.Locale.getDefault()
                            ).format(java.util.Date())
                            val safeTitle = game.title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            val file = java.io.File(screenshotsDir, "${safeTitle}_${timestamp}.png")
                            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            Toast.makeText(context, "截图已保存: ${file.name}", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "截图失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(context, "截图失败：无画面数据", Toast.LENGTH_SHORT).show()
                    }
                },
                onSaveState = { showSlotPicker = "save" },
                onLoadState = { showSlotPicker = "load" },
                onReset = {
                    engine.reset(hard = false)
                    Toast.makeText(context, "已重置", Toast.LENGTH_SHORT).show()
                },
                onLayoutEditor = { showLayoutEditor = true },
                onSettings = { showSettings = true },
                onClose = { showMenu = false },
                onExit = { onExit() }
            )
        }

        // State slot picker dialog
        if (showSlotPicker != null) {
            SlotPickerDialog(
                mode = showSlotPicker!!,
                currentSlot = saveLoadSlot,
                onSlotSelected = { slot ->
                    val saveDir = java.io.File(context.filesDir, "saves")
                    saveDir.mkdirs()
                    val stateFile = java.io.File(saveDir, "${game.id}_slot${slot}.state")
                    if (showSlotPicker == "save") {
                        try {
                            engine.saveState(slot, stateFile)
                            Toast.makeText(context, "存档已保存 [槽位 $slot]", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "存档失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        if (stateFile.exists()) {
                            try {
                                engine.loadState(slot, stateFile)
                                Toast.makeText(context, "存档已读取 [槽位 $slot]", Toast.LENGTH_SHORT).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "读档失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "槽位 $slot 无存档", Toast.LENGTH_SHORT).show()
                        }
                    }
                    saveLoadSlot = slot
                    showSlotPicker = null
                },
                onDismiss = { showSlotPicker = null }
            )
        }

        if (loaded && showLayoutEditor) {
            PadLayoutEditor(
                padLayout = padLayout,
                platform = platform,
                onLayoutChange = { newLayout ->
                    padLayout = newLayout
                    PadLayoutStore.save(context, newLayout)
                },
                surfaceSize = surfaceSize,
                onClose = { showLayoutEditor = false }
            )
        }

        if (loaded && showSettings) {
            SettingsPanel(
                padLayout = padLayout,
                platform = platform,
                onLayoutChange = { newLayout ->
                    padLayout = newLayout
                    PadLayoutStore.save(context, newLayout)
                    applyCoreOptions(engine, newLayout, platform)
                },
                onClose = { showSettings = false }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Apply core options to engine — platform-aware option mapping
// ---------------------------------------------------------------------------
private fun applyCoreOptions(engine: EmulatorEngine, layout: PadLayout, platform: GamePlatform = GamePlatform.NES) {
    when (platform) {
        GamePlatform.NES -> {
            engine.setCoreOption("fceumm_ntsc_filter", layout.ntscFilter)
            engine.setCoreOption("fceumm_palette", layout.palette)
            engine.setCoreOption("fceumm_region", layout.region)
            val cropVal = if (layout.cropOverscan == "enabled") "8" else "0"
            engine.setCoreOption("fceumm_overscan_h_left", cropVal)
            engine.setCoreOption("fceumm_overscan_h_right", cropVal)
            engine.setCoreOption("fceumm_overscan_v_top", cropVal)
            engine.setCoreOption("fceumm_overscan_v_bottom", cropVal)
            engine.setCoreOption("fceumm_overclocking", layout.overclocking)
        }
        GamePlatform.SFC -> {
            // SNES9x aspect ratio values: "4:3" | "uncorrected" | "auto" | "ntsc" | "pal"
            // The dropdown already provides these values directly.
            engine.setCoreOption("snes9x_aspect", layout.aspectRatio)
            engine.setCoreOption("snes9x_overclock", layout.sfcOverclock)
            engine.setCoreOption("snes9x_blargg", layout.ntscFilter)
            engine.setCoreOption("snes9x_overscan", layout.sfcOverscan)
            engine.setCoreOption("snes9x_up_down_allowed", layout.sfcUpDownAllowed)
            engine.setCoreOption("snes9x_reduce_sprite_flicker", layout.sfcReduceSpriteFlicker)
            engine.setCoreOption("snes9x_overclock_cycles", layout.sfcReduceSlowdown)
            engine.setCoreOption("snes9x_audio_interpolation", layout.sfcAudioInterpolation)
            engine.setCoreOption("snes9x_gfx_transp", layout.sfcGfxTransparency)
            engine.setCoreOption("snes9x_gfx_hires", layout.sfcGfxHires)
            engine.setCoreOption("snes9x_gfx_clip", layout.sfcGfxClip)
            engine.setCoreOption("snes9x_block_invalid_vram_access", "enabled")
            engine.setCoreOption("snes9x_hires_blend", layout.sfcSideBySide)
            engine.setCoreOption("snes9x_echo_buffer_hack", layout.sfcSoundOutput)
            engine.setCoreOption("snes9x_randomize_memory", layout.sfcSuperScope)
            engine.setCoreOption("snes9x_region", "auto")
            engine.setCoreOption("snes9x_layer_1", layout.sfcLayer1)
            engine.setCoreOption("snes9x_layer_2", layout.sfcLayer2)
            engine.setCoreOption("snes9x_layer_3", layout.sfcLayer3)
            engine.setCoreOption("snes9x_layer_4", layout.sfcLayer4)
            engine.setCoreOption("snes9x_layer_5", layout.sfcLayer5)
            // Sound channels (8 individual channels)
            engine.setCoreOption("snes9x_sndchan_1", "enabled")
            engine.setCoreOption("snes9x_sndchan_2", "enabled")
            engine.setCoreOption("snes9x_sndchan_3", "enabled")
            engine.setCoreOption("snes9x_sndchan_4", "enabled")
            engine.setCoreOption("snes9x_sndchan_5", "enabled")
            engine.setCoreOption("snes9x_sndchan_6", "enabled")
            engine.setCoreOption("snes9x_sndchan_7", "enabled")
            engine.setCoreOption("snes9x_sndchan_8", "enabled")
        }
        GamePlatform.GB, GamePlatform.GBA -> {
            engine.setCoreOption("mgba_gb_model", layout.gbModel)
            engine.setCoreOption("mgba_gb_colors", layout.gbColorCorrection)
            engine.setCoreOption("mgba_gb_colors_preset", layout.gbcColorPreset)
            engine.setCoreOption("mgba_gba_colors", layout.gbaColorCorrection)
            engine.setCoreOption("mgba_gba_colors_preset", layout.gbaColorPreset)
            engine.setCoreOption("mgba_interframe_blending", layout.gbaFrameBlending)
            engine.setCoreOption("mgba_solar_sensor_level", layout.gbaSolarSensor)
            engine.setCoreOption("mgba_frameskip", layout.gbaFrameskipCount)
            engine.setCoreOption("mgba_frameskip_type", layout.gbaFrameskipType)
            engine.setCoreOption("mgba_frameskip_threshold", layout.gbaFrameskipThreshold)
            engine.setCoreOption("mgba_audio_resampler", layout.gbaAudioResampler)
            engine.setCoreOption("mgba_audio_low_pass_filter", layout.gbaAudioLowPass)
            engine.setCoreOption("mgba_audio_low_pass_range", layout.gbaAudioLowPassRange)
            engine.setCoreOption("mgba_sgb_borders", layout.gbSgbBorders)
            engine.setCoreOption("mgba_gba_forceRTC", layout.gbaForceRTC)
            engine.setCoreOption("mgba_allow_opposite_directions", layout.gbaAllowOpposite)
            if (platform == GamePlatform.GBA) {
                engine.setCoreOption("mgba_gba_idle_optimization", layout.gbaIdleOptimization)
            }
        }
        GamePlatform.JAVA -> { /* no core options for J2ME */ }
    }
}

// ---------------------------------------------------------------------------
// GameSurfaceView
// ---------------------------------------------------------------------------
@Composable
private fun GameSurfaceView(
    engine: EmulatorEngine,
    videoScale: String,
    videoFilter: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val surfaceModifier = when (videoScale) {
            "4:3" -> Modifier.aspectRatio(4f / 3f)
            "8:7" -> Modifier.aspectRatio(8f / 7f)
            "16:9" -> Modifier.aspectRatio(16f / 9f)
            else -> Modifier.fillMaxSize() // stretch (default)
        }
        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            engine.setSurface(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.setSurface(null)
                        }
                    })
                    holder.setFormat(android.graphics.PixelFormat.RGBX_8888)
                }
            },
            modifier = surfaceModifier
        )
        // GPU-accelerated filter overlay — scanline/CRT/dot/*+dot drawn by Compose
        if (videoFilter in listOf("scanline", "crt", "dot", "xbr_dot", "4xbr_dot", "hq4x_dot")) {
            FilterOverlay(
                if (videoFilter.endsWith("_dot")) "dot" else videoFilter,
                surfaceModifier
            )
        }
    }
}

// GPU-accelerated filter overlay using BitmapShader — a single GPU texture
// draw instead of hundreds of individual drawLine calls.
// The pattern bitmap is small (1x3 or 3x3) and tiled via REPEAT mode.
@Composable
private fun FilterOverlay(
    filterType: String,
    modifier: Modifier = Modifier
) {
    // Pre-create the pattern bitmap once per filter type
    val patternBitmap = remember(filterType) {
        when (filterType) {
            "scanline" -> createScanlinePattern()
            "crt" -> createCrtPattern()
            "dot" -> createDotPattern()
            else -> null
        }
    }

    Canvas(modifier = modifier) {
        patternBitmap?.let { bmp ->
            drawIntoCanvas { canvas ->
                val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                val paint = android.graphics.Paint().apply { this.shader = shader }
                canvas.nativeCanvas.drawRect(0f, 0f, size.width, size.height, paint)
            }
        }
        // CRT vignette — radial gradient darkening at edges
        if (filterType == "crt") {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = minOf(size.width, size.height) * 0.7f
                )
            )
        }
    }
}

// Scanline pattern: 2px wide, 4px tall — 3 transparent rows + 1 dark row.
// The 4px height matches roughly 1 NES scanline on a 1080p display, giving
// authentic CRT scanline spacing. 55% black is clearly visible.
private fun createScanlinePattern(): Bitmap {
    val bmp = Bitmap.createBitmap(2, 4, Bitmap.Config.ARGB_8888)
    for (x in 0..1) {
        bmp.setPixel(x, 0, 0x00000000)
        bmp.setPixel(x, 1, 0x00000000)
        bmp.setPixel(x, 2, 0x00000000)
        bmp.setPixel(x, 3, 0x8C000000L.toInt()) // 55% black
    }
    return bmp
}

// CRT pattern: 3px wide (RGB subpixel triads), 6px tall — 5 clear rows + 1
// scanline row. Each column has a colour tint simulating phosphor separation
// (RGB shadow mask), and the scanline row is 50% black.
// Based on RetroArch's crt-geom shader concept — visible phosphor tints and
// scanlines that mimic a real CRT monitor.
private fun createCrtPattern(): Bitmap {
    val bmp = Bitmap.createBitmap(3, 6, Bitmap.Config.ARGB_8888)
    for (y in 0..4) {
        // Phosphor tint: R column red, G column green, B column blue
        // 15% opacity — clearly visible colour separation
        bmp.setPixel(0, y, 0x26FF0000) // red phosphor
        bmp.setPixel(1, y, 0x2600FF00) // green phosphor
        bmp.setPixel(2, y, 0x260000FF) // blue phosphor
    }
    // Scanline row — darker across all subpixels
    for (x in 0..2) {
        bmp.setPixel(x, 5, 0x80000000L.toInt()) // 50% black scanline
    }
    return bmp
}

// Dot pattern: LCD dot matrix using smoothstep distance field.
// Based on RetroArch's dot.glsl shader by Themaister. Each 4x4 cell has a
// circular transparent dot in the centre with a smooth alpha gradient toward
// the edges, simulating a real LCD panel (like GameBoy DMG or NES-style LCD).
//
// Key differences from the previous broken version:
//   1. Uses smoothstep() for continuous alpha — no visible banding/squares
//   2. Larger dot radius (1.0 vs 0.7) — dots are clearly visible
//   3. Lower max darkness (50% vs 80%) — screen stays bright and readable
//   4. 4x4 cell — better dot separation than 5x5
private fun createDotPattern(): Bitmap {
    val size = 4
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val center = (size - 1) / 2.0f  // 1.5
    val dotRadius = 1.0f             // radius of the transparent dot centre
    val maxDist = kotlin.math.sqrt(center * center + center * center) // ~2.12

    for (y in 0 until size) {
        for (x in 0 until size) {
            val dx = x - center
            val dy = y - center
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)

            // Smoothstep alpha: 0 (transparent) at dot centre → 128 (50% dark) at corners
            // This produces smooth circular dots, NOT a square grid.
            val t = ((dist - dotRadius) / (maxDist - dotRadius)).coerceIn(0f, 1f)
            val smoothT = t * t * (3 - 2 * t)  // smoothstep
            val alpha = (smoothT * 128f).toInt().coerceIn(0, 255)

            // ARGB: alpha + black (0xRRGGBB = 0x000000)
            bmp.setPixel(x, y, (alpha shl 24))
        }
    }
    return bmp
}

// ---------------------------------------------------------------------------
// OnScreenController — SINGLE pointerInput for true multi-touch
// ---------------------------------------------------------------------------
@Composable
private fun OnScreenController(
    padLayout: PadLayout,
    surfaceSize: IntSize,
    onPadBits: (Int) -> Unit,
    platform: GamePlatform = GamePlatform.NES
) {
    val density = LocalDensity.current
    val opacity = padLayout.opacity

    // Which extra buttons to show based on platform
    val showLR = platform == GamePlatform.GBA || platform == GamePlatform.SFC
    val showXY = platform == GamePlatform.SFC

    // L/R bit values differ between GBA (bit8/9) and SNES (bit10/11)
    val lBit = if (platform == GamePlatform.SFC) BTN_L_SNES else BTN_L_GBA
    val rBit = if (platform == GamePlatform.SFC) BTN_R_SNES else BTN_R_GBA

    // Compute button hit-areas in pixels
    fun btnRect(layout: ButtonLayout, widthScale: Float = 1f, heightScale: Float = 1f): androidx.compose.ui.geometry.Rect {
        val sizePx = with(density) { layout.sizeDp.dp.toPx() }
        val w = sizePx * widthScale
        val h = sizePx * heightScale
        val cx = surfaceSize.width * layout.x
        val cy = surfaceSize.height * layout.y
        return androidx.compose.ui.geometry.Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    // Track active pointers: pointerId -> (BtnType, direction bits for dpad)
    val activePointers = remember { mutableMapOf<Long, Pair<BtnType, Int>>() }
    var visualState by remember { mutableStateOf(0) } // bits for drawing pressed state
    var turboState by remember { mutableStateOf(0) }  // turbo hold bits

    // Send button state to engine immediately on change (zero-latency input).
    // The LaunchedEffect loop below maintains state at 60fps for turbo and
    // held buttons, but this ensures D-pad moves and button presses feel
    // instant with no 16ms frame delay.
    val sendStateNow = remember {
        { vs: Int, ts: Int ->
            if (ts != 0) {
                // Turbo active: send combined state immediately so D-pad
                // changes are instant, turbo cycling continues in the loop.
                onPadBits(vs or ts)
            } else {
                onPadBits(vs)
            }
        }
    }

    // Turbo auto-fire: simulates rapid short taps (press 2 frames, release 4 frames)
    // FC turbo buttons rapidly press/release the A/B button at ~10Hz.
    // Also maintains held-button state at 60fps for the emulation core.
    var turboCounter by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            if (turboState != 0) {
                turboCounter++
                // 6-frame cycle: 2 frames ON, 4 frames OFF = ~10Hz rapid tap
                val turboOn = turboCounter % 6 < 2
                val effective = if (turboOn) visualState or turboState else visualState
                onPadBits(effective)
            } else {
                onPadBits(visualState)
            }
            delay(16)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(padLayout, surfaceSize) {
                // Compute hit areas once (recomputed when key changes)
                val dpadRect = btnRect(padLayout.dpad)
                val aRect = btnRect(padLayout.btnA)
                val bRect = btnRect(padLayout.btnB)
                // Turbo A/B hit areas only for non-SNES platforms
                val taRect = if (!showXY) btnRect(padLayout.btnTurboA) else null
                val tbRect = if (!showXY) btnRect(padLayout.btnTurboB) else null
                val startRect = btnRect(padLayout.btnStart, 2.2f, 0.7f)
                val selectRect = btnRect(padLayout.btnSelect, 2.2f, 0.7f)
                val lRect = if (showLR) btnRect(padLayout.btnL, 1.6f, 0.7f) else null
                val rRect = if (showLR) btnRect(padLayout.btnR, 1.6f, 0.7f) else null
                val xRect = if (showXY) btnRect(padLayout.btnX) else null
                val yRect = if (showXY) btnRect(padLayout.btnY) else null

                // Process a pointer DOWN at the given position.
                // Returns true if the pointer landed on a button.
                fun processDown(pid: Long, pos: Offset) {
                    val btnType = when {
                        dpadRect.contains(pos) -> BtnType.DPAD
                        aRect.contains(pos) -> BtnType.A
                        bRect.contains(pos) -> BtnType.B
                        taRect?.contains(pos) == true -> BtnType.TURBO_A
                        tbRect?.contains(pos) == true -> BtnType.TURBO_B
                        startRect.contains(pos) -> BtnType.START
                        selectRect.contains(pos) -> BtnType.SELECT
                        lRect?.contains(pos) == true -> BtnType.L
                        rRect?.contains(pos) == true -> BtnType.R
                        xRect?.contains(pos) == true -> BtnType.X
                        yRect?.contains(pos) == true -> BtnType.Y
                        else -> null
                    }
                    if (btnType != null) {
                        var bits = 0
                        var turboBits = 0
                        when (btnType) {
                            BtnType.DPAD -> bits = computeDpadDirection(pos, dpadRect)
                            BtnType.A -> bits = BTN_A
                            BtnType.B -> bits = BTN_B
                            BtnType.TURBO_A -> turboBits = BTN_A
                            BtnType.TURBO_B -> turboBits = BTN_B
                            BtnType.START -> bits = BTN_START
                            BtnType.SELECT -> bits = BTN_SELECT
                            BtnType.L -> bits = lBit
                            BtnType.R -> bits = rBit
                            BtnType.X -> bits = BTN_X
                            BtnType.Y -> bits = BTN_Y
                        }
                        activePointers[pid] = btnType to (if (turboBits != 0) turboBits else bits)
                        if (turboBits != 0) {
                            turboState = turboState or turboBits
                            sendStateNow(visualState, turboState)
                        } else {
                            visualState = visualState or bits
                            sendStateNow(visualState, turboState)
                        }
                    }
                }

                // Main gesture loop:
                // awaitFirstDown() and awaitPointerEvent() are members of
                // AwaitPointerEventScope, so we wrap everything in
                // awaitPointerEventScope { }. This captures the first finger
                // DOWN immediately (single-touch works) and then processes all
                // subsequent events (multi-touch, moves, ups) in the inner loop.
                awaitPointerEventScope {
                    while (true) {
                        val firstDown = awaitFirstDown(requireUnconsumed = false)
                        processDown(firstDown.id.value, firstDown.position)

                        var pressedCount = 1 // firstDown gave us one pressed finger

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)

                            for (change in event.changes) {
                                val pid = change.id.value

                                if (change.changedToDown()) {
                                    pressedCount++
                                    processDown(pid, change.position)
                                } else if (change.changedToUp()) {
                                    pressedCount--
                                    val entry = activePointers.remove(pid)
                                    if (entry != null) {
                                        val (bt, heldBits) = entry
                                        when (bt) {
                                            BtnType.DPAD, BtnType.A, BtnType.B,
                                            BtnType.START, BtnType.SELECT,
                                            BtnType.L, BtnType.R,
                                            BtnType.X, BtnType.Y -> {
                                                visualState = visualState and heldBits.inv()
                                                sendStateNow(visualState, turboState)
                                            }
                                            BtnType.TURBO_A, BtnType.TURBO_B -> {
                                                turboState = turboState and heldBits.inv()
                                                sendStateNow(visualState, turboState)
                                            }
                                        }
                                    }
                                } else if (change.positionChanged()) {
                                    val entry = activePointers[pid]
                                    if (entry != null && entry.first == BtnType.DPAD) {
                                        val oldBits = entry.second
                                        visualState = visualState and oldBits.inv()
                                        val newBits = computeDpadDirection(change.position, dpadRect)
                                        visualState = visualState or newBits
                                        activePointers[pid] = BtnType.DPAD to newBits
                                        sendStateNow(visualState, turboState)
                                    }
                                }
                            }

                            if (pressedCount <= 0) break
                        }
                    }
                }
            }
    ) {
        // Draw D-pad
        DpadCanvas(
            layout = padLayout.dpad,
            surfaceSize = surfaceSize,
            opacity = opacity,
            pressedDirs = visualState and 0xF0
        )
        // Draw A
        ActionButtonCanvas("A", Color(0xFFE74C3C), padLayout.btnA, surfaceSize, opacity, visualState and BTN_A != 0)
        // Draw B
        ActionButtonCanvas("B", Color(0xFFE67E22), padLayout.btnB, surfaceSize, opacity, visualState and BTN_B != 0)
        // Turbo A/B — hidden on SNES (X/Y buttons take their place)
        if (!showXY) {
            TurboButtonCanvas("A", Color(0xFFE74C3C), padLayout.btnTurboA, surfaceSize, opacity, turboState and BTN_A != 0)
            TurboButtonCanvas("B", Color(0xFFE67E22), padLayout.btnTurboB, surfaceSize, opacity, turboState and BTN_B != 0)
        }
        // Start
        PillButtonCanvas("START", padLayout.btnStart, surfaceSize, opacity, visualState and BTN_START != 0)
        // Select
        PillButtonCanvas("SELECT", padLayout.btnSelect, surfaceSize, opacity, visualState and BTN_SELECT != 0)
        // L/R shoulder buttons (GBA/SNES)
        if (showLR) {
            ShoulderButtonCanvas("L", padLayout.btnL, surfaceSize, opacity, visualState and lBit != 0)
            ShoulderButtonCanvas("R", padLayout.btnR, surfaceSize, opacity, visualState and rBit != 0)
        }
        // X/Y face buttons (SNES only)
        if (showXY) {
            ActionButtonCanvas("X", Color(0xFF3498DB), padLayout.btnX, surfaceSize, opacity, visualState and BTN_X != 0)
            ActionButtonCanvas("Y", Color(0xFF2ECC71), padLayout.btnY, surfaceSize, opacity, visualState and BTN_Y != 0)
        }
    }
}

// Compute D-pad direction from touch position within dpad rect.
// Supports 8 directions: up, down, left, right, and 4 diagonals.
private fun computeDpadDirection(
    pos: Offset,
    rect: androidx.compose.ui.geometry.Rect
): Int {
    val cx = rect.center.x
    val cy = rect.center.y
    val dx = pos.x - cx
    val dy = pos.y - cy
    val absX = kotlin.math.abs(dx)
    val absY = kotlin.math.abs(dy)
    val deadZone = rect.width * 0.15f
    if (absX < deadZone && absY < deadZone) return 0

    var bits = 0
    if (absX > deadZone) {
        bits = bits or (if (dx < 0) BTN_LEFT else BTN_RIGHT)
    }
    if (absY > deadZone) {
        bits = bits or (if (dy < 0) BTN_UP else BTN_DOWN)
    }
    return bits
}

// ---------------------------------------------------------------------------
// Button drawing composables (no pointer input — purely visual)
// ---------------------------------------------------------------------------
private fun buttonOffset(layout: ButtonLayout, surfaceSize: IntSize, density: androidx.compose.ui.unit.Density): Pair<Float, Float> {
    val sizePx = with(density) { layout.sizeDp.dp.toPx() }
    val px = surfaceSize.width * layout.x - sizePx / 2
    val py = surfaceSize.height * layout.y - sizePx / 2
    return px to py
}

@Composable
private fun DpadCanvas(
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    pressedDirs: Int
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val halfSize = size.width / 2f
            val armLen = halfSize * 0.95f
            val armThick = size.width * 0.30f
            val halfThick = armThick / 2f
            val cornerR = armThick * 0.15f
            val cr = androidx.compose.ui.geometry.CornerRadius(cornerR, cornerR)

            val baseColor = Color(0xFF1A1A22).copy(alpha = opacity)
            val armColor = Color(0xFF2C2C38).copy(alpha = opacity)
            val pressedColor = Color(0xFFFFD66B).copy(alpha = opacity * 0.8f)

            drawRoundRect(armColor, Offset(cx - armLen, cy - halfThick), Size(armLen * 2, armThick), cr)
            drawRoundRect(armColor, Offset(cx - halfThick, cy - armLen), Size(armThick, armLen * 2), cr)
            drawRoundRect(baseColor, Offset(cx - halfThick * 0.85f, cy - halfThick * 0.85f), Size(halfThick * 1.7f, halfThick * 1.7f), cr)

            val armTipLen = armLen * 0.42f
            val tipThick = armThick * 0.7f
            if (pressedDirs and BTN_UP != 0) drawRoundRect(pressedColor, Offset(cx - tipThick/2, cy - armLen), Size(tipThick, armTipLen), cr)
            if (pressedDirs and BTN_DOWN != 0) drawRoundRect(pressedColor, Offset(cx - tipThick/2, cy + armLen - armTipLen), Size(tipThick, armTipLen), cr)
            if (pressedDirs and BTN_LEFT != 0) drawRoundRect(pressedColor, Offset(cx - armLen, cy - tipThick/2), Size(armTipLen, tipThick), cr)
            if (pressedDirs and BTN_RIGHT != 0) drawRoundRect(pressedColor, Offset(cx + armLen - armTipLen, cy - tipThick/2), Size(armTipLen, tipThick), cr)

            val arrowSize = armThick * 0.18f
            val arrowOffset = armLen * 0.68f
            val dirs = listOf(Triple(0f, -1f, BTN_UP), Triple(0f, 1f, BTN_DOWN), Triple(-1f, 0f, BTN_LEFT), Triple(1f, 0f, BTN_RIGHT))
            for ((dx, dy, bit) in dirs) {
                val ax = cx + dx * arrowOffset
                val ay = cy + dy * arrowOffset
                val isActive = pressedDirs and bit != 0
                drawTriangle(ax, ay, dx, dy, arrowSize, if (isActive) Color(0xFF1A1A22) else Color(0x99FFFFFF))
            }
        }
    }
}

private fun DrawScope.drawTriangle(cx: Float, cy: Float, dx: Float, dy: Float, size: Float, color: Color) {
    val sx = cx - dy * size; val sy = cy + dx * size
    val ex = cx + dy * size; val ey = cy - dx * size
    val tx = cx + dx * size * 1.5f; val ty = cy + dy * size * 1.5f
    drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(sx, sy); lineTo(ex, ey); lineTo(tx, ty); close() }, color)
}

@Composable
private fun ActionButtonCanvas(
    label: String, color: Color, layout: ButtonLayout, surfaceSize: IntSize, opacity: Float, isPressed: Boolean
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)

    Box(
        modifier = Modifier.offset { IntOffset(px.toInt(), py.toInt()) }.size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f; val cy = size.height / 2f; val r = size.width * 0.46f
            drawCircle(color.copy(alpha = opacity * 0.3f), r + 3.dp.toPx(), Offset(cx, cy))
            drawCircle(if (isPressed) color.copy(alpha = (opacity * 1.5f).coerceAtMost(1f)) else color.copy(alpha = opacity), r, Offset(cx, cy))
            drawCircle(Color.White.copy(alpha = if (isPressed) 0.1f else 0.15f), r * 0.7f, Offset(cx - r * 0.15f, cy - r * 0.15f))
        }
        Text(label, color = Color.White, fontSize = (sizeDp.value * 0.35f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
private fun TurboButtonCanvas(
    label: String, color: Color, layout: ButtonLayout, surfaceSize: IntSize, opacity: Float, isPressed: Boolean
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)

    Box(
        modifier = Modifier.offset { IntOffset(px.toInt(), py.toInt()) }.size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f; val cy = size.height / 2f; val r = size.width * 0.44f
            drawCircle(color.copy(alpha = opacity * 0.4f), r + 2.dp.toPx(), Offset(cx, cy), style = Stroke(width = 1.5.dp.toPx()))
            drawCircle(if (isPressed) color.copy(alpha = (opacity * 1.5f).coerceAtMost(1f)) else color.copy(alpha = opacity * 0.7f), r, Offset(cx, cy))
        }
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = (sizeDp.value * 0.32f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

@Composable
private fun PillButtonCanvas(
    label: String, layout: ButtonLayout, surfaceSize: IntSize, opacity: Float, isPressed: Boolean
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val widthDp = sizeDp * 2.2f
    val heightDp = sizeDp * 0.7f
    val wPx = with(density) { widthDp.toPx() }
    val hPx = with(density) { heightDp.toPx() }
    val px = surfaceSize.width * layout.x - wPx / 2
    val py = surfaceSize.height * layout.y - hPx / 2

    Box(
        modifier = Modifier.offset { IntOffset(px.toInt(), py.toInt()) }.size(width = widthDp, height = heightDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val r = h * 0.4f
            val cr = androidx.compose.ui.geometry.CornerRadius(r, r)
            drawRoundRect(
                if (isPressed) Color(0xFF3A4050).copy(alpha = (opacity * 1.5f).coerceAtMost(1f))
                else Color(0xFF2A3040).copy(alpha = opacity),
                Offset(0f, 0f), Size(w, h), cr
            )
            drawRoundRect(Color.White.copy(alpha = if (isPressed) 0.05f else 0.1f), Offset(w * 0.1f, h * 0.15f), Size(w * 0.8f, h * 0.25f), androidx.compose.ui.geometry.CornerRadius(r * 0.5f, r * 0.5f))
        }
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = (sizeDp.value * 0.22f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
    }
}

// Shoulder button (L/R) — wide pill-shaped, top corners
@Composable
private fun ShoulderButtonCanvas(
    label: String,
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    isPressed: Boolean
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val widthDp = sizeDp * 1.6f
    val heightDp = sizeDp * 0.7f
    val wPx = with(density) { widthDp.toPx() }
    val hPx = with(density) { heightDp.toPx() }
    val px = surfaceSize.width * layout.x - wPx / 2
    val py = surfaceSize.height * layout.y - hPx / 2

    Box(
        modifier = Modifier.offset { IntOffset(px.toInt(), py.toInt()) }.size(width = widthDp, height = heightDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val r = h * 0.4f
            val cr = androidx.compose.ui.geometry.CornerRadius(r, r)
            drawRoundRect(
                if (isPressed) Color(0xFF3A4050).copy(alpha = (opacity * 1.5f).coerceAtMost(1f))
                else Color(0xFF2A3040).copy(alpha = opacity),
                Offset(0f, 0f), Size(w, h), cr
            )
            drawRoundRect(Color.White.copy(alpha = if (isPressed) 0.05f else 0.1f), Offset(w * 0.1f, h * 0.15f), Size(w * 0.8f, h * 0.25f), androidx.compose.ui.geometry.CornerRadius(r * 0.5f, r * 0.5f))
        }
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = (sizeDp.value * 0.28f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Menu overlay
// ---------------------------------------------------------------------------
@Composable
private fun MenuOverlay(
    gameTitle: String,
    running: Boolean,
    fastForward: Boolean,
    currentSlot: Int = 0,
    onTogglePause: () -> Unit,
    onToggleFastForward: () -> Unit,
    onScreenshot: () -> Unit,
    onSaveState: () -> Unit,
    onLoadState: () -> Unit,
    onReset: () -> Unit,
    onLayoutEditor: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x88000000))
            .pointerInput(Unit) {
                awaitEachGesture { awaitFirstDown(); /* consume */ }
            }
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp)
            .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(gameTitle, color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp), maxLines = 1)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onTogglePause) { Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "暂停/继续", tint = Color.White) }
        IconButton(onClick = onToggleFastForward) { Icon(Icons.Rounded.FastForward, "快进", tint = if (fastForward) Color(0xFFFFD66B) else Color.White) }
        IconButton(onClick = onScreenshot) { Icon(Icons.Rounded.CameraAlt, "截图", tint = Color.White) }
        IconButton(onClick = onSaveState) { Icon(Icons.Rounded.Save, "存档", tint = Color.White) }
        IconButton(onClick = onLoadState) { Icon(Icons.Rounded.Upload, "读档", tint = Color.White) }
        IconButton(onClick = onReset) { Icon(Icons.Rounded.Refresh, "重置", tint = Color(0xFFFFD66B)) }
        IconButton(onClick = onLayoutEditor) { Icon(Icons.Rounded.Tune, "手柄布局", tint = Color.White) }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "设置", tint = Color.White) }
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Fullscreen, "隐藏菜单", tint = Color(0xFF4A90D9)) }
        IconButton(onClick = onExit) { Icon(Icons.Rounded.Close, "退出", tint = Color(0xFFFF6B6B)) }
    }
}

// ---------------------------------------------------------------------------
// State slot picker dialog — choose a save slot (0-9) for save/load state
// ---------------------------------------------------------------------------
@Composable
private fun SlotPickerDialog(
    mode: String, // "save" | "load"
    currentSlot: Int,
    onSlotSelected: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (mode == "save") "保存存档" else "读取存档") },
        text = {
            Column {
                Text(
                    if (mode == "save") "选择一个存档槽位（覆盖已有存档）" else "选择一个存档槽位读取",
                    fontSize = 13.sp,
                    color = Color.Gray,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (slot in 0..4) {
                        SlotButton(
                            slot = slot,
                            isCurrent = slot == currentSlot,
                            onClick = { onSlotSelected(slot) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (slot in 5..9) {
                        SlotButton(
                            slot = slot,
                            isCurrent = slot == currentSlot,
                            onClick = { onSlotSelected(slot) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun SlotButton(slot: Int, isCurrent: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isCurrent) Color(0xFF4F8AC4) else Color(0xFFE0E0E0))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "$slot",
            color = if (isCurrent) Color.White else Color(0xFF1E2A3A),
            fontSize = 16.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}

// ---------------------------------------------------------------------------
// Pad layout editor — drag to move (fixed), tap to select + slider for size
// ---------------------------------------------------------------------------
@Composable
private fun PadLayoutEditor(
    padLayout: PadLayout,
    platform: GamePlatform = GamePlatform.NES,
    onLayoutChange: (PadLayout) -> Unit,
    surfaceSize: IntSize,
    onClose: () -> Unit
) {
    val density = LocalDensity.current
    var selectedBtn by remember { mutableStateOf<BtnType?>(null) }

    val showLR = platform == GamePlatform.GBA || platform == GamePlatform.SFC
    val showXY = platform == GamePlatform.SFC

    Box(modifier = Modifier.fillMaxSize().background(Color(0x88000000))) {
        // Top toolbar
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
                .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("拖动移动 · 点击选大小", color = Color.White, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onLayoutChange(PadLayout()) }) {
                Icon(Icons.Rounded.Refresh, "重置", tint = Color(0xFFFFD66B))
            }
            IconButton(onClick = onClose) {
                Text("完成", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }

        // Draggable button previews — use Unit key so gesture doesn't restart
        Box(modifier = Modifier.fillMaxSize()) {
            EditableDpad(padLayout, surfaceSize, selectedBtn == BtnType.DPAD,
                onMove = { targetX, targetY ->
                    val nx = targetX.coerceIn(0.05f, 0.45f)
                    val ny = targetY.coerceIn(0.3f, 0.97f)
                    onLayoutChange(padLayout.copy(dpad = padLayout.dpad.copy(x = nx, y = ny)))
                },
                onSelect = { selectedBtn = BtnType.DPAD }
            )
            EditableRoundBtn("A", Color(0xFFE74C3C), padLayout.btnA, surfaceSize, selectedBtn == BtnType.A,
                onMove = { targetX, targetY ->
                    val nx = targetX.coerceIn(0.4f, 0.95f)
                    val ny = targetY.coerceIn(0.3f, 0.97f)
                    onLayoutChange(padLayout.copy(btnA = padLayout.btnA.copy(x = nx, y = ny)))
                },
                onSelect = { selectedBtn = BtnType.A }
            )
            EditableRoundBtn("B", Color(0xFFE67E22), padLayout.btnB, surfaceSize, selectedBtn == BtnType.B,
                onMove = { targetX, targetY ->
                    val nx = targetX.coerceIn(0.4f, 0.95f)
                    val ny = targetY.coerceIn(0.3f, 0.97f)
                    onLayoutChange(padLayout.copy(btnB = padLayout.btnB.copy(x = nx, y = ny)))
                },
                onSelect = { selectedBtn = BtnType.B }
            )
            if (!showXY) {
                EditableRoundBtn("TA", Color(0xFFE74C3C), padLayout.btnTurboA, surfaceSize, selectedBtn == BtnType.TURBO_A,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        onLayoutChange(padLayout.copy(btnTurboA = padLayout.btnTurboA.copy(x = nx, y = ny)))
                    },
                    onSelect = { selectedBtn = BtnType.TURBO_A }
                )
                EditableRoundBtn("TB", Color(0xFFE67E22), padLayout.btnTurboB, surfaceSize, selectedBtn == BtnType.TURBO_B,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        onLayoutChange(padLayout.copy(btnTurboB = padLayout.btnTurboB.copy(x = nx, y = ny)))
                    },
                    onSelect = { selectedBtn = BtnType.TURBO_B }
                )
            }
            EditablePillBtn("START", padLayout.btnStart, surfaceSize, selectedBtn == BtnType.START,
                onMove = { targetX, targetY ->
                    val nx = targetX.coerceIn(0.1f, 0.9f)
                    val ny = targetY.coerceIn(0.3f, 0.97f)
                    onLayoutChange(padLayout.copy(btnStart = padLayout.btnStart.copy(x = nx, y = ny)))
                },
                onSelect = { selectedBtn = BtnType.START }
            )
            EditablePillBtn("SELECT", padLayout.btnSelect, surfaceSize, selectedBtn == BtnType.SELECT,
                onMove = { targetX, targetY ->
                    val nx = targetX.coerceIn(0.1f, 0.9f)
                    val ny = targetY.coerceIn(0.3f, 0.97f)
                    onLayoutChange(padLayout.copy(btnSelect = padLayout.btnSelect.copy(x = nx, y = ny)))
                },
                onSelect = { selectedBtn = BtnType.SELECT }
            )
            // L/R shoulder buttons (GBA/SNES)
            if (showLR) {
                EditablePillBtn("L", padLayout.btnL, surfaceSize, selectedBtn == BtnType.L,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.05f, 0.4f)
                        val ny = targetY.coerceIn(0.02f, 0.3f)
                        onLayoutChange(padLayout.copy(btnL = padLayout.btnL.copy(x = nx, y = ny)))
                    },
                    onSelect = { selectedBtn = BtnType.L }
                )
                EditablePillBtn("R", padLayout.btnR, surfaceSize, selectedBtn == BtnType.R,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.6f, 0.95f)
                        val ny = targetY.coerceIn(0.02f, 0.3f)
                        onLayoutChange(padLayout.copy(btnR = padLayout.btnR.copy(x = nx, y = ny)))
                    },
                    onSelect = { selectedBtn = BtnType.R }
                )
            }
            // X/Y face buttons (SNES only)
            if (showXY) {
                EditableRoundBtn("X", Color(0xFF3498DB), padLayout.btnX, surfaceSize, selectedBtn == BtnType.X,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        onLayoutChange(padLayout.copy(btnX = padLayout.btnX.copy(x = nx, y = ny)))
                    },
                    onSelect = { selectedBtn = BtnType.X }
                )
                EditableRoundBtn("Y", Color(0xFF2ECC71), padLayout.btnY, surfaceSize, selectedBtn == BtnType.Y,
                    onMove = { targetX, targetY ->
                        val nx = targetX.coerceIn(0.4f, 0.95f)
                        val ny = targetY.coerceIn(0.3f, 0.97f)
                        onLayoutChange(padLayout.copy(btnY = padLayout.btnY.copy(x = nx, y = ny)))
                    },
                    onSelect = { selectedBtn = BtnType.Y }
                )
            }
        }

        // Size slider at bottom — shown when a button is selected
        val sel = selectedBtn
        if (sel != null) {
            val currentSize: Int
            val minSize: Int
            val maxSize: Int
            val label: String
            when (sel) {
                BtnType.DPAD -> { currentSize = padLayout.dpad.sizeDp; minSize = 80; maxSize = 220; label = "十字键大小" }
                BtnType.A -> { currentSize = padLayout.btnA.sizeDp; minSize = 40; maxSize = 120; label = "A键大小" }
                BtnType.B -> { currentSize = padLayout.btnB.sizeDp; minSize = 40; maxSize = 120; label = "B键大小" }
                BtnType.TURBO_A -> { currentSize = padLayout.btnTurboA.sizeDp; minSize = 30; maxSize = 90; label = "连射A大小" }
                BtnType.TURBO_B -> { currentSize = padLayout.btnTurboB.sizeDp; minSize = 30; maxSize = 90; label = "连射B大小" }
                BtnType.START -> { currentSize = padLayout.btnStart.sizeDp; minSize = 30; maxSize = 100; label = "START大小" }
                BtnType.SELECT -> { currentSize = padLayout.btnSelect.sizeDp; minSize = 30; maxSize = 100; label = "SELECT大小" }
                BtnType.L -> { currentSize = padLayout.btnL.sizeDp; minSize = 36; maxSize = 90; label = "L键大小" }
                BtnType.R -> { currentSize = padLayout.btnR.sizeDp; minSize = 36; maxSize = 90; label = "R键大小" }
                BtnType.X -> { currentSize = padLayout.btnX.sizeDp; minSize = 40; maxSize = 120; label = "X键大小" }
                BtnType.Y -> { currentSize = padLayout.btnY.sizeDp; minSize = 40; maxSize = 120; label = "Y键大小" }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = Color.White, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("${currentSize}dp", color = Color(0xFFFFD66B), fontSize = 13.sp)
                }
                Spacer(Modifier.size(8.dp))
                Slider(
                    value = currentSize.toFloat(),
                    onValueChange = { newVal ->
                        val intVal = newVal.toInt()
                        val newLayout = when (sel) {
                            BtnType.DPAD -> padLayout.copy(dpad = padLayout.dpad.copy(sizeDp = intVal))
                            BtnType.A -> padLayout.copy(btnA = padLayout.btnA.copy(sizeDp = intVal))
                            BtnType.B -> padLayout.copy(btnB = padLayout.btnB.copy(sizeDp = intVal))
                            BtnType.TURBO_A -> padLayout.copy(btnTurboA = padLayout.btnTurboA.copy(sizeDp = intVal))
                            BtnType.TURBO_B -> padLayout.copy(btnTurboB = padLayout.btnTurboB.copy(sizeDp = intVal))
                            BtnType.START -> padLayout.copy(btnStart = padLayout.btnStart.copy(sizeDp = intVal))
                            BtnType.SELECT -> padLayout.copy(btnSelect = padLayout.btnSelect.copy(sizeDp = intVal))
                            BtnType.L -> padLayout.copy(btnL = padLayout.btnL.copy(sizeDp = intVal))
                            BtnType.R -> padLayout.copy(btnR = padLayout.btnR.copy(sizeDp = intVal))
                            BtnType.X -> padLayout.copy(btnX = padLayout.btnX.copy(sizeDp = intVal))
                            BtnType.Y -> padLayout.copy(btnY = padLayout.btnY.copy(sizeDp = intVal))
                        }
                        onLayoutChange(newLayout)
                    },
                    valueRange = minSize.toFloat()..maxSize.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFFFFD66B),
                        activeTrackColor = Color(0xFFFFD66B),
                        inactiveTrackColor = Color(0xFF4A5568)
                    )
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Editable button — drag to move (uses awaitEachGesture with proper delta)
// ---------------------------------------------------------------------------
@Composable
private fun EditableRoundBtn(
    label: String,
    color: Color,
    layout: ButtonLayout,
    surfaceSize: IntSize,
    isSelected: Boolean,
    onMove: (targetX: Float, targetY: Float) -> Unit,
    onSelect: () -> Unit
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)
    // Track drag start position to compute accurate absolute target
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartY by remember { mutableStateOf(0f) }
    var layoutStartX by remember { mutableStateOf(0f) }
    var layoutStartY by remember { mutableStateOf(0f) }

    // CRITICAL: use rememberUpdatedState so the gesture handler (which has
    // pointerInput(Unit) and doesn't restart) always reads the LATEST values.
    // Without this, moving button A then dragging button B would use stale
    // padLayout (captured at initial composition), resetting A's position.
    val currentLayout by rememberUpdatedState(layout)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSurfaceSize by rememberUpdatedState(surfaceSize)

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    currentOnSelect()
                    dragStartX = down.position.x
                    dragStartY = down.position.y
                    layoutStartX = currentLayout.x
                    layoutStartY = currentLayout.y

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            val dxPx = change.position.x - dragStartX
                            val dyPx = change.position.y - dragStartY
                            val dxFrac = dxPx / currentSurfaceSize.width
                            val dyFrac = dyPx / currentSurfaceSize.height
                            currentOnMove(layoutStartX + dxFrac, layoutStartY + dyFrac)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.width * 0.46f
            drawCircle(color.copy(alpha = if (isSelected) 0.5f else 0.35f), r, Offset(size.width / 2f, size.height / 2f))
            drawCircle(color, r, Offset(size.width / 2f, size.height / 2f), style = Stroke(width = if (isSelected) 3.dp.toPx() else 2.dp.toPx()))
        }
        Text(label, color = color, fontSize = (sizeDp.value * 0.2f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
private fun EditableDpad(
    padLayout: PadLayout,
    surfaceSize: IntSize,
    isSelected: Boolean,
    onMove: (targetX: Float, targetY: Float) -> Unit,
    onSelect: () -> Unit
) {
    val layout = padLayout.dpad
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize, density)
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartY by remember { mutableStateOf(0f) }
    var layoutStartX by remember { mutableStateOf(0f) }
    var layoutStartY by remember { mutableStateOf(0f) }

    val currentLayout by rememberUpdatedState(layout)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSurfaceSize by rememberUpdatedState(surfaceSize)

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    currentOnSelect()
                    dragStartX = down.position.x
                    dragStartY = down.position.y
                    layoutStartX = currentLayout.x
                    layoutStartY = currentLayout.y

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            val dxPx = change.position.x - dragStartX
                            val dyPx = change.position.y - dragStartY
                            val dxFrac = dxPx / currentSurfaceSize.width
                            val dyFrac = dyPx / currentSurfaceSize.height
                            currentOnMove(layoutStartX + dxFrac, layoutStartY + dyFrac)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.width * 0.46f
            drawCircle(Color(0xFFFFD66B).copy(alpha = if (isSelected) 0.5f else 0.35f), r, Offset(size.width / 2f, size.height / 2f))
            drawCircle(Color(0xFFFFD66B), r, Offset(size.width / 2f, size.height / 2f), style = Stroke(width = if (isSelected) 3.dp.toPx() else 2.dp.toPx()))
        }
        Text("D-Pad", color = Color(0xFFFFD66B), fontSize = (sizeDp.value * 0.15f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
private fun EditablePillBtn(
    label: String,
    layout: ButtonLayout,
    surfaceSize: IntSize,
    isSelected: Boolean,
    onMove: (targetX: Float, targetY: Float) -> Unit,
    onSelect: () -> Unit
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val widthDp = sizeDp * 2.2f
    val heightDp = sizeDp * 0.7f
    val wPx = with(density) { widthDp.toPx() }
    val hPx = with(density) { heightDp.toPx() }
    val px = surfaceSize.width * layout.x - wPx / 2
    val py = surfaceSize.height * layout.y - hPx / 2
    var dragStartX by remember { mutableStateOf(0f) }
    var dragStartY by remember { mutableStateOf(0f) }
    var layoutStartX by remember { mutableStateOf(0f) }
    var layoutStartY by remember { mutableStateOf(0f) }

    val currentLayout by rememberUpdatedState(layout)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentSurfaceSize by rememberUpdatedState(surfaceSize)

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(width = widthDp, height = heightDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    currentOnSelect()
                    dragStartX = down.position.x
                    dragStartY = down.position.y
                    layoutStartX = currentLayout.x
                    layoutStartY = currentLayout.y

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            val dxPx = change.position.x - dragStartX
                            val dyPx = change.position.y - dragStartY
                            val dxFrac = dxPx / currentSurfaceSize.width
                            val dyFrac = dyPx / currentSurfaceSize.height
                            currentOnMove(layoutStartX + dxFrac, layoutStartY + dyFrac)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height; val r = h * 0.4f
            val cr = androidx.compose.ui.geometry.CornerRadius(r, r)
            drawRoundRect(Color(0xFF4A90D9).copy(alpha = if (isSelected) 0.5f else 0.35f), Offset(0f, 0f), Size(w, h), cr)
            drawRoundRect(Color(0xFF4A90D9), Offset(0f, 0f), Size(w, h), cr, style = Stroke(width = if (isSelected) 3.dp.toPx() else 2.dp.toPx()))
        }
        Text(label, color = Color(0xFF4A90D9), fontSize = (sizeDp.value * 0.2f).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------
// Settings panel (in-game) — unified with main SettingsScreen via PadLayoutStore
// Includes FDS BIOS import for Famicom Disk System game support.
// ---------------------------------------------------------------------------
@Composable
private fun SettingsPanel(
    padLayout: PadLayout,
    platform: GamePlatform = GamePlatform.NES,
    onLayoutChange: (PadLayout) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    var biosStatus by remember { mutableStateOf(checkFdsBiosStatus(context)) }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x88000000))
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp)
            .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("核心设置", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭", tint = Color.White) }
        }
        Spacer(Modifier.size(8.dp))

        // Common video settings for all platforms
        DropdownSetting("画面缩放",
            listOf("stretch" to "全屏拉伸(默认)", "4:3" to "4:3", "8:7" to "8:7", "16:9" to "16:9"),
            padLayout.videoScale
        ) { onLayoutChange(padLayout.copy(videoScale = it)) }

        DropdownSetting("视频滤镜",
            listOf("none" to "关闭", "scanline" to "扫描线", "crt" to "CRT", "dot" to "点阵",
                   "xbr" to "XBR", "hq2x" to "HQ2X", "hq4x" to "HQ4X", "xbr_dot" to "XBR+点阵",
                   "4xbr" to "4XBR", "4xbr_dot" to "4XBR+点阵", "hq4x_dot" to "HQ4X+点阵"),
            padLayout.videoFilter
        ) { onLayoutChange(padLayout.copy(videoFilter = it)) }

        Spacer(Modifier.size(8.dp))
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
        Spacer(Modifier.size(8.dp))

        when (platform) {
            GamePlatform.NES -> {
                Text("NES 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))

                DropdownSetting("NTSC 滤镜",
                    listOf("disabled" to "关闭", "composite" to "复合", "svideo" to "S-Video", "rgb" to "RGB", "monochrome" to "黑白"),
                    padLayout.ntscFilter
                ) { onLayoutChange(padLayout.copy(ntscFilter = it)) }

                DropdownSetting("调色板",
                    listOf(
                        "default" to "默认", "asqrealc" to "AspiringSquire", "wii-vc" to "Wii VC",
                        "rgb" to "Nintendo RGB", "yuv-v3" to "FBX YUV-V3", "unsaturated-final" to "Unsaturated",
                        "sony-cxa2025as-us" to "Sony CXA", "pal" to "PAL", "bmf-final2" to "BMF Final 2",
                        "smooth-fbx" to "FBX Smooth", "composite-direct-fbx" to "FBX Composite",
                        "ntsc-hardware-fbx" to "FBX NTSC HW", "nes-classic-fbx" to "FBX NES Classic"
                    ),
                    padLayout.palette
                ) { onLayoutChange(padLayout.copy(palette = it)) }

                DropdownSetting("区域",
                    listOf("Auto" to "自动", "NTSC" to "NTSC", "PAL" to "PAL", "Dendy" to "Dendy"),
                    padLayout.region
                ) { onLayoutChange(padLayout.copy(region = it)) }

                DropdownSetting("裁剪过扫描",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.cropOverscan
                ) { onLayoutChange(padLayout.copy(cropOverscan = it)) }

                DropdownSetting("超频(减少慢动作)",
                    listOf("disabled" to "关闭", "2x-Postrender" to "后渲染(兼容性好)", "2x-VBlank" to "VBlank(推荐·魂斗罗力量)"),
                    padLayout.overclocking
                ) { onLayoutChange(padLayout.copy(overclocking = it)) }

                Spacer(Modifier.size(12.dp))
                Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
                Spacer(Modifier.size(8.dp))
                Text("FDS BIOS (磁盘系统)", color = Color(0xFFFFD66B), fontSize = 14.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(4.dp))
                Text(
                    "如已将disksys.rom放入assets目录，FDS游戏将自动加载BIOS。" +
                    "也可手动导入disksys.rom (8KB)。",
                    color = Color(0xFF8899AA), fontSize = 10.sp, lineHeight = 14.sp
                )
                Spacer(Modifier.size(6.dp))
                FdsBiosImportSection(
                    biosStatus = biosStatus,
                    onImport = { uri ->
                        val result = importFdsBios(context, uri)
                        biosStatus = checkFdsBiosStatus(context)
                        biosStatus = biosStatus.copy(message = result)
                    }
                )
            }
            GamePlatform.SFC -> {
                Text("SFC/SNES 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))

                Text("画面", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("画面比例",
                    listOf("4:3" to "4:3 (标准)", "uncorrected" to "8:7 (原始像素比)",
                           "auto" to "自动", "ntsc" to "NTSC", "pal" to "PAL"),
                    padLayout.aspectRatio
                ) { onLayoutChange(padLayout.copy(aspectRatio = it)) }

                DropdownSetting("NTSC 滤镜",
                    listOf("disabled" to "关闭", "monochrome" to "黑白", "rf" to "RF",
                           "composite" to "复合", "s-video" to "S-Video", "rgb" to "RGB"),
                    padLayout.ntscFilter
                ) { onLayoutChange(padLayout.copy(ntscFilter = it)) }

                DropdownSetting("裁剪过扫描",
                    listOf("enabled" to "开启", "disabled" to "关闭", "auto" to "自动"),
                    padLayout.sfcOverscan
                ) { onLayoutChange(padLayout.copy(sfcOverscan = it)) }

                DropdownSetting("高分辨率模式",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.sfcGfxHires
                ) { onLayoutChange(padLayout.copy(sfcGfxHires = it)) }

                DropdownSetting("透明效果",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.sfcGfxTransparency
                ) { onLayoutChange(padLayout.copy(sfcGfxTransparency = it)) }

                DropdownSetting("图形裁剪",
                    listOf("enabled" to "开启", "disabled" to "关闭"),
                    padLayout.sfcGfxClip
                ) { onLayoutChange(padLayout.copy(sfcGfxClip = it)) }

                DropdownSetting("高分辨率混合",
                    listOf("disabled" to "关闭", "merge" to "合并", "blur" to "模糊"),
                    padLayout.sfcSideBySide
                ) { onLayoutChange(padLayout.copy(sfcSideBySide = it)) }

                Spacer(Modifier.size(4.dp))
                Text("性能", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("超频(SuperFX)",
                    listOf("100%" to "100% (默认)", "150%" to "150%", "200%" to "200%",
                           "300%" to "300%", "400%" to "400%", "500%" to "500%"),
                    padLayout.sfcOverclock
                ) { onLayoutChange(padLayout.copy(sfcOverclock = it)) }

                DropdownSetting("减少精灵闪烁",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.sfcReduceSpriteFlicker
                ) { onLayoutChange(padLayout.copy(sfcReduceSpriteFlicker = it)) }

                DropdownSetting("减少慢动作",
                    listOf("disabled" to "关闭", "light" to "轻微",
                           "compatible" to "兼容", "max" to "最大"),
                    padLayout.sfcReduceSlowdown
                ) { onLayoutChange(padLayout.copy(sfcReduceSlowdown = it)) }

                Spacer(Modifier.size(4.dp))
                Text("音频", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("音频插值",
                    listOf("gaussian" to "高斯(默认)", "cubic" to "三次", "sinc" to "Sinc",
                           "linear" to "线性", "none" to "无"),
                    padLayout.sfcAudioInterpolation
                ) { onLayoutChange(padLayout.copy(sfcAudioInterpolation = it)) }

                DropdownSetting("回声缓冲Hack",
                    listOf("disabled" to "关闭", "enabled" to "开启(旧版Addmusic)"),
                    padLayout.sfcSoundOutput
                ) { onLayoutChange(padLayout.copy(sfcSoundOutput = it)) }

                Spacer(Modifier.size(4.dp))
                Text("输入", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("上下方向同时输入",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.sfcUpDownAllowed
                ) { onLayoutChange(padLayout.copy(sfcUpDownAllowed = it)) }

                DropdownSetting("随机内存(不安全)",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.sfcSuperScope
                ) { onLayoutChange(padLayout.copy(sfcSuperScope = it)) }

                Spacer(Modifier.size(4.dp))
                Text("图层显示", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("BG图层 1",
                    listOf("enabled" to "显示", "disabled" to "隐藏"),
                    padLayout.sfcLayer1
                ) { onLayoutChange(padLayout.copy(sfcLayer1 = it)) }

                DropdownSetting("BG图层 2",
                    listOf("enabled" to "显示", "disabled" to "隐藏"),
                    padLayout.sfcLayer2
                ) { onLayoutChange(padLayout.copy(sfcLayer2 = it)) }

                DropdownSetting("BG图层 3",
                    listOf("enabled" to "显示", "disabled" to "隐藏"),
                    padLayout.sfcLayer3
                ) { onLayoutChange(padLayout.copy(sfcLayer3 = it)) }

                DropdownSetting("BG图层 4",
                    listOf("enabled" to "显示", "disabled" to "隐藏"),
                    padLayout.sfcLayer4
                ) { onLayoutChange(padLayout.copy(sfcLayer4 = it)) }

                DropdownSetting("精灵图层",
                    listOf("enabled" to "显示", "disabled" to "隐藏"),
                    padLayout.sfcLayer5
                ) { onLayoutChange(padLayout.copy(sfcLayer5 = it)) }
            }
            GamePlatform.GB, GamePlatform.GBA -> {
                val platName = if (platform == GamePlatform.GBA) "GBA" else "GB/GBC"
                Text("$platName 专属设置", color = Color(0xFFFFD66B), fontSize = 13.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                Spacer(Modifier.size(6.dp))

                Text("系统", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("主机型号",
                    listOf("Autodetect" to "自动", "Game Boy" to "Game Boy (DMG)",
                           "Super Game Boy" to "Super Game Boy", "Game Boy Color" to "Game Boy Color",
                           "Game Boy Advance" to "Game Boy Advance"),
                    padLayout.gbModel
                ) { onLayoutChange(padLayout.copy(gbModel = it)) }

                DropdownSetting("SGB 边框",
                    listOf("ON" to "显示", "OFF" to "隐藏"),
                    padLayout.gbSgbBorders
                ) { onLayoutChange(padLayout.copy(gbSgbBorders = it)) }

                Spacer(Modifier.size(4.dp))
                Text("色彩校正", color = Color(0xFF8899AA), fontSize = 11.sp)
                if (platform == GamePlatform.GB) {
                    DropdownSetting("GB色彩校正",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.gbColorCorrection
                    ) { onLayoutChange(padLayout.copy(gbColorCorrection = it)) }

                    DropdownSetting("GB色彩预设",
                        listOf("default" to "默认", "AGB" to "GBA风格", "GB Pocket" to "Pocket风格",
                               "GB Light" to "亮色", "GB Original" to "原始"),
                        padLayout.gbcColorPreset
                    ) { onLayoutChange(padLayout.copy(gbcColorPreset = it)) }
                }
                if (platform == GamePlatform.GBA) {
                    DropdownSetting("GBA色彩校正",
                        listOf("enabled" to "开启", "disabled" to "关闭"),
                        padLayout.gbaColorCorrection
                    ) { onLayoutChange(padLayout.copy(gbaColorCorrection = it)) }

                    DropdownSetting("GBA色彩预设",
                        listOf("default" to "默认", "AGB" to "GBA原机", "GBA SP" to "GBA SP风格",
                               "GB Micro" to "GB Micro风格"),
                        padLayout.gbaColorPreset
                    ) { onLayoutChange(padLayout.copy(gbaColorPreset = it)) }
                }

                Spacer(Modifier.size(4.dp))
                Text("画面", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("帧混合",
                    listOf("OFF" to "关闭", "ON" to "开启", "fast" to "快速"),
                    padLayout.gbaFrameBlending
                ) { onLayoutChange(padLayout.copy(gbaFrameBlending = it)) }

                Spacer(Modifier.size(4.dp))
                Text("音频", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("音频重采样器",
                    listOf("nearest" to "最近邻(快速)", "sinc" to "Sinc(高质量)",
                           "cosine" to "余弦(均衡)", "cubic" to "三次(高质量)"),
                    padLayout.gbaAudioResampler
                ) { onLayoutChange(padLayout.copy(gbaAudioResampler = it)) }

                DropdownSetting("低通滤波",
                    listOf("disabled" to "关闭", "enabled" to "开启"),
                    padLayout.gbaAudioLowPass
                ) { onLayoutChange(padLayout.copy(gbaAudioLowPass = it)) }

                DropdownSetting("低通滤波范围",
                    listOf("20" to "20", "40" to "40", "60" to "60 (默认)",
                           "80" to "80", "100" to "100"),
                    padLayout.gbaAudioLowPassRange
                ) { onLayoutChange(padLayout.copy(gbaAudioLowPassRange = it)) }

                Spacer(Modifier.size(4.dp))
                Text("性能", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("跳帧类型",
                    listOf("disabled" to "关闭", "auto" to "自动跳帧", "fixed" to "固定跳帧"),
                    padLayout.gbaFrameskipType
                ) { onLayoutChange(padLayout.copy(gbaFrameskipType = it)) }

                DropdownSetting("跳帧数量",
                    listOf("0" to "0", "1" to "1", "2" to "2", "3" to "3",
                           "4" to "4", "5" to "5", "6" to "6", "7" to "7",
                           "8" to "8", "9" to "9", "10" to "10"),
                    padLayout.gbaFrameskipCount
                ) { onLayoutChange(padLayout.copy(gbaFrameskipCount = it)) }

                DropdownSetting("跳帧阈值(自动)",
                    listOf("10" to "10", "20" to "20", "33" to "33 (默认)",
                           "50" to "50", "70" to "70", "90" to "90"),
                    padLayout.gbaFrameskipThreshold
                ) { onLayoutChange(padLayout.copy(gbaFrameskipThreshold = it)) }

                if (platform == GamePlatform.GBA) {
                    DropdownSetting("空闲优化",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.gbaIdleOptimization
                    ) { onLayoutChange(padLayout.copy(gbaIdleOptimization = it)) }
                }

                Spacer(Modifier.size(4.dp))
                Text("高级", color = Color(0xFF8899AA), fontSize = 11.sp)
                DropdownSetting("允许相反方向",
                    listOf("OFF" to "关闭", "ON" to "开启"),
                    padLayout.gbaAllowOpposite
                ) { onLayoutChange(padLayout.copy(gbaAllowOpposite = it)) }

                DropdownSetting("太阳能传感器",
                    listOf("0" to "0 (黑暗)", "1" to "1", "2" to "2", "3" to "3",
                           "4" to "4", "5" to "5 (中等)", "6" to "6", "7" to "7",
                           "8" to "8", "9" to "9", "10" to "10 (明亮)"),
                    padLayout.gbaSolarSensor
                ) { onLayoutChange(padLayout.copy(gbaSolarSensor = it)) }

                if (platform == GamePlatform.GBA) {
                    DropdownSetting("强制RTC",
                        listOf("disabled" to "关闭", "enabled" to "开启"),
                        padLayout.gbaForceRTC
                    ) { onLayoutChange(padLayout.copy(gbaForceRTC = it)) }
                }
            }
            GamePlatform.JAVA -> { /* no core options for J2ME */ }
        }

        Spacer(Modifier.size(8.dp))
        Text("修改后即时生效。设置与主界面设置同步。", color = Color(0xFF8899AA), fontSize = 11.sp)
    }
}

// FDS BIOS status data
private data class FdsBiosStatus(val exists: Boolean, val valid: Boolean, val message: String = "")

// Check if disksys.rom exists and is valid in the app's filesDir
private fun checkFdsBiosStatus(context: android.content.Context): FdsBiosStatus {
    val biosFile = java.io.File(context.filesDir, "disksys.rom")
    if (!biosFile.exists()) {
        return FdsBiosStatus(exists = false, valid = false, message = "未导入")
    }
    val size = biosFile.length()
    if (size != 8192L) {
        return FdsBiosStatus(exists = true, valid = false,
            message = "文件大小错误: ${size}字节 (需要8192字节)")
    }
    // Quick check: first byte should not be 0x00 (corrupted BIOS has all zeros)
    biosFile.inputStream().use { input ->
        val firstByte = input.read()
        if (firstByte == 0) {
            // Check if first 64 bytes are all zeros
            val header = ByteArray(64)
            input.read(header)
            if (header.all { it == 0.toByte() }) {
                return FdsBiosStatus(exists = true, valid = false,
                    message = "文件已损坏 (全零)")
            }
        }
    }
    return FdsBiosStatus(exists = true, valid = true, message = "已导入 ✓")
}

// Import FDS BIOS from a content URI to filesDir/disksys.rom
private fun importFdsBios(context: android.content.Context, uri: android.net.Uri): String {
    return try {
        val biosFile = java.io.File(context.filesDir, "disksys.rom")
        context.contentResolver.openInputStream(uri)?.use { input ->
            biosFile.outputStream().use { output -> input.copyTo(output) }
        } ?: return "导入失败: 无法读取文件"

        val size = biosFile.length()
        if (size != 8192L) {
            return "导入失败: 文件大小${size}字节不正确 (需要8192字节)"
        }

        // Verify first byte is not zero (corruption check)
        biosFile.inputStream().use { input ->
            val firstByte = input.read()
            if (firstByte == 0) {
                val header = ByteArray(64)
                input.read(header)
                if (header.all { it == 0.toByte() }) {
                    biosFile.delete()
                    return "导入失败: 文件已损坏 (全零数据)"
                }
            }
        }
        "导入成功! 请重新加载FDS游戏"
    } catch (e: Exception) {
        "导入失败: ${e.message}"
    }
}

@Composable
private fun FdsBiosImportSection(
    biosStatus: FdsBiosStatus,
    onImport: (android.net.Uri) -> Unit
) {
    val context = LocalContext.current
    val biosPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) { }
            onImport(uri)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status indicator
        val statusColor = if (biosStatus.valid) Color(0xFF4CAF50) else Color(0xFFFF5252)
        Text("●", color = statusColor, fontSize = 14.sp)
        Spacer(Modifier.size(6.dp))
        Text(
            biosStatus.message,
            color = if (biosStatus.valid) Color(0xFF88DD88) else Color(0xFFFFAAAA),
            fontSize = 12.sp,
            modifier = Modifier.weight(1f)
        )
        // Import button
        Text(
            "导入BIOS",
            color = Color(0xFFFFD66B),
            fontSize = 13.sp,
            modifier = Modifier
                .clickable { biosPickerLauncher.launch(arrayOf("*/*")) }
                .padding(8.dp)
        )
    }
}

@Composable
private fun DropdownSetting(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Spacer(Modifier.weight(1f))
        Box {
            Text(
                selectedLabel, color = Color(0xFFFFD66B), fontSize = 13.sp,
                modifier = Modifier.pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); expanded = true } }.padding(8.dp)
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text, fontSize = 13.sp) }, onClick = { onSelect(value); expanded = false })
                }
            }
        }
    }
}

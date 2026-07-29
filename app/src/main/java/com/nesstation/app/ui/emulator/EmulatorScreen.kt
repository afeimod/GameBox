package com.nesstation.app.ui.emulator

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.storage.ButtonLayout
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.storage.PadLayoutStore
import kotlinx.coroutines.delay

/**
 * The in-game screen — SurfaceView for hardware-accelerated rendering,
 * modern FC-style on-screen controller, and menu/layout/settings overlays.
 *
 * No permanent top bar. Press the device Back button (or long-press the screen)
 * to bring up a menu overlay with pause / save / screenshot / fast-forward /
 * layout editor / settings.
 *
 * Each virtual button (D-pad, A, B, Turbo A, Turbo B, Start, Select) has its
 * own position and size, fully customizable via drag-and-pinch in the layout
 * editor.
 */
@Composable
fun EmulatorScreen(
    game: GameEntry,
    onExit: () -> Unit
) {
    val engine = remember { NesEngine.get() }
    val context = LocalContext.current
    var running by remember { mutableStateOf(true) }
    var fastForward by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var padBits by remember { mutableStateOf(0) }
    var turboBits by remember { mutableStateOf(0) } // turbo A/B hold state
    var turboFrameCounter by remember { mutableIntStateOf(0) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    // Menu overlay state — hidden by default, shown via Back button
    var showMenu by remember { mutableStateOf(false) }
    var showLayoutEditor by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    // Pad layout — loaded from persistent storage
    var padLayout by remember { mutableStateOf(PadLayoutStore.load(context)) }

    // Apply core options on first load
    LaunchedEffect(padLayout) {
        engine.setCoreOption("fceumm_ntsc_filter", padLayout.ntscFilter)
        engine.setCoreOption("fceumm_aspect", padLayout.aspectRatio)
        engine.setCoreOption("fceumm_palette", padLayout.palette)
        engine.setCoreOption("fceumm_region", padLayout.region)
        engine.setCoreOption("fceumm_sndquality", padLayout.soundQuality)
        engine.setCoreOption("fceumm_cropoverscan", padLayout.cropOverscan)
    }

    // Handle Back button: first press opens menu, second press exits
    BackHandler(enabled = !showMenu && !showLayoutEditor && !showSettings) {
        showMenu = true
    }
    BackHandler(enabled = showMenu && !showLayoutEditor && !showSettings) {
        showMenu = false
    }
    BackHandler(enabled = showLayoutEditor) {
        showLayoutEditor = false
    }
    BackHandler(enabled = showSettings) {
        showSettings = false
    }

    // Load ROM on entry
    LaunchedEffect(game) {
        val romPath = game.romPath
        if (romPath.isNullOrEmpty()) {
            errorMsg = "该游戏未关联 ROM 文件"
            return@LaunchedEffect
        }
        val romFile = java.io.File(romPath)
        if (romFile.exists()) {
            val filesDir = context.filesDir.absolutePath
            val ok = engine.loadRom(romFile, filesDir, filesDir) { }
            if (!ok) {
                errorMsg = engine.lastError().ifEmpty { "ROM 加载失败" }
            } else {
                loaded = true
            }
        } else {
            try {
                val input = context.contentResolver.openInputStream(android.net.Uri.parse(romPath))
                if (input != null) {
                    val tempFile = java.io.File(context.cacheDir, "temp_rom.nes")
                    tempFile.outputStream().use { out -> input.copyTo(out) }
                    input.close()
                    val filesDir = context.filesDir.absolutePath
                    val ok = engine.loadRom(tempFile, filesDir, filesDir) { }
                    if (!ok) {
                        errorMsg = engine.lastError().ifEmpty { "ROM 加载失败" }
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

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose { engine.unload() }
    }

    // Apply fast-forward / pause state to engine
    LaunchedEffect(fastForward) { engine.setFastForward(fastForward) }
    LaunchedEffect(running) { engine.setPaused(!running) }

    // Controller state polling loop — pushes pad bits to the engine every frame.
    // Handles turbo button auto-fire by toggling A/B bits at a configurable rate.
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        while (true) {
            // Compute effective pad bits: combine direct buttons + turbo
            var bits = padBits
            if (turboBits != 0) {
                turboFrameCounter++
                // Toggle turbo bits every 3 frames (~20Hz at 60fps)
                if (turboFrameCounter % 3 < 2) {
                    bits = bits or turboBits
                }
            }
            engine.setPad1(bits)
            delay(16)
        }
    }

    // Compute game aspect ratio from settings
    val gameAspect = when (padLayout.aspectRatio) {
        "4:3" -> 4f / 3f
        "NTSC" -> 4f / 3f  // NTSC uses 4:3 display
        "PAL" -> 4f / 3f   // PAL uses 4:3 display
        else -> 8f / 7f     // 8:7 (native NES PAR)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // === Game surface — SurfaceView for hardware-accelerated rendering ===
        if (loaded) {
            GameSurfaceView(
                engine = engine,
                gameAspect = gameAspect,
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

        // === On-screen controller — show when loaded, pad visible, no overlays ===
        if (loaded && padLayout.showPad && !showMenu && !showLayoutEditor && !showSettings && surfaceSize != IntSize.Zero) {
            OnScreenController(
                padLayout = padLayout,
                padBits = padBits,
                onPadBitsChange = { padBits = it },
                turboBits = turboBits,
                onTurboBitsChange = { turboBits = it },
                surfaceSize = surfaceSize
            )
        }

        // === Menu overlay — shown via Back button or long-press ===
        if (loaded && showMenu && !showLayoutEditor && !showSettings) {
            MenuOverlay(
                gameTitle = game.title,
                running = running,
                fastForward = fastForward,
                onTogglePause = { running = !running },
                onToggleFastForward = { fastForward = !fastForward },
                onScreenshot = { /* TODO */ },
                onSaveState = { /* TODO */ },
                onLayoutEditor = { showLayoutEditor = true },
                onSettings = { showSettings = true },
                onClose = { showMenu = false },
                onExit = { onExit() }
            )
        }

        // === Layout editor mode ===
        if (loaded && showLayoutEditor) {
            PadLayoutEditor(
                padLayout = padLayout,
                onLayoutChange = { newLayout ->
                    padLayout = newLayout
                    PadLayoutStore.save(context, newLayout)
                },
                surfaceSize = surfaceSize,
                onClose = { showLayoutEditor = false }
            )
        }

        // === Settings panel ===
        if (loaded && showSettings) {
            SettingsPanel(
                padLayout = padLayout,
                onLayoutChange = { newLayout ->
                    padLayout = newLayout
                    PadLayoutStore.save(context, newLayout)
                    // Apply core options immediately
                    engine.setCoreOption("fceumm_ntsc_filter", newLayout.ntscFilter)
                    engine.setCoreOption("fceumm_aspect", newLayout.aspectRatio)
                    engine.setCoreOption("fceumm_palette", newLayout.palette)
                    engine.setCoreOption("fceumm_region", newLayout.region)
                    engine.setCoreOption("fceumm_sndquality", newLayout.soundQuality)
                    engine.setCoreOption("fceumm_cropoverscan", newLayout.cropOverscan)
                },
                onClose = { showSettings = false }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// GameSurfaceView — Android SurfaceView for hardware-accelerated rendering
// ---------------------------------------------------------------------------
@Composable
private fun GameSurfaceView(
    engine: NesEngine,
    gameAspect: Float,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        engine.setSurface(holder.surface)
                    }

                    override fun surfaceChanged(
                        holder: SurfaceHolder,
                        format: Int,
                        width: Int,
                        height: Int
                    ) {
                        // SurfaceFlinger handles scaling; nothing to do here
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        engine.setSurface(null)
                    }
                })
                // Use default z-order (below View hierarchy) so Compose UI
                // (virtual buttons, menus) draws on top of the game surface.
                holder.setFormat(android.graphics.PixelFormat.RGBX_8888)
            }
        },
        modifier = modifier
    )
}

// ---------------------------------------------------------------------------
// On-screen controller — modern FC buttons with individual positioning
// ---------------------------------------------------------------------------
@Composable
private fun OnScreenController(
    padLayout: PadLayout,
    padBits: Int,
    onPadBitsChange: (Int) -> Unit,
    turboBits: Int,
    onTurboBitsChange: (Int) -> Unit,
    surfaceSize: IntSize
) {
    val opacity = padLayout.opacity

    Box(modifier = Modifier.fillMaxSize()) {
        // D-pad
        ModernDpad(
            layout = padLayout.dpad,
            surfaceSize = surfaceSize,
            opacity = opacity,
            pressed = padBits and 0xF0,
            onChange = { dirBits ->
                onPadBitsChange((padBits and 0x0F) or dirBits)
            }
        )

        // A button
        ActionButton(
            label = "A",
            color = Color(0xFFE74C3C),
            layout = padLayout.btnA,
            surfaceSize = surfaceSize,
            opacity = opacity,
            isPressed = padBits and 0x01 != 0,
            onPress = { onPadBitsChange(padBits or 0x01) },
            onRelease = { onPadBitsChange(padBits and 0xFE) }
        )

        // B button
        ActionButton(
            label = "B",
            color = Color(0xFFE67E22),
            layout = padLayout.btnB,
            surfaceSize = surfaceSize,
            opacity = opacity,
            isPressed = padBits and 0x02 != 0,
            onPress = { onPadBitsChange(padBits or 0x02) },
            onRelease = { onPadBitsChange(padBits and 0xFD) }
        )

        // Turbo A button
        TurboButton(
            label = "A",
            color = Color(0xFFE74C3C),
            layout = padLayout.btnTurboA,
            surfaceSize = surfaceSize,
            opacity = opacity,
            isPressed = turboBits and 0x01 != 0,
            onPress = { onTurboBitsChange(turboBits or 0x01) },
            onRelease = { onTurboBitsChange(turboBits and 0xFE) }
        )

        // Turbo B button
        TurboButton(
            label = "B",
            color = Color(0xFFE67E22),
            layout = padLayout.btnTurboB,
            surfaceSize = surfaceSize,
            opacity = opacity,
            isPressed = turboBits and 0x02 != 0,
            onPress = { onTurboBitsChange(turboBits or 0x02) },
            onRelease = { onTurboBitsChange(turboBits and 0xFD) }
        )

        // Start button
        PillButton(
            label = "START",
            layout = padLayout.btnStart,
            surfaceSize = surfaceSize,
            opacity = opacity,
            isPressed = padBits and 0x08 != 0,
            onPress = { onPadBitsChange(padBits or 0x08) },
            onRelease = { onPadBitsChange(padBits and 0xF7) }
        )

        // Select button
        PillButton(
            label = "SELECT",
            layout = padLayout.btnSelect,
            surfaceSize = surfaceSize,
            opacity = opacity,
            isPressed = padBits and 0x04 != 0,
            onPress = { onPadBitsChange(padBits or 0x04) },
            onRelease = { onPadBitsChange(padBits and 0xFB) }
        )
    }
}

// ---------------------------------------------------------------------------
// Helper: convert ButtonLayout to pixel offset from screen top-left
// ---------------------------------------------------------------------------
private fun buttonOffset(layout: ButtonLayout, surfaceSize: IntSize): Pair<Float, Float> {
    val px = surfaceSize.width * layout.x
    val py = surfaceSize.height * layout.y
    return px to py
}

// ---------------------------------------------------------------------------
// Modern D-pad — rounded cross with directional indicators
// ---------------------------------------------------------------------------
@Composable
private fun ModernDpad(
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    pressed: Int, // bits 4-7: Up Down Left Right
    onChange: (Int) -> Unit
) {
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize)
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }

    var activeDir by remember { mutableStateOf(0) }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (px - sizePx / 2).toInt(),
                    (py - sizePx / 2).toInt()
                )
            }
            .size(sizeDp)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        val pos = change.position
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        val dx = pos.x - cx
                        val dy = pos.y - cy
                        val absX = kotlin.math.abs(dx)
                        val absY = kotlin.math.abs(dy)
                        val deadZone = size.width * 0.12f

                        val newDir = when {
                            absX < deadZone && absY < deadZone -> 0
                            absY > absX && dy < 0 -> 0x10 // Up
                            absY > absX && dy > 0 -> 0x20 // Down
                            absX > absY && dx < 0 -> 0x40 // Left
                            absX > absY && dx > 0 -> 0x80 // Right
                            else -> activeDir
                        }
                        if (newDir != activeDir) {
                            activeDir = newDir
                            onChange(newDir)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val armLen = size.width * 0.32f
            val armThick = size.width * 0.34f
            val radius = armThick * 0.35f

            // Background circle (base)
            drawCircle(
                color = Color(0xFF1A1F2E).copy(alpha = opacity * 0.5f),
                radius = size.width * 0.48f,
                center = Offset(cx, cy)
            )

            // Cross arms (rounded rectangles)
            val crossColor = Color(0xFF2A3040).copy(alpha = opacity)
            // Horizontal arm
            drawRoundRect(
                color = crossColor,
                topLeft = Offset(cx - armLen, cy - armThick / 2),
                size = Size(armLen * 2, armThick),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
            )
            // Vertical arm
            drawRoundRect(
                color = crossColor,
                topLeft = Offset(cx - armThick / 2, cy - armLen),
                size = Size(armThick, armLen * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius)
            )

            // Directional indicators (arrows/triangles)
            val dirs = listOf(
                Triple(0f, -1f, 0x10), // Up
                Triple(0f, 1f, 0x20),  // Down
                Triple(-1f, 0f, 0x40), // Left
                Triple(1f, 0f, 0x80)   // Right
            )
            for ((dx, dy, bit) in dirs) {
                val ax = cx + dx * armLen * 0.55f
                val ay = cy + dy * armLen * 0.55f
                val isActive = (activeDir and bit) != 0
                val indColor = if (isActive) Color(0xFFFFD66B) else Color(0x88FFFFFF)
                drawTriangle(ax, ay, dx, dy, armThick * 0.22f, indColor)
            }

            // Center circle
            drawCircle(
                color = Color(0xFF1A1F2E).copy(alpha = opacity),
                radius = armThick * 0.28f,
                center = Offset(cx, cy)
            )
        }
    }
}

// Draw a triangle (directional arrow)
private fun DrawScope.drawTriangle(
    cx: Float, cy: Float, dx: Float, dy: Float, size: Float, color: Color
) {
    val sx = cx - dy * size
    val sy = cy + dx * size
    val ex = cx + dy * size
    val ey = cy - dx * size
    val tx = cx + dx * size * 1.5f
    val ty = cy + dy * size * 1.5f
    drawPath(
        path = androidx.compose.ui.graphics.Path().apply {
            moveTo(sx, sy)
            lineTo(ex, ey)
            lineTo(tx, ty)
            close()
        },
        color = color
    )
}

// ---------------------------------------------------------------------------
// Action button — circular A/B button with modern styling
// ---------------------------------------------------------------------------
@Composable
private fun ActionButton(
    label: String,
    color: Color,
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    isPressed: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize)
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (px - sizePx / 2).toInt(),
                    (py - sizePx / 2).toInt()
                )
            }
            .size(sizeDp)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        try { tryAwaitRelease() } finally { onRelease() }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.width * 0.46f

            // Outer ring
            drawCircle(
                color = color.copy(alpha = opacity * 0.3f),
                radius = r + 3.dp.toPx(),
                center = Offset(cx, cy)
            )
            // Main button
            drawCircle(
                color = if (isPressed) color.copy(alpha = opacity * 1.5f) else color.copy(alpha = opacity),
                radius = r,
                center = Offset(cx, cy)
            )
            // Inner highlight
            drawCircle(
                color = Color.White.copy(alpha = if (isPressed) 0.1f else 0.15f),
                radius = r * 0.7f,
                center = Offset(cx - r * 0.15f, cy - r * 0.15f)
            )
        }
        Text(
            label,
            color = Color.White,
            fontSize = (sizeDp.value * 0.35f).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}

// ---------------------------------------------------------------------------
// Turbo button — smaller circular button with turbo indicator
// ---------------------------------------------------------------------------
@Composable
private fun TurboButton(
    label: String,
    color: Color,
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    isPressed: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize)
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (px - sizePx / 2).toInt(),
                    (py - sizePx / 2).toInt()
                )
            }
            .size(sizeDp)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        try { tryAwaitRelease() } finally { onRelease() }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.width * 0.44f

            // Dashed outer ring (turbo indicator)
            drawCircle(
                color = color.copy(alpha = opacity * 0.4f),
                radius = r + 2.dp.toPx(),
                center = Offset(cx, cy),
                style = Stroke(width = 1.5.dp.toPx())
            )
            // Main button
            drawCircle(
                color = if (isPressed) color.copy(alpha = opacity * 1.5f) else color.copy(alpha = opacity * 0.7f),
                radius = r,
                center = Offset(cx, cy)
            )
        }
        Text(
            label,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = (sizeDp.value * 0.32f).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
    }
}

// ---------------------------------------------------------------------------
// Pill button — Start/Select button
// ---------------------------------------------------------------------------
@Composable
private fun PillButton(
    label: String,
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    isPressed: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit
) {
    val sizeDp = layout.sizeDp.dp
    val widthDp = sizeDp * 2.2f
    val heightDp = sizeDp * 0.7f
    val (px, py) = buttonOffset(layout, surfaceSize)
    val density = LocalDensity.current
    val wPx = with(density) { widthDp.toPx() }
    val hPx = with(density) { heightDp.toPx() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (px - wPx / 2).toInt(),
                    (py - hPx / 2).toInt()
                )
            }
            .size(width = widthDp, height = heightDp)
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onPress()
                        try { tryAwaitRelease() } finally { onRelease() }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val r = h * 0.4f

            drawRoundRect(
                color = if (isPressed) Color(0xFF3A4050).copy(alpha = opacity * 1.5f)
                        else Color(0xFF2A3040).copy(alpha = opacity),
                topLeft = Offset(0f, 0f),
                size = Size(w, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
            )
            // Inner highlight line
            drawRoundRect(
                color = Color.White.copy(alpha = if (isPressed) 0.05f else 0.1f),
                topLeft = Offset(w * 0.1f, h * 0.15f),
                size = Size(w * 0.8f, h * 0.25f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(r * 0.5f, r * 0.5f)
            )
        }
        Text(
            label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = (sizeDp.value * 0.22f).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
    }
}

// ---------------------------------------------------------------------------
// Menu overlay — semi-transparent panel at top
// ---------------------------------------------------------------------------
@Composable
private fun MenuOverlay(
    gameTitle: String,
    running: Boolean,
    fastForward: Boolean,
    onTogglePause: () -> Unit,
    onToggleFastForward: () -> Unit,
    onScreenshot: () -> Unit,
    onSaveState: () -> Unit,
    onLayoutEditor: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit
) {
    // Dimmed full-screen background — tap to close
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .pointerInput(Unit) {
                detectTapGestures { onClose() }
            }
    )

    // Menu panel at top
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = gameTitle,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp)
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onTogglePause) {
            Icon(
                if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = "暂停/继续",
                tint = Color.White
            )
        }
        IconButton(onClick = onToggleFastForward) {
            Icon(
                Icons.Rounded.FastForward,
                contentDescription = "快进",
                tint = if (fastForward) Color(0xFFFFD66B) else Color.White
            )
        }
        IconButton(onClick = onScreenshot) {
            Icon(Icons.Rounded.CameraAlt, contentDescription = "截图", tint = Color.White)
        }
        IconButton(onClick = onSaveState) {
            Icon(Icons.Rounded.Save, contentDescription = "存档", tint = Color.White)
        }
        IconButton(onClick = onLayoutEditor) {
            Icon(Icons.Rounded.Tune, contentDescription = "手柄布局", tint = Color.White)
        }
        IconButton(onClick = onSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = "设置", tint = Color.White)
        }
        IconButton(onClick = onExit) {
            Icon(Icons.Rounded.Close, contentDescription = "退出", tint = Color(0xFFFF6B6B))
        }
    }
}

// ---------------------------------------------------------------------------
// Pad layout editor — drag to move, pinch to resize each button
// No sliders — pure touch interaction
// ---------------------------------------------------------------------------
@Composable
private fun PadLayoutEditor(
    padLayout: PadLayout,
    onLayoutChange: (PadLayout) -> Unit,
    surfaceSize: IntSize,
    onClose: () -> Unit
) {
    // Dimmed background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
    )

    // Top toolbar
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "拖动移动 · 双指缩放",
            color = Color.White,
            fontSize = 13.sp
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onLayoutChange(PadLayout()) }) {
            Icon(Icons.Rounded.Refresh, contentDescription = "重置", tint = Color(0xFFFFD66B))
        }
        IconButton(onClick = onClose) {
            Text("完成", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        }
    }

    // Draggable button previews
    Box(modifier = Modifier.fillMaxSize()) {
        // D-pad
        EditableButton(
            layout = padLayout.dpad,
            surfaceSize = surfaceSize,
            color = Color(0xFFFFD66B),
            label = "D-Pad",
            onMove = { dx, dy ->
                val nx = (padLayout.dpad.x + dx).coerceIn(0.05f, 0.45f)
                val ny = (padLayout.dpad.y + dy).coerceIn(0.3f, 0.97f)
                onLayoutChange(padLayout.copy(dpad = padLayout.dpad.copy(x = nx, y = ny)))
            },
            onResize = { scale ->
                val ns = (padLayout.dpad.sizeDp * scale).toInt().coerceIn(80, 220)
                onLayoutChange(padLayout.copy(dpad = padLayout.dpad.copy(sizeDp = ns)))
            }
        )

        // A button
        EditableButton(
            layout = padLayout.btnA,
            surfaceSize = surfaceSize,
            color = Color(0xFFE74C3C),
            label = "A",
            onMove = { dx, dy ->
                val nx = (padLayout.btnA.x + dx).coerceIn(0.4f, 0.95f)
                val ny = (padLayout.btnA.y + dy).coerceIn(0.3f, 0.97f)
                onLayoutChange(padLayout.copy(btnA = padLayout.btnA.copy(x = nx, y = ny)))
            },
            onResize = { scale ->
                val ns = (padLayout.btnA.sizeDp * scale).toInt().coerceIn(40, 120)
                onLayoutChange(padLayout.copy(btnA = padLayout.btnA.copy(sizeDp = ns)))
            }
        )

        // B button
        EditableButton(
            layout = padLayout.btnB,
            surfaceSize = surfaceSize,
            color = Color(0xFFE67E22),
            label = "B",
            onMove = { dx, dy ->
                val nx = (padLayout.btnB.x + dx).coerceIn(0.4f, 0.95f)
                val ny = (padLayout.btnB.y + dy).coerceIn(0.3f, 0.97f)
                onLayoutChange(padLayout.copy(btnB = padLayout.btnB.copy(x = nx, y = ny)))
            },
            onResize = { scale ->
                val ns = (padLayout.btnB.sizeDp * scale).toInt().coerceIn(40, 120)
                onLayoutChange(padLayout.copy(btnB = padLayout.btnB.copy(sizeDp = ns)))
            }
        )

        // Turbo A
        EditableButton(
            layout = padLayout.btnTurboA,
            surfaceSize = surfaceSize,
            color = Color(0xFFE74C3C),
            label = "TA",
            onMove = { dx, dy ->
                val nx = (padLayout.btnTurboA.x + dx).coerceIn(0.4f, 0.95f)
                val ny = (padLayout.btnTurboA.y + dy).coerceIn(0.3f, 0.97f)
                onLayoutChange(padLayout.copy(btnTurboA = padLayout.btnTurboA.copy(x = nx, y = ny)))
            },
            onResize = { scale ->
                val ns = (padLayout.btnTurboA.sizeDp * scale).toInt().coerceIn(30, 90)
                onLayoutChange(padLayout.copy(btnTurboA = padLayout.btnTurboA.copy(sizeDp = ns)))
            }
        )

        // Turbo B
        EditableButton(
            layout = padLayout.btnTurboB,
            surfaceSize = surfaceSize,
            color = Color(0xFFE67E22),
            label = "TB",
            onMove = { dx, dy ->
                val nx = (padLayout.btnTurboB.x + dx).coerceIn(0.4f, 0.95f)
                val ny = (padLayout.btnTurboB.y + dy).coerceIn(0.3f, 0.97f)
                onLayoutChange(padLayout.copy(btnTurboB = padLayout.btnTurboB.copy(x = nx, y = ny)))
            },
            onResize = { scale ->
                val ns = (padLayout.btnTurboB.sizeDp * scale).toInt().coerceIn(30, 90)
                onLayoutChange(padLayout.copy(btnTurboB = padLayout.btnTurboB.copy(sizeDp = ns)))
            }
        )

        // Start
        EditableButton(
            layout = padLayout.btnStart,
            surfaceSize = surfaceSize,
            color = Color(0xFF4A90D9),
            label = "START",
            onMove = { dx, dy ->
                val nx = (padLayout.btnStart.x + dx).coerceIn(0.1f, 0.9f)
                val ny = (padLayout.btnStart.y + dy).coerceIn(0.3f, 0.97f)
                onLayoutChange(padLayout.copy(btnStart = padLayout.btnStart.copy(x = nx, y = ny)))
            },
            onResize = { scale ->
                val ns = (padLayout.btnStart.sizeDp * scale).toInt().coerceIn(30, 100)
                onLayoutChange(padLayout.copy(btnStart = padLayout.btnStart.copy(sizeDp = ns)))
            }
        )

        // Select
        EditableButton(
            layout = padLayout.btnSelect,
            surfaceSize = surfaceSize,
            color = Color(0xFF4A90D9),
            label = "SELECT",
            onMove = { dx, dy ->
                val nx = (padLayout.btnSelect.x + dx).coerceIn(0.1f, 0.9f)
                val ny = (padLayout.btnSelect.y + dy).coerceIn(0.3f, 0.97f)
                onLayoutChange(padLayout.copy(btnSelect = padLayout.btnSelect.copy(x = nx, y = ny)))
            },
            onResize = { scale ->
                val ns = (padLayout.btnSelect.sizeDp * scale).toInt().coerceIn(30, 100)
                onLayoutChange(padLayout.copy(btnSelect = padLayout.btnSelect.copy(sizeDp = ns)))
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Editable button — draggable + pinch-to-resize
// ---------------------------------------------------------------------------
@Composable
private fun EditableButton(
    layout: ButtonLayout,
    surfaceSize: IntSize,
    color: Color,
    label: String,
    onMove: (deltaX: Float, deltaY: Float) -> Unit,
    onResize: (scale: Float) -> Unit
) {
    val sizeDp = layout.sizeDp.dp
    val (px, py) = buttonOffset(layout, surfaceSize)
    val sizePx = with(LocalDensity.current) { sizeDp.toPx() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (px - sizePx / 2).toInt(),
                    (py - sizePx / 2).toInt()
                )
            }
            .size(sizeDp)
            .pointerInput(layout) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (pan.x != 0f || pan.y != 0f) {
                        val dx = pan.x / surfaceSize.width
                        val dy = pan.y / surfaceSize.height
                        onMove(dx, dy)
                    }
                    if (zoom != 1f) {
                        onResize(zoom)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.width * 0.46f
            // Semi-transparent fill
            drawCircle(
                color = color.copy(alpha = 0.35f),
                radius = r,
                center = Offset(size.width / 2f, size.height / 2f)
            )
            // Border (dashed look via stroke)
            drawCircle(
                color = color,
                radius = r,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = 2.dp.toPx())
            )
        }
        Text(
            label,
            color = color,
            fontSize = (sizeDp.value * 0.2f).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
    }
}

// ---------------------------------------------------------------------------
// Settings panel — core options (NTSC filter, aspect ratio, palette, etc.)
// ---------------------------------------------------------------------------
@Composable
private fun SettingsPanel(
    padLayout: PadLayout,
    onLayoutChange: (PadLayout) -> Unit,
    onClose: () -> Unit
) {
    // Dimmed background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .pointerInput(Unit) {
                detectTapGestures { onClose() }
            }
    )

    // Settings panel
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("核心设置", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭", tint = Color.White)
            }
        }
        Spacer(Modifier.size(8.dp))

        // NTSC Filter
        DropdownSetting(
            label = "NTSC 滤镜",
            options = listOf("disabled" to "关闭", "composite" to "复合", "svideo" to "S-Video", "rgb" to "RGB"),
            selected = padLayout.ntscFilter,
            onSelect = { onLayoutChange(padLayout.copy(ntscFilter = it)) }
        )

        // Aspect Ratio
        DropdownSetting(
            label = "画面比例",
            options = listOf("8:7" to "8:7 (原始)", "4:3" to "4:3 (电视)", "NTSC" to "NTSC", "PAL" to "PAL"),
            selected = padLayout.aspectRatio,
            onSelect = { onLayoutChange(padLayout.copy(aspectRatio = it)) }
        )

        // Palette
        DropdownSetting(
            label = "调色板",
            options = listOf("default" to "默认", "dq" to "Dragon Quest", "nx" to "Nestopia", "asq" to "AspiringSquire", "rp2" to "Real"),
            selected = padLayout.palette,
            onSelect = { onLayoutChange(padLayout.copy(palette = it)) }
        )

        // Region
        DropdownSetting(
            label = "区域",
            options = listOf("Auto" to "自动", "NTSC" to "NTSC", "PAL" to "PAL", "Dendy" to "Dendy"),
            selected = padLayout.region,
            onSelect = { onLayoutChange(padLayout.copy(region = it)) }
        )

        // Sound Quality
        DropdownSetting(
            label = "音质",
            options = listOf("Low" to "低", "High" to "高", "Very High" to "非常高"),
            selected = padLayout.soundQuality,
            onSelect = { onLayoutChange(padLayout.copy(soundQuality = it)) }
        )

        // Crop Overscan
        DropdownSetting(
            label = "裁剪过扫描",
            options = listOf("disabled" to "关闭", "enabled" to "开启"),
            selected = padLayout.cropOverscan,
            onSelect = { onLayoutChange(padLayout.copy(cropOverscan = it)) }
        )

        Spacer(Modifier.size(8.dp))
        Text(
            "修改后即时生效。NTSC 滤镜会增加画面宽度。",
            color = Color(0xFF8899AA),
            fontSize = 11.sp
        )
    }
}

// ---------------------------------------------------------------------------
// Dropdown setting item
// ---------------------------------------------------------------------------
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Spacer(Modifier.weight(1f))
        Box {
            Text(
                selectedLabel,
                color = Color(0xFFFFD66B),
                fontSize = 13.sp,
                modifier = Modifier
                    .pointerInput(Unit) { detectTapGestures { expanded = true } }
                    .padding(8.dp)
            )
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text, fontSize = 13.sp) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

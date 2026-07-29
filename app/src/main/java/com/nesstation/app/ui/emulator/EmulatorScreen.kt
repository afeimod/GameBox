package com.nesstation.app.ui.emulator

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.storage.ButtonLayout
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.storage.PadLayoutStore
import kotlinx.coroutines.delay

// ---------------------------------------------------------------------------
// Button types for multi-touch tracking
// ---------------------------------------------------------------------------
private enum class BtnType { DPAD, A, B, TURBO_A, TURBO_B, START, SELECT }

// Bit masks for NES controller
private const val BTN_UP = 0x10
private const val BTN_DOWN = 0x20
private const val BTN_LEFT = 0x40
private const val BTN_RIGHT = 0x80
private const val BTN_A = 0x01
private const val BTN_B = 0x02
private const val BTN_SELECT = 0x04
private const val BTN_START = 0x08

// ---------------------------------------------------------------------------
// Main Emulator Screen
// ---------------------------------------------------------------------------
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
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    var showMenu by remember { mutableStateOf(false) }
    var showLayoutEditor by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var padLayout by remember { mutableStateOf(PadLayoutStore.load(context)) }

    // Apply core options on load and when they change
    LaunchedEffect(padLayout.ntscFilter, padLayout.aspectRatio, padLayout.palette,
                   padLayout.region, padLayout.soundQuality, padLayout.cropOverscan) {
        applyCoreOptions(engine, padLayout)
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
                    val origName = game.title.ifBlank { romPath.substringAfterLast('/') }
                    val ext = when {
                        origName.endsWith(".fds", ignoreCase = true) -> ".fds"
                        origName.endsWith(".unf", ignoreCase = true) -> ".unf"
                        origName.endsWith(".unif", ignoreCase = true) -> ".unif"
                        romPath.contains(".fds", ignoreCase = true) -> ".fds"
                        romPath.contains(".unf", ignoreCase = true) -> ".unf"
                        else -> ".nes"
                    }
                    val tempFile = java.io.File(context.cacheDir, "temp_rom$ext")
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

    DisposableEffect(Unit) {
        onDispose { engine.unload() }
    }

    LaunchedEffect(fastForward) { engine.setFastForward(fastForward) }
    LaunchedEffect(running) { engine.setPaused(!running) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (loaded) {
            GameSurfaceView(
                engine = engine,
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
                onPadBits = { bits -> engine.setPad1(bits) }
            )
        }

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

        if (loaded && showSettings) {
            SettingsPanel(
                padLayout = padLayout,
                onLayoutChange = { newLayout ->
                    padLayout = newLayout
                    PadLayoutStore.save(context, newLayout)
                    applyCoreOptions(engine, newLayout)
                },
                onClose = { showSettings = false }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Apply core options to engine — maps PadLayout fields to FCEUmm option keys
// ---------------------------------------------------------------------------
private fun applyCoreOptions(engine: NesEngine, layout: PadLayout) {
    engine.setCoreOption("fceumm_ntsc_filter", layout.ntscFilter)
    engine.setCoreOption("fceumm_aspect", layout.aspectRatio)
    engine.setCoreOption("fceumm_palette", layout.palette)
    engine.setCoreOption("fceumm_region", layout.region)
    engine.setCoreOption("fceumm_sndquality", layout.soundQuality)
    // Overscan: map "enabled"/"disabled" to the 4 individual crop keys
    val cropVal = if (layout.cropOverscan == "enabled") "8" else "0"
    engine.setCoreOption("fceumm_overscan_h_left", cropVal)
    engine.setCoreOption("fceumm_overscan_h_right", cropVal)
    engine.setCoreOption("fceumm_overscan_v_top", cropVal)
    engine.setCoreOption("fceumm_overscan_v_bottom", cropVal)
}

// ---------------------------------------------------------------------------
// GameSurfaceView
// ---------------------------------------------------------------------------
@Composable
private fun GameSurfaceView(
    engine: NesEngine,
    modifier: Modifier = Modifier
) {
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
        modifier = modifier
    )
}

// ---------------------------------------------------------------------------
// OnScreenController — SINGLE pointerInput for true multi-touch
// ---------------------------------------------------------------------------
@Composable
private fun OnScreenController(
    padLayout: PadLayout,
    surfaceSize: IntSize,
    onPadBits: (Int) -> Unit
) {
    val density = LocalDensity.current
    val opacity = padLayout.opacity

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

    // Turbo auto-fire: simulates rapid short taps (press 2 frames, release 4 frames)
    // FC turbo buttons rapidly press/release the A/B button at ~10Hz
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
                val taRect = btnRect(padLayout.btnTurboA)
                val tbRect = btnRect(padLayout.btnTurboB)
                val startRect = btnRect(padLayout.btnStart, 2.2f, 0.7f)
                val selectRect = btnRect(padLayout.btnSelect, 2.2f, 0.7f)

                // Process a pointer DOWN at the given position.
                // Returns true if the pointer landed on a button.
                fun processDown(pid: Long, pos: Offset) {
                    val btnType = when {
                        dpadRect.contains(pos) -> BtnType.DPAD
                        aRect.contains(pos) -> BtnType.A
                        bRect.contains(pos) -> BtnType.B
                        taRect.contains(pos) -> BtnType.TURBO_A
                        tbRect.contains(pos) -> BtnType.TURBO_B
                        startRect.contains(pos) -> BtnType.START
                        selectRect.contains(pos) -> BtnType.SELECT
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
                        }
                        activePointers[pid] = btnType to (if (turboBits != 0) turboBits else bits)
                        if (turboBits != 0) {
                            turboState = turboState or turboBits
                        } else {
                            visualState = visualState or bits
                        }
                    }
                }

                // Main gesture loop:
                // awaitFirstDown() captures the FIRST finger's DOWN event (so
                // single-touch works immediately). Then awaitPointerEventScope
                // gives us AwaitPointerEventScope which has awaitPointerEvent()
                // for processing all subsequent events (multi-touch, moves, ups).
                while (true) {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    processDown(firstDown.id.value, firstDown.position)

                    var pressedCount = 1 // firstDown gave us one pressed finger

                    awaitPointerEventScope {
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
                                            BtnType.START, BtnType.SELECT -> {
                                                visualState = visualState and heldBits.inv()
                                            }
                                            BtnType.TURBO_A, BtnType.TURBO_B -> {
                                                turboState = turboState and heldBits.inv()
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
        // Turbo A
        TurboButtonCanvas("A", Color(0xFFE74C3C), padLayout.btnTurboA, surfaceSize, opacity, turboState and BTN_A != 0)
        // Turbo B
        TurboButtonCanvas("B", Color(0xFFE67E22), padLayout.btnTurboB, surfaceSize, opacity, turboState and BTN_B != 0)
        // Start
        PillButtonCanvas("START", padLayout.btnStart, surfaceSize, opacity, visualState and BTN_START != 0)
        // Select
        PillButtonCanvas("SELECT", padLayout.btnSelect, surfaceSize, opacity, visualState and BTN_SELECT != 0)
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

// ---------------------------------------------------------------------------
// Menu overlay
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
        Text(gameTitle, color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onTogglePause) { Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "暂停/继续", tint = Color.White) }
        IconButton(onClick = onToggleFastForward) { Icon(Icons.Rounded.FastForward, "快进", tint = if (fastForward) Color(0xFFFFD66B) else Color.White) }
        IconButton(onClick = onScreenshot) { Icon(Icons.Rounded.CameraAlt, "截图", tint = Color.White) }
        IconButton(onClick = onSaveState) { Icon(Icons.Rounded.Save, "存档", tint = Color.White) }
        IconButton(onClick = onLayoutEditor) { Icon(Icons.Rounded.Tune, "手柄布局", tint = Color.White) }
        IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "设置", tint = Color.White) }
        IconButton(onClick = onExit) { Icon(Icons.Rounded.Close, "退出", tint = Color(0xFFFF6B6B)) }
    }
}

// ---------------------------------------------------------------------------
// Pad layout editor — drag to move (fixed), tap to select + slider for size
// ---------------------------------------------------------------------------
@Composable
private fun PadLayoutEditor(
    padLayout: PadLayout,
    onLayoutChange: (PadLayout) -> Unit,
    surfaceSize: IntSize,
    onClose: () -> Unit
) {
    val density = LocalDensity.current
    var selectedBtn by remember { mutableStateOf<BtnType?>(null) }

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

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSelect()
                    dragStartX = down.position.x
                    dragStartY = down.position.y
                    layoutStartX = layout.x
                    layoutStartY = layout.y

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            // Compute total drag in pixels, convert to fraction of screen
                            val dxPx = change.position.x - dragStartX
                            val dyPx = change.position.y - dragStartY
                            val dxFrac = dxPx / surfaceSize.width
                            val dyFrac = dyPx / surfaceSize.height
                            // Pass ABSOLUTE target position (initial + drag offset)
                            onMove(layoutStartX + dxFrac, layoutStartY + dyFrac)
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

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSelect()
                    dragStartX = down.position.x
                    dragStartY = down.position.y
                    layoutStartX = layout.x
                    layoutStartY = layout.y

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            val dxPx = change.position.x - dragStartX
                            val dyPx = change.position.y - dragStartY
                            val dxFrac = dxPx / surfaceSize.width
                            val dyFrac = dyPx / surfaceSize.height
                            onMove(layoutStartX + dxFrac, layoutStartY + dyFrac)
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

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(width = widthDp, height = heightDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onSelect()
                    dragStartX = down.position.x
                    dragStartY = down.position.y
                    layoutStartX = layout.x
                    layoutStartY = layout.y

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChanged()) {
                            val dxPx = change.position.x - dragStartX
                            val dyPx = change.position.y - dragStartY
                            val dxFrac = dxPx / surfaceSize.width
                            val dyFrac = dyPx / surfaceSize.height
                            onMove(layoutStartX + dxFrac, layoutStartY + dyFrac)
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
// ---------------------------------------------------------------------------
@Composable
private fun SettingsPanel(
    padLayout: PadLayout,
    onLayoutChange: (PadLayout) -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x88000000))
    )

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp)
            .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("核心设置", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "关闭", tint = Color.White) }
        }
        Spacer(Modifier.size(8.dp))

        DropdownSetting("NTSC 滤镜",
            listOf("disabled" to "关闭", "composite" to "复合", "svideo" to "S-Video", "rgb" to "RGB", "monochrome" to "黑白"),
            padLayout.ntscFilter
        ) { onLayoutChange(padLayout.copy(ntscFilter = it)) }

        DropdownSetting("画面比例",
            listOf("8:7 PAR" to "8:7 (原始)", "4:3" to "4:3 (电视)", "PP" to "像素完美"),
            padLayout.aspectRatio
        ) { onLayoutChange(padLayout.copy(aspectRatio = it)) }

        DropdownSetting("调色板",
            listOf("default" to "默认", "dq" to "Dragon Quest", "nx" to "Nestopia", "asq" to "AspiringSquire", "rp2" to "Real"),
            padLayout.palette
        ) { onLayoutChange(padLayout.copy(palette = it)) }

        DropdownSetting("区域",
            listOf("Auto" to "自动", "NTSC" to "NTSC", "PAL" to "PAL", "Dendy" to "Dendy"),
            padLayout.region
        ) { onLayoutChange(padLayout.copy(region = it)) }

        DropdownSetting("音质",
            listOf("Low" to "低", "High" to "高", "Very High" to "非常高"),
            padLayout.soundQuality
        ) { onLayoutChange(padLayout.copy(soundQuality = it)) }

        DropdownSetting("裁剪过扫描",
            listOf("disabled" to "关闭", "enabled" to "开启"),
            padLayout.cropOverscan
        ) { onLayoutChange(padLayout.copy(cropOverscan = it)) }

        Spacer(Modifier.size(8.dp))
        Text("修改后即时生效。设置与主界面设置同步。", color = Color(0xFF8899AA), fontSize = 11.sp)
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

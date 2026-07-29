package com.nesstation.app.ui.emulator

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.storage.PadLayoutStore
import kotlinx.coroutines.delay

/**
 * The in-game surface — full-bleed framebuffer, on-screen D-pad + A/B/Start/Select.
 *
 * No permanent top bar. Press the device Back button (or tap the screen edge)
 * to bring up a menu overlay with pause / save / screenshot / fast-forward / settings.
 *
 * Controller layout (position, size, opacity) is fully configurable via [PadLayoutStore].
 */
@Composable
fun EmulatorScreen(
    game: GameEntry,
    onExit: () -> Unit
) {
    val engine = remember { NesEngine.get() }
    val context = LocalContext.current
    val density = LocalDensity.current
    var running by remember { mutableStateOf(true) }
    var fastForward by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var frameTick by remember { mutableIntStateOf(0) }
    var padBits by remember { mutableStateOf(0) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = remember { Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888) }
    val imageBitmap = remember { bitmap.asImageBitmap() }

    // Menu overlay state — hidden by default, shown via Back button
    var showMenu by remember { mutableStateOf(false) }

    // Pad layout — loaded from persistent storage
    var padLayout by remember { mutableStateOf(PadLayoutStore.load(context)) }
    var showLayoutEditor by remember { mutableStateOf(false) }

    // Handle Back button: first press opens menu, second press exits
    BackHandler(enabled = !showMenu) {
        showMenu = true
    }
    BackHandler(enabled = showMenu && !showLayoutEditor) {
        showMenu = false
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

    // Apply fast-forward / running state to engine
    LaunchedEffect(fastForward) { engine.setFastForward(fastForward) }

    // Frame polling loop
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        while (true) {
            engine.setPad1(padBits)
            if (running) {
                bitmap.setPixels(engine.frameBuffer, 0, 256, 0, 0, 256, 240)
                frameTick++
            }
            delay(if (fastForward) 8 else 16)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // === Game surface — full bleed, no overlay ===
        if (loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { fastForward = !fastForward },
                            onLongPress = { showMenu = true }
                        )
                    }
                    .onSizeChanged { surfaceSize = it }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val arGame = 256f / 240f
                    val arScreen = size.width / size.height
                    val (w, h) = if (arScreen > arGame) {
                        size.height * arGame to size.height
                    } else {
                        size.width to size.width / arGame
                    }
                    val x = (size.width - w) / 2
                    val y = (size.height - h) / 2
                    drawRect(Color.Black, topLeft = Offset.Zero, size = size)
                    drawImage(
                        image = imageBitmap,
                        dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt())
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = errorMsg ?: "正在加载…",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }

        // === On-screen controller — show when loaded, pad visible, no menu ===
        if (loaded && padLayout.showPad && !showMenu && !showLayoutEditor && surfaceSize != IntSize.Zero) {
            OnScreenController(
                padLayout = padLayout,
                padBits = padBits,
                onPadBitsChange = { padBits = it },
                surfaceSize = surfaceSize,
                density = density.density
            )
        }

        // === Menu overlay — shown via Back button or long-press ===
        if (loaded && showMenu && !showLayoutEditor) {
            MenuOverlay(
                gameTitle = game.title,
                running = running,
                fastForward = fastForward,
                onTogglePause = { running = !running },
                onToggleFastForward = { fastForward = !fastForward },
                onScreenshot = { /* TODO */ },
                onSaveState = { /* TODO */ },
                onLayoutEditor = { showLayoutEditor = true },
                onSettings = { /* TODO */ },
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
                density = density.density,
                onClose = { showLayoutEditor = false }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Menu overlay — semi-transparent panel, does NOT block the game screen
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
// On-screen controller — fixed pixel-to-dp conversion
// ---------------------------------------------------------------------------
@Composable
private fun OnScreenController(
    padLayout: PadLayout,
    padBits: Int,
    onPadBitsChange: (Int) -> Unit,
    surfaceSize: IntSize,
    density: Float
) {
    val dpadSize = (130 * padLayout.dpadScale).dp
    val btnSize = (60 * padLayout.btnScale).dp
    val selSize = (52 * padLayout.btnScale).dp
    val opacity = padLayout.opacity

    // Convert fractional positions to dp using actual pixel dimensions
    val dpadOffsetXDp = (surfaceSize.width * padLayout.dpadX / density).dp
    val dpadOffsetYDp = (surfaceSize.height * padLayout.dpadY / density).dp
    val btnOffsetXDp = (surfaceSize.width * padLayout.btnX / density).dp
    val btnOffsetYDp = (surfaceSize.height * padLayout.btnY / density).dp

    Box(modifier = Modifier.fillMaxSize()) {
        // D-pad on the left
        DPad(
            onChange = { bits -> onPadBitsChange((padBits and 0x0F) or bits) },
            modifier = Modifier
                .size(dpadSize)
                .align(Alignment.TopStart)
                .padding(start = dpadOffsetXDp, top = dpadOffsetYDp),
            opacity = opacity
        )

        // A/B/Start/Select on the right
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = btnOffsetXDp, top = btnOffsetYDp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PadButton("B", Color(0xFFE67E22), btnSize, opacity) { bit ->
                    onPadBitsChange(if (bit) padBits or 0x02 else padBits and 0xFD)
                }
                PadButton("A", Color(0xFFE74C3C), btnSize, opacity) { bit ->
                    onPadBitsChange(if (bit) padBits or 0x01 else padBits and 0xFE)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PadButton("SEL", Color(0xFF1E2A3A), selSize, opacity) { bit ->
                    onPadBitsChange(if (bit) padBits or 0x04 else padBits and 0xFB)
                }
                PadButton("STA", Color(0xFF1E2A3A), selSize, opacity) { bit ->
                    onPadBitsChange(if (bit) padBits or 0x08 else padBits and 0xF7)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pad layout editor
// ---------------------------------------------------------------------------
@Composable
private fun PadLayoutEditor(
    padLayout: PadLayout,
    onLayoutChange: (PadLayout) -> Unit,
    surfaceSize: IntSize,
    density: Float,
    onClose: () -> Unit
) {
    // Dimmed background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
    )

    // Editor panel at top
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        Text("手柄布局设置", color = Color.White, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Spacer(Modifier.size(12.dp))

        Text("方向键大小: ${"%.1f".format(padLayout.dpadScale)}x", color = Color.White, fontSize = 12.sp)
        Slider(
            value = padLayout.dpadScale,
            onValueChange = { onLayoutChange(padLayout.copy(dpadScale = it)) },
            valueRange = 0.5f..2.0f,
            steps = 14
        )

        Text("按键大小: ${"%.1f".format(padLayout.btnScale)}x", color = Color.White, fontSize = 12.sp)
        Slider(
            value = padLayout.btnScale,
            onValueChange = { onLayoutChange(padLayout.copy(btnScale = it)) },
            valueRange = 0.5f..2.0f,
            steps = 14
        )

        Text("透明度: ${"%.0f".format(padLayout.opacity * 100)}%", color = Color.White, fontSize = 12.sp)
        Slider(
            value = padLayout.opacity,
            onValueChange = { onLayoutChange(padLayout.copy(opacity = it)) },
            valueRange = 0.3f..1.0f,
            steps = 13
        )

        Text("方向键位置 (或拖动方向键调整)", color = Color.White, fontSize = 12.sp)
        Row {
            Text("X: ${"%.2f".format(padLayout.dpadX)}", color = Color(0xFFFFD66B), fontSize = 11.sp, modifier = Modifier.padding(end = 16.dp))
            Text("Y: ${"%.2f".format(padLayout.dpadY)}", color = Color(0xFFFFD66B), fontSize = 11.sp)
        }
        Slider(
            value = padLayout.dpadX,
            onValueChange = { onLayoutChange(padLayout.copy(dpadX = it)) },
            valueRange = 0f..0.4f
        )
        Slider(
            value = padLayout.dpadY,
            onValueChange = { onLayoutChange(padLayout.copy(dpadY = it)) },
            valueRange = 0.3f..0.95f
        )

        Text("按键位置 (或拖动按键调整)", color = Color.White, fontSize = 12.sp)
        Row {
            Text("X: ${"%.2f".format(padLayout.btnX)}", color = Color(0xFFFFD66B), fontSize = 11.sp, modifier = Modifier.padding(end = 16.dp))
            Text("Y: ${"%.2f".format(padLayout.btnY)}", color = Color(0xFFFFD66B), fontSize = 11.sp)
        }
        Slider(
            value = padLayout.btnX,
            onValueChange = { onLayoutChange(padLayout.copy(btnX = it)) },
            valueRange = 0.4f..0.95f
        )
        Slider(
            value = padLayout.btnY,
            onValueChange = { onLayoutChange(padLayout.copy(btnY = it)) },
            valueRange = 0.3f..0.95f
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("显示手柄", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = padLayout.showPad,
                onCheckedChange = { onLayoutChange(padLayout.copy(showPad = it)) }
            )
        }

        Spacer(Modifier.size(8.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { onLayoutChange(PadLayout()) }) {
                Text("重置", color = Color(0xFFFFD66B), fontSize = 13.sp)
            }
            IconButton(onClick = onClose) {
                Text("完成", color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
    }

    // Preview pads (draggable) — use proper pixel-to-dp conversion
    val dpadSize = (130 * padLayout.dpadScale).dp
    val btnSize = (60 * padLayout.btnScale).dp
    val selSize = (52 * padLayout.btnScale).dp
    val dpadOffsetXDp = if (surfaceSize.width > 0) (surfaceSize.width * padLayout.dpadX / density).dp else 0.dp
    val dpadOffsetYDp = if (surfaceSize.height > 0) (surfaceSize.height * padLayout.dpadY / density).dp else 0.dp
    val btnOffsetXDp = if (surfaceSize.width > 0) (surfaceSize.width * padLayout.btnX / density).dp else 0.dp
    val btnOffsetYDp = if (surfaceSize.height > 0) (surfaceSize.height * padLayout.btnY / density).dp else 0.dp

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(dpadSize)
                .align(Alignment.TopStart)
                .padding(start = dpadOffsetXDp, top = dpadOffsetYDp)
                .background(Color(0x66FFD66B), RoundedCornerShape(20.dp))
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val newX = (padLayout.dpadX + dragAmount.x / surfaceSize.width).coerceIn(0f, 0.4f)
                        val newY = (padLayout.dpadY + dragAmount.y / surfaceSize.height).coerceIn(0.3f, 0.95f)
                        onLayoutChange(padLayout.copy(dpadX = newX, dpadY = newY))
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Text("D-Pad", color = Color.White, fontSize = 10.sp)
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = btnOffsetXDp, top = btnOffsetYDp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val newX = (padLayout.btnX + dragAmount.x / surfaceSize.width).coerceIn(0.4f, 0.95f)
                        val newY = (padLayout.btnY + dragAmount.y / surfaceSize.height).coerceIn(0.3f, 0.95f)
                        onLayoutChange(padLayout.copy(btnX = newX, btnY = newY))
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(btnSize).background(Color(0x66E67E22), CircleShape), Alignment.Center) { Text("B", color = Color.White, fontSize = 12.sp) }
                Box(Modifier.size(btnSize).background(Color(0x66E74C3C), CircleShape), Alignment.Center) { Text("A", color = Color.White, fontSize = 12.sp) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(selSize).background(Color(0x661E2A3A), CircleShape), Alignment.Center) { Text("SEL", color = Color.White, fontSize = 10.sp) }
                Box(Modifier.size(selSize).background(Color(0x661E2A3A), CircleShape), Alignment.Center) { Text("STA", color = Color.White, fontSize = 10.sp) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// D-pad component
// ---------------------------------------------------------------------------
@Composable
private fun DPad(
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.75f
) {
    var pressed by remember { mutableStateOf(0) }
    Box(
        modifier = modifier
            .background(Color((0xFF1E2A3A).toInt()).copy(alpha = opacity * 0.3f), RoundedCornerShape(20.dp))
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
                        val deadZone = size.width * 0.10f
                        val newPressed = when {
                            absX < deadZone && absY < deadZone -> 0
                            absY > absX && dy < 0 -> 0x10
                            absY > absX && dy > 0 -> 0x20
                            absX > absY && dx < 0 -> 0x40
                            absX > absY && dx > 0 -> 0x80
                            else -> pressed
                        }
                        if (newPressed != pressed) {
                            pressed = newPressed
                            onChange(newPressed)
                            change.consume()
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val arm = size.width * 0.30f
            val thick = size.width * 0.30f
            drawRect(Color(0xFF1E2A3A).copy(alpha = opacity), topLeft = Offset(cx - arm, cy - thick / 2), size = Size(arm * 2, thick))
            drawRect(Color(0xFF1E2A3A).copy(alpha = opacity), topLeft = Offset(cx - thick / 2, cy - arm), size = Size(thick, arm * 2))
            listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).forEach { (dx, dy) ->
                val ax = cx + dx * (arm * 0.55f)
                val ay = cy + dy * (arm * 0.55f)
                val isActive = (dx == 0 && dy == -1 && pressed and 0x10 != 0) ||
                               (dx == 0 && dy == 1  && pressed and 0x20 != 0) ||
                               (dx == -1 && pressed and 0x40 != 0) ||
                               (dx == 1  && pressed and 0x80 != 0)
                drawCircle(
                    color = if (isActive) Color(0xFFFFD66B) else Color(0x66FFFFFF),
                    radius = thick * 0.25f,
                    center = Offset(ax, ay)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Action button component
// ---------------------------------------------------------------------------
@Composable
private fun PadButton(
    label: String,
    color: Color,
    size: androidx.compose.ui.unit.Dp,
    opacity: Float,
    onSet: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = opacity))
            .pointerInput(label) {
                detectTapGestures(
                    onPress = {
                        onSet(true)
                        try { tryAwaitRelease() } finally { onSet(false) }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

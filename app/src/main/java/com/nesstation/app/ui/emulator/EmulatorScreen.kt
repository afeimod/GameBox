package com.nesstation.app.ui.emulator

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.model.GameEntry
import kotlinx.coroutines.delay

/**
 * The in-game surface — full-bleed framebuffer, on-screen D-pad + A/B/Start/Select,
 * top overlay with pause / save / screenshot / fast-forward / settings.
 *
 * On entry, loads the ROM via [NesEngine] and starts the 60Hz emulation loop.
 * The frame buffer is polled from the engine and blitted to a Compose Canvas.
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
    var frameTick by remember { mutableIntStateOf(0) }
    var padBits by remember { mutableStateOf(0) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }
    val bitmap = remember { Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888) }
    val imageBitmap = remember { bitmap.asImageBitmap() }

    // Load ROM on entry
    LaunchedEffect(game) {
        val romPath = game.romPath
        if (romPath.isNullOrEmpty()) {
            errorMsg = "该游戏未关联 ROM 文件"
            return@LaunchedEffect
        }
        val romFile = java.io.File(romPath)
        if (!romFile.exists()) {
            errorMsg = "ROM 文件不存在: $romPath"
            return@LaunchedEffect
        }
        val filesDir = context.filesDir.absolutePath
        val ok = engine.loadRom(romFile, filesDir, filesDir) { }
        if (!ok) {
            errorMsg = engine.lastError().ifEmpty { "ROM 加载失败" }
        } else {
            loaded = true
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose { engine.unload() }
    }

    // Apply fast-forward / running state to engine
    LaunchedEffect(fastForward) { engine.fastForward = fastForward }

    // Frame polling loop — copy engine's frame buffer to the Bitmap at ~60Hz
    LaunchedEffect(loaded) {
        if (!loaded) return@LaunchedEffect
        while (true) {
            engine.setPad1(padBits)
            if (running) {
                // Copy the 256x240 ARGB pixels from the engine's shared buffer
                bitmap.setPixels(engine.frameBuffer, 0, 256, 0, 0, 256, 240)
                frameTick++ // trigger recomposition
            }
            delay(if (fastForward) 8 else 16)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Game surface or error message
        if (loaded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { fastForward = !fastForward })
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
                    // Letterbox
                    drawRect(Color.Black, topLeft = Offset.Zero, size = size)
                    drawImage(
                        image = imageBitmap,
                        dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(w.toInt(), h.toInt())
                    )
                    // Scanline overlay (subtle)
                    val scanColor = Color(0x14000000)
                    var sy = y
                    while (sy < y + h) {
                        drawRect(scanColor, topLeft = Offset(x, sy), size = Size(w, 1f))
                        sy += 2
                    }
                }
            }
        } else {
            // Loading / error state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = errorMsg ?: "正在加载…",
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }

        // Top overlay controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .background(Color(0x66000000), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = game.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            if (loaded) {
                IconButton(onClick = { running = !running }) {
                    Icon(if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White)
                }
                IconButton(onClick = { fastForward = !fastForward }) {
                    Icon(Icons.Rounded.FastForward, contentDescription = null, tint = if (fastForward) Color(0xFFFFD66B) else Color.White)
                }
            }
            IconButton(onClick = { /* TODO: takeScreenshot */ }) {
                Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = Color.White)
            }
            IconButton(onClick = { /* TODO: save state */ }) {
                Icon(Icons.Rounded.Save, contentDescription = null, tint = Color.White)
            }
            IconButton(onClick = { /* TODO: open settings sheet */ }) {
                Icon(Icons.Rounded.Settings, contentDescription = null, tint = Color.White)
            }
            IconButton(onClick = { onExit() }) {
                Icon(Icons.Rounded.Close, contentDescription = null, tint = Color.White)
            }
        }

        // Bottom on-screen pad (only show when loaded)
        if (loaded) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DPad(
                    onChange = { bits -> padBits = (padBits and 0x0F) or bits },
                    modifier = Modifier.size(160.dp)
                )
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PadButton("A", Color(0xFFE74C3C), size = 64) { bit ->
                            padBits = if (bit) padBits or 0x01 else padBits and 0xFE
                        }
                        PadButton("B", Color(0xFFE67E22), size = 64) { bit ->
                            padBits = if (bit) padBits or 0x02 else padBits and 0xFD
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PadButton("SEL", Color(0xFF1E2A3A), size = 56) { bit ->
                            padBits = if (bit) padBits or 0x04 else padBits and 0xFB
                        }
                        PadButton("STA", Color(0xFF1E2A3A), size = 56) { bit ->
                            padBits = if (bit) padBits or 0x08 else padBits and 0xF7
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DPad(
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(0) }
    Box(
        modifier = modifier
            .background(Color(0x331E2A3A), RoundedCornerShape(20.dp))
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
            // Horizontal arm
            drawRect(Color(0xFF1E2A3A), topLeft = Offset(cx - arm, cy - thick / 2), size = Size(arm * 2, thick))
            // Vertical arm
            drawRect(Color(0xFF1E2A3A), topLeft = Offset(cx - thick / 2, cy - arm), size = Size(thick, arm * 2))
            // Direction indicators
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

@Composable
private fun PadButton(
    label: String,
    color: Color,
    size: Int,
    onSet: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color)
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

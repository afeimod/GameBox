package com.nesstation.app.ui.emulator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import com.nesstation.app.core.engine.J2meEngine
import com.nesstation.app.core.storage.ButtonLayout
import com.nesstation.app.core.storage.PadLayout

// ===========================================================================
// J2ME On-Screen Controller — two modes:
//   1. "gamepad" mode: D-pad + A/B/X/Y/Start/Select (standard gamepad).
//      Mirrors the generic OnScreenController visual style.
//   2. "phone"   mode: 12-key numeric keypad (1-9, *, 0, #) + soft keys
//      (SOFT_LEFT / SOFT_RIGHT) + FIRE + END. Mirrors the classic J2ME
//      phone layout that MIDlets expect for T9 input, menu navigation,
//      and number entry.
//
// Both modes:
//   - Transparent backgrounds (alpha = padLayout.opacity * 0.5)
//   - Work in both landscape AND portrait
//   - Support multi-touch (one pointer per button)
//   - Dispatch button state via J2meEngine.setPad1(bits)
//   - Mode toggle button in the top-right corner
// ===========================================================================

// Button bit constants for J2ME. Reuses the generic BTN_* constants for
// gamepad keys (defined in EmulatorScreen.kt) and defines J2ME-specific
// bits for the numeric keypad (bits 16-25, which are unused by BTN_*).
private const val J2ME_BTN_UP = 0x0010
private const val J2ME_BTN_DOWN = 0x0020
private const val J2ME_BTN_LEFT = 0x0040
private const val J2ME_BTN_RIGHT = 0x0080
private const val J2ME_BTN_A = 0x0001          // → Canvas.KEY_FIRE
private const val J2ME_BTN_B = 0x0002          // → Canvas.KEY_SOFT_LEFT
private const val J2ME_BTN_X = 0x0100          // → Canvas.KEY_SOFT_RIGHT
private const val J2ME_BTN_Y = 0x0200          // → Canvas.KEY_STAR
private const val J2ME_BTN_SELECT = 0x0004     // → Canvas.KEY_POUND
private const val J2ME_BTN_START = 0x0008      // → Canvas.KEY_END
private const val J2ME_BTN_NUM_0 = 0x010000
private const val J2ME_BTN_NUM_1 = 0x020000
private const val J2ME_BTN_NUM_2 = 0x040000
private const val J2ME_BTN_NUM_3 = 0x080000
private const val J2ME_BTN_NUM_4 = 0x100000
private const val J2ME_BTN_NUM_5 = 0x200000
private const val J2ME_BTN_NUM_6 = 0x400000
private const val J2ME_BTN_NUM_7 = 0x800000
private const val J2ME_BTN_NUM_8 = 0x1000000
private const val J2ME_BTN_NUM_9 = 0x2000000

/**
 * Entry point for the J2ME on-screen controller. Switches between gamepad
 * and phone modes based on `padLayout.javaInputMode`. The mode toggle
 * button is always visible in the top-right corner.
 */
@Composable
fun J2meOnScreenController(
    engine: J2meEngine,
    padLayout: PadLayout,
    surfaceSize: IntSize,
    isPortrait: Boolean,
    onToggleMode: () -> Unit
) {
    val opacity = (padLayout.opacity * 0.5f).coerceIn(0.15f, 0.85f)

    Box(modifier = Modifier.fillMaxSize()) {
        if (padLayout.javaInputMode == "phone") {
            J2mePhoneOverlay(
                engine = engine,
                opacity = opacity,
                isPortrait = isPortrait,
                surfaceSize = surfaceSize
            )
        } else {
            J2meGamepadOverlay(
                engine = engine,
                padLayout = padLayout,
                opacity = opacity,
                isPortrait = isPortrait,
                surfaceSize = surfaceSize
            )
        }

        // Mode switch button — top-right corner, always on top.
        J2meModeSwitchButton(
            isPhone = padLayout.javaInputMode == "phone",
            opacity = opacity,
            onClick = onToggleMode,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        )
    }
}

// ===========================================================================
// Gamepad mode — D-pad + A/B/X/Y/Start/Select
// ===========================================================================

@Composable
private fun J2meGamepadOverlay(
    engine: J2meEngine,
    padLayout: PadLayout,
    opacity: Float,
    isPortrait: Boolean,
    surfaceSize: IntSize
) {
    val density = LocalDensity.current

    // Reuse the generic PadLayout button positions (landscape/portrait).
    val dpad = if (isPortrait) padLayout.dpadP else padLayout.dpad
    val btnA = if (isPortrait) padLayout.btnAP else padLayout.btnA
    val btnB = if (isPortrait) padLayout.btnBP else padLayout.btnB
    val btnX = if (isPortrait) padLayout.btnXP else padLayout.btnX
    val btnY = if (isPortrait) padLayout.btnYP else padLayout.btnY
    val btnStart = if (isPortrait) padLayout.btnStartP else padLayout.btnStart
    val btnSelect = if (isPortrait) padLayout.btnSelectP else padLayout.btnSelect

    // Track active pointers and compute OR'd bit mask.
    val activePointers = remember { mutableMapOf<Long, Int>() }
    var visualState by remember { mutableStateOf(0) }

    val sendState = remember(engine) {
        { bits: Int ->
            visualState = bits
            engine.setPad1(bits)
        }
    }

    // Compute hit rects
    fun btnRect(layout: ButtonLayout, widthScale: Float = 1f, heightScale: Float = 1f): Rect {
        val sizePx = with(density) { layout.sizeDp.dp.toPx() }
        val w = sizePx * widthScale
        val h = sizePx * heightScale
        val cx = surfaceSize.width * layout.x
        val cy = surfaceSize.height * layout.y
        return Rect(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    val dpadRect = btnRect(dpad)
    val aRect = btnRect(btnA)
    val bRect = btnRect(btnB)
    val xRect = btnRect(btnX)
    val yRect = btnRect(btnY)
    val startRect = btnRect(btnStart, 2.2f, 0.7f)
    val selectRect = btnRect(btnSelect, 2.2f, 0.7f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(surfaceSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val id = down.id.value
                    val bits = hitTestGamepad(down.position, dpadRect, aRect, bRect, xRect, yRect, startRect, selectRect)
                    if (bits != 0) {
                        activePointers[id] = bits
                        sendState(combineBits(activePointers))
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            val pid = change.id.value
                            val newBits = if (change.pressed) {
                                hitTestGamepad(change.position, dpadRect, aRect, bRect, xRect, yRect, startRect, selectRect)
                            } else 0
                            val oldBits = activePointers[pid] ?: 0
                            if (oldBits != newBits) {
                                activePointers[pid] = newBits
                                sendState(combineBits(activePointers))
                            }
                            if (!change.pressed) {
                                activePointers.remove(pid)
                                sendState(combineBits(activePointers))
                            }
                        }
                    }
                }
            }
    ) {
        // Draw D-pad
        J2meDpadCanvas(dpad, surfaceSize, opacity, visualState and 0xF0)
        // Draw A
        J2meActionButton("A", Color(0xFFE74C3C), btnA, surfaceSize, opacity, visualState and J2ME_BTN_A != 0)
        // Draw B
        J2meActionButton("B", Color(0xFFE67E22), btnB, surfaceSize, opacity, visualState and J2ME_BTN_B != 0)
        // Draw X
        J2meActionButton("X", Color(0xFF3498DB), btnX, surfaceSize, opacity, visualState and J2ME_BTN_X != 0)
        // Draw Y
        J2meActionButton("Y", Color(0xFF2ECC71), btnY, surfaceSize, opacity, visualState and J2ME_BTN_Y != 0)
        // Draw Start
        J2mePillButton("START", btnStart, surfaceSize, opacity, visualState and J2ME_BTN_START != 0)
        // Draw Select
        J2mePillButton("SELECT", btnSelect, surfaceSize, opacity, visualState and J2ME_BTN_SELECT != 0)
    }
}

private fun hitTestGamepad(
    pos: Offset,
    dpadRect: Rect, aRect: Rect, bRect: Rect, xRect: Rect, yRect: Rect,
    startRect: Rect, selectRect: Rect
): Int {
    var bits = 0
    if (dpadRect.contains(pos)) {
        val cx = dpadRect.center.x
        val cy = dpadRect.center.y
        val hw = dpadRect.width / 2f
        val hh = dpadRect.height / 2f
        val dx = (pos.x - cx) / hw.coerceAtLeast(0.001f)
        val dy = (pos.y - cy) / hh.coerceAtLeast(0.001f)
        if (dy < -0.3f) bits = bits or J2ME_BTN_UP
        if (dy > 0.3f) bits = bits or J2ME_BTN_DOWN
        if (dx < -0.3f) bits = bits or J2ME_BTN_LEFT
        if (dx > 0.3f) bits = bits or J2ME_BTN_RIGHT
    }
    if (aRect.contains(pos)) bits = bits or J2ME_BTN_A
    if (bRect.contains(pos)) bits = bits or J2ME_BTN_B
    if (xRect.contains(pos)) bits = bits or J2ME_BTN_X
    if (yRect.contains(pos)) bits = bits or J2ME_BTN_Y
    if (startRect.contains(pos)) bits = bits or J2ME_BTN_START
    if (selectRect.contains(pos)) bits = bits or J2ME_BTN_SELECT
    return bits
}

private fun combineBits(pointers: Map<Long, Int>): Int {
    var result = 0
    for ((_, bits) in pointers) result = result or bits
    return result
}

// ===========================================================================
// Phone mode — 12-key numeric keypad + soft keys + FIRE + END
// ===========================================================================

@Composable
private fun J2mePhoneOverlay(
    engine: J2meEngine,
    opacity: Float,
    isPortrait: Boolean,
    surfaceSize: IntSize
) {
    val density = LocalDensity.current

    // Layout parameters — keypad occupies the bottom-center area.
    // In portrait: narrower, taller grid. In landscape: wider, shorter.
    val keySizeDp = if (isPortrait) 56.dp else 52.dp
    val keyGapDp = 6.dp
    val keyGapPx = with(density) { keyGapDp.toPx() }
    val keySizePx = with(density) { keySizeDp.toPx() }

    // 4 rows × 3 cols grid. Positions are relative to the keypad's top-left.
    // Keypad is anchored to the bottom of the screen.
    val cols = 3
    val rows = 4
    val gridWidth = cols * keySizePx + (cols - 1) * keyGapPx
    val gridHeight = rows * keySizePx + (rows - 1) * keyGapPx
    val gridLeft = (surfaceSize.width - gridWidth) / 2f
    val gridTop = surfaceSize.height - gridHeight - 24.dp.toPx()

    // Key labels and their bit values, in reading order (row-major).
    // Row 0: 1 2 3
    // Row 1: 4 5 6
    // Row 2: 7 8 9
    // Row 3: * 0 #
    val keys = listOf(
        "1" to J2ME_BTN_NUM_1,
        "2" to J2ME_BTN_NUM_2,
        "3" to J2ME_BTN_NUM_3,
        "4" to J2ME_BTN_NUM_4,
        "5" to J2ME_BTN_NUM_5,
        "6" to J2ME_BTN_NUM_6,
        "7" to J2ME_BTN_NUM_7,
        "8" to J2ME_BTN_NUM_8,
        "9" to J2ME_BTN_NUM_9,
        "*" to J2ME_BTN_Y,           // → Canvas.KEY_STAR
        "0" to J2ME_BTN_NUM_0,
        "#" to J2ME_BTN_SELECT,       // → Canvas.KEY_POUND
    )

    // Soft key + action key positions (above the keypad, left/right).
    val softKeySizePx = with(density) { 48.dp.toPx() }
    val softKeyY = gridTop - softKeySizePx - 12.dp.toPx()
    val softLeftX = gridLeft - softKeySizePx - 12.dp.toPx()
    val softRightX = gridLeft + gridWidth + 12.dp.toPx()

    val activePointers = remember { mutableMapOf<Long, Int>() }
    var visualState by remember { mutableStateOf(0) }

    val sendState = remember(engine) {
        { bits: Int ->
            visualState = bits
            engine.setPad1(bits)
        }
    }

    // Compute hit rects for each key
    fun keyRect(row: Int, col: Int): Rect {
        val x = gridLeft + col * (keySizePx + keyGapPx)
        val y = gridTop + row * (keySizePx + keyGapPx)
        return Rect(x, y, x + keySizePx, y + keySizePx)
    }

    val keyRects = keys.mapIndexed { idx, _ ->
        val row = idx / cols
        val col = idx % cols
        keyRect(row, col)
    }

    val softLeftRect = Rect(softLeftX, softKeyY, softLeftX + softKeySizePx, softKeyY + softKeySizePx)
    val softRightRect = Rect(softRightX, softKeyY, softRightX + softKeySizePx, softKeyY + softKeySizePx)
    val fireRect = Rect(
        (surfaceSize.width - softKeySizePx) / 2f,
        softKeyY,
        (surfaceSize.width + softKeySizePx) / 2f,
        softKeyY + softKeySizePx
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(surfaceSize) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val id = down.id.value
                    val bits = hitTestPhone(
                        down.position, keyRects, keys,
                        softLeftRect, softRightRect, fireRect
                    )
                    if (bits != 0) {
                        activePointers[id] = bits
                        sendState(combineBits(activePointers))
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            val pid = change.id.value
                            val newBits = if (change.pressed) {
                                hitTestPhone(
                                    change.position, keyRects, keys,
                                    softLeftRect, softRightRect, fireRect
                                )
                            } else 0
                            val oldBits = activePointers[pid] ?: 0
                            if (oldBits != newBits) {
                                activePointers[pid] = newBits
                                sendState(combineBits(activePointers))
                            }
                            if (!change.pressed) {
                                activePointers.remove(pid)
                                sendState(combineBits(activePointers))
                            }
                        }
                    }
                }
            }
    ) {
        // Draw soft keys row
        J2mePillButton("SOFT_L", ButtonLayout(softLeftX / surfaceSize.width, softKeyY / surfaceSize.height, 48), surfaceSize, opacity, visualState and J2ME_BTN_B != 0)
        J2mePillButton("FIRE", ButtonLayout(fireRect.center.x / surfaceSize.width, softKeyY / surfaceSize.height, 48), surfaceSize, opacity, visualState and J2ME_BTN_A != 0)
        J2mePillButton("SOFT_R", ButtonLayout(softRightX / surfaceSize.width, softKeyY / surfaceSize.height, 48), surfaceSize, opacity, visualState and J2ME_BTN_X != 0)

        // Draw numeric keypad
        keys.forEachIndexed { idx, (label, bit) ->
            val row = idx / cols
            val col = idx % cols
            val cx = gridLeft + col * (keySizePx + keyGapPx) + keySizePx / 2f
            val cy = gridTop + row * (keySizePx + keyGapPx) + keySizePx / 2f
            val isPressed = visualState and bit != 0
            J2meNumericKey(label, cx, cy, keySizePx, opacity, isPressed)
        }
    }
}

private fun hitTestPhone(
    pos: Offset,
    keyRects: List<Rect>,
    keys: List<Pair<String, Int>>,
    softLeftRect: Rect,
    softRightRect: Rect,
    fireRect: Rect
): Int {
    for (i in keyRects.indices) {
        if (keyRects[i].contains(pos)) return keys[i].second
    }
    if (softLeftRect.contains(pos)) return J2ME_BTN_B
    if (softRightRect.contains(pos)) return J2ME_BTN_X
    if (fireRect.contains(pos)) return J2ME_BTN_A
    return 0
}

// ===========================================================================
// Visual components
// ===========================================================================

@Composable
private fun J2meDpadCanvas(
    layout: ButtonLayout,
    surfaceSize: IntSize,
    opacity: Float,
    pressedDirs: Int
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val px = (surfaceSize.width * layout.x - sizePx / 2f)
    val py = (surfaceSize.height * layout.y - sizePx / 2f)

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

            val armColor = Color(0xFF2C2C38).copy(alpha = opacity)
            val pressedColor = Color(0xFFFFD66B).copy(alpha = opacity * 0.8f)

            drawRoundRect(armColor, Offset(cx - armLen, cy - halfThick), Size(armLen * 2, armThick), cr)
            drawRoundRect(armColor, Offset(cx - halfThick, cy - armLen), Size(armThick, armLen * 2), cr)

            val armTipLen = armLen * 0.42f
            val tipThick = armThick * 0.7f
            if (pressedDirs and J2ME_BTN_UP != 0) drawRoundRect(pressedColor, Offset(cx - tipThick / 2, cy - armLen), Size(tipThick, armTipLen), cr)
            if (pressedDirs and J2ME_BTN_DOWN != 0) drawRoundRect(pressedColor, Offset(cx - tipThick / 2, cy + armLen - armTipLen), Size(tipThick, armTipLen), cr)
            if (pressedDirs and J2ME_BTN_LEFT != 0) drawRoundRect(pressedColor, Offset(cx - armLen, cy - tipThick / 2), Size(armTipLen, tipThick), cr)
            if (pressedDirs and J2ME_BTN_RIGHT != 0) drawRoundRect(pressedColor, Offset(cx + armLen - armTipLen, cy - tipThick / 2), Size(armTipLen, tipThick), cr)
        }
    }
}

@Composable
private fun J2meActionButton(
    label: String, color: Color, layout: ButtonLayout, surfaceSize: IntSize, opacity: Float, isPressed: Boolean
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val px = (surfaceSize.width * layout.x - sizePx / 2f)
    val py = (surfaceSize.height * layout.y - sizePx / 2f)

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(sizeDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val r = size.width * 0.46f
            drawCircle(color.copy(alpha = opacity * 0.3f), r + 3.dp.toPx(), Offset(cx, cy))
            drawCircle(
                if (isPressed) color.copy(alpha = (opacity * 1.5f).coerceAtMost(1f))
                else color.copy(alpha = opacity),
                r, Offset(cx, cy)
            )
            drawCircle(Color.White.copy(alpha = if (isPressed) 0.1f else 0.15f), r * 0.7f, Offset(cx - r * 0.15f, cy - r * 0.15f))
        }
        Text(label, color = Color.White, fontSize = (sizeDp.value * 0.35f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun J2mePillButton(
    label: String, layout: ButtonLayout, surfaceSize: IntSize, opacity: Float, isPressed: Boolean
) {
    val density = LocalDensity.current
    val sizeDp = layout.sizeDp.dp
    val sizePx = with(density) { sizeDp.toPx() }
    val px = (surfaceSize.width * layout.x - sizePx / 2f)
    val py = (surfaceSize.height * layout.y - sizePx / 2f)

    Box(
        modifier = Modifier
            .offset { IntOffset(px.toInt(), py.toInt()) }
            .size(width = sizeDp * 2.2f, height = sizeDp * 0.7f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val rx = size.width * 0.46f
            val ry = size.height * 0.46f
            val color = Color(0xFF95A5A6).copy(alpha = if (isPressed) (opacity * 1.5f).coerceAtMost(1f) else opacity)
            drawCircle(color, ry + 2.dp.toPx(), Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5.dp.toPx()))
            drawCircle(color.copy(alpha = opacity * 0.5f), ry, Offset(cx, cy))
        }
        Text(label, color = Color.White, fontSize = (sizeDp.value * 0.28f).sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun J2meNumericKey(
    label: String,
    cx: Float,
    cy: Float,
    sizePx: Float,
    opacity: Float,
    isPressed: Boolean
) {
    val r = sizePx * 0.46f
    val bgColor = Color(0xFF1A1A22).copy(alpha = opacity)
    val fgColor = Color(0xFFFFD66B).copy(alpha = if (isPressed) (opacity * 1.3f).coerceAtMost(1f) else opacity)
    val borderColor = Color.White.copy(alpha = opacity * 0.4f)

    Box(
        modifier = Modifier
            .offset { IntOffset((cx - sizePx / 2).toInt(), (cy - sizePx / 2).toInt()) }
            .size(sizePx.dp)
            .clip(CircleShape)
            .background(bgColor)
            .border(1.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = fgColor,
            fontSize = (sizePx * 0.35f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ===========================================================================
// Mode switch button — top-right corner
// ===========================================================================

@Composable
private fun J2meModeSwitchButton(
    isPhone: Boolean,
    opacity: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = Color.Black.copy(alpha = opacity * 0.6f)
    val fgColor = Color.White.copy(alpha = opacity)
    val borderColor = Color.White.copy(alpha = opacity * 0.7f)
    // 🎮 = gamepad, ☎ = phone
    val label = if (isPhone) "\ud83c\udfae" else "\u260e\ufe0f"

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bgColor)
            .border(width = 1.dp, color = borderColor, shape = RoundedCornerShape(22.dp))
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
    ) {
        Text(
            text = label,
            color = fgColor,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

// ===========================================================================
// J2ME In-Game Menu Overlay
//
// Shows J2ME-relevant controls:
//   - Pause / Resume (toggle MIDlet running state)
//   - Overlay mode toggle (gamepad ↔ phone)
//   - Exit (unload MIDlet, return to game library)
//
// Does NOT show libretro-only features:
//   - Save / Load state (J2ME uses RMS, not native save states)
//   - Fast-forward (J2ME is event-driven, not frame-pumped)
//   - Screenshot (J2ME renders to its own SurfaceView)
//   - Reset (J2ME restart = unload + reload)
//   - Layout editor (J2ME overlay is mode-based, not position-based)
// ===========================================================================

@Composable
fun J2meMenuOverlay(
    gameTitle: String,
    running: Boolean,
    isPhoneMode: Boolean,
    isPortrait: Boolean = false,
    onTogglePause: () -> Unit,
    onToggleOverlayMode: () -> Unit,
    onClose: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x88000000))
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    // Don't consume — let horizontal drags reach the scrollable menu row.
                }
            }
    )
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (isPortrait) Alignment.TopCenter else Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .background(Color(0xDD1E2A3A), RoundedCornerShape(16.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                gameTitle,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                // Pause / Resume
                IconButton(onClick = onTogglePause) {
                    Icon(
                        if (running) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        "暂停/继续",
                        tint = Color.White
                    )
                }
                // Overlay mode toggle (gamepad ↔ phone)
                IconButton(onClick = onToggleOverlayMode) {
                    Icon(
                        Icons.Rounded.SwapHoriz,
                        if (isPhoneMode) "切换到手柄" else "切换到手机键盘",
                        tint = if (isPhoneMode) Color(0xFFFFD66B) else Color.White
                    )
                }
                Text(
                    if (isPhoneMode) "手机" else "手柄",
                    color = Color(0xFFFFD66B),
                    fontSize = 12.sp,
                    modifier = Modifier.clickable { onToggleOverlayMode() }
                )
                // Close menu
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Fullscreen, "隐藏菜单", tint = Color(0xFF4A90D9))
                }
                // Exit
                IconButton(onClick = onExit) {
                    Icon(Icons.Rounded.Close, "退出", tint = Color(0xFFFF6B6B))
                }
            }
        }
    }
}

package com.nesstation.app.ui.emulator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.engine.DosEngine
import com.nesstation.app.core.jni.DosKeys
import com.nesstation.app.core.storage.PadLayout
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// ===========================================================================
// DOS On-Screen Controller — two modes:
//   1. "gamepad" mode: transparent circular buttons (matches screenshots
//      from ponyemu.main): D-pad on the left, action buttons (Esc/Enter/Space/
//      Tab/Ctrl/Alt/Shift), mouse buttons (L/R/Middle), and a mode switch key.
//   2. "keyboard" mode: full QWERTY capsule-style transparent keyboard with
//      function keys, modifier keys, and arrow cluster.
// Both modes:
//   - Have transparent backgrounds (alpha = padLayout.opacity * 0.5)
//   - Work in both landscape AND portrait (auto-repositions based on isPortrait)
//   - Support multi-touch (one pointer per button)
//   - Dispatch events through DosEngine's inject* methods
// ===========================================================================

// Gamepad bit constants (used for the standard libretro gamepad input — the
// dosbox_pure core's auto-mapping converts these to DOS keys).
private const val BTN_UP = 0x10
private const val BTN_DOWN = 0x20
private const val BTN_LEFT = 0x40
private const val BTN_RIGHT = 0x80
private const val BTN_A = 0x01      // dosbox_pure → Enter
private const val BTN_B = 0x02      // dosbox_pure → Esc
private const val BTN_X = 0x400     // dosbox_pure → Space (libretro ID 10)
private const val BTN_Y = 0x800     // dosbox_pure → Tab   (libretro ID 11)
private const val BTN_L = 0x100     // dosbox_pure → mouse left  (libretro ID 8)
private const val BTN_R = 0x200     // dosbox_pure → mouse right (libretro ID 9)
private const val BTN_START = 0x08
private const val BTN_SELECT = 0x04

/**
 * The DOS on-screen controller — entry point. Switches between gamepad and
 * keyboard modes based on `padLayout.dosInputMode`. The mode toggle button is
 * always visible (top-right corner).
 *
 * @param engine the DOS engine — used to inject keyboard / mouse events
 * @param padLayout the persisted pad layout + DOSBox options (incl. dosInputMode)
 * @param surfaceSize the current surface size (for relative positioning)
 * @param isPortrait whether the device is currently in portrait orientation
 * @param onToggleMode callback to flip between "gamepad" / "keyboard" mode
 */
@Composable
fun DosOnScreenController(
    engine: DosEngine,
    padLayout: PadLayout,
    surfaceSize: IntSize,
    isPortrait: Boolean,
    onToggleMode: () -> Unit
) {
    val opacity = (padLayout.opacity * 0.5f).coerceIn(0.15f, 0.85f)

    Box(modifier = Modifier.fillMaxSize()) {
        // Mode switch button — top-right corner, always visible.
        ModeSwitchButton(
            isKeyboard = padLayout.dosInputMode == "keyboard",
            opacity = opacity,
            isPortrait = isPortrait,
            onClick = onToggleMode,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
        )

        if (padLayout.dosInputMode == "keyboard") {
            DosKeyboardOverlay(
                engine = engine,
                opacity = opacity,
                isPortrait = isPortrait,
                surfaceSize = surfaceSize
            )
        } else {
            DosGamepadOverlay(
                engine = engine,
                opacity = opacity,
                isPortrait = isPortrait,
                surfaceSize = surfaceSize
            )
        }
    }
}

// ===========================================================================
// Gamepad overlay — circular transparent buttons, matches screenshot style.
// Layout:
//   Left side: D-pad cross (up/down/left/right) — dispatches as standard
//              gamepad bits (auto-mapped by dosbox_pure to arrow keys).
//   Right side: 4 circular action buttons in a diamond:
//              Esc / Enter / Space / Tab — dispatched as keyboard keys.
//   Bottom-left: virtual mouse left/right buttons (circular) + drag area
//              in the screen center for mouse movement.
//   Bottom-right: Start (Enter), Select (Esc) pill buttons + Ctrl/Alt/Shift
//              modifier buttons for keyboard combinations.
// ===========================================================================

@Composable
private fun DosGamepadOverlay(
    engine: DosEngine,
    opacity: Float,
    isPortrait: Boolean,
    @Suppress("UNUSED_PARAMETER") surfaceSize: IntSize
) {
    val bgColor = Color.Black.copy(alpha = opacity * 0.5f)
    val fgColor = Color.White.copy(alpha = opacity)
    val pressedColor = Color(0xFFFFD66B).copy(alpha = opacity)
    val borderColor = Color.White.copy(alpha = opacity * 0.6f)

    // Track pressed buttons for visual feedback.
    var dpadState by remember { mutableStateOf(0) }   // bitfield of UP/DOWN/LEFT/RIGHT
    var pressedKeys by remember { mutableStateOf(setOf<Int>()) } // set of DosKeys codes
    var mouseLeft by remember { mutableStateOf(false) }
    var mouseRight by remember { mutableStateOf(false) }

    // Helper: push current dpad bits to the engine.
    fun pushDpad() {
        // Convert dpad bits to libretro JOYPAD bits and send.
        val bits = dpadState and 0xF0  // UP/DOWN/LEFT/RIGHT (bits 4-7)
        engine.setPad1(bits)
    }

    // Helper: dispatch keyboard key down/up.
    fun keyDown(code: Int) {
        pressedKeys = pressedKeys + code
        engine.injectKeyDown(code, 0)
    }
    fun keyUp(code: Int) {
        pressedKeys = pressedKeys - code
        engine.injectKeyUp(code, 0)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // === Mouse move: drag area covering the WHOLE screen ===
        // CRITICAL ORDERING: This must be the FIRST child of the Box so it
        // is drawn at the BOTTOM of the z-order. In Compose's Box, later
        // children are drawn on top — so buttons declared after this drag
        // area will receive touch events FIRST, and only touches that miss
        // all buttons fall through to this drag handler.
        //
        // The previous version declared this Box LAST (on top), which caused
        // it to intercept every touch event across the screen and made all
        // virtual buttons unresponsive (bug #3).
        //
        // We use detectDragGestures (not detectTapGestures) so taps/clicks
        // on buttons are NOT consumed by this handler — only actual drags
        // (movement) trigger mouse move injection.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { },
                        onDragEnd = { },
                        onDragCancel = { },
                        onDrag = { change: PointerInputChange, _ ->
                            val dx = change.positionChange().x
                            val dy = change.positionChange().y
                            if (dx != 0f || dy != 0f) {
                                // Scale up the delta for better mouse sensitivity.
                                engine.injectMouseMove((dx * 1.5f).toInt(), (dy * 1.5f).toInt())
                            }
                            change.consume()
                        }
                    )
                }
        )

        // === Left side: D-pad ===
        val dpadSizeDp = if (isPortrait) 120.dp else 140.dp
        val dpadOffsetX = if (isPortrait) 24.dp else 32.dp
        val dpadOffsetY = if (isPortrait) 80.dp else 24.dp

        CircularDpad(
            sizeDp = dpadSizeDp,
            bgColor = bgColor,
            fgColor = fgColor,
            borderColor = borderColor,
            pressedColor = pressedColor,
            pressed = dpadState,
            onPressedChange = { dir, pressed ->
                val bit = when (dir) {
                    "up" -> BTN_UP; "down" -> BTN_DOWN
                    "left" -> BTN_LEFT; "right" -> BTN_RIGHT
                    else -> 0
                }
                dpadState = if (pressed) dpadState or bit else dpadState and bit.inv()
                pushDpad()
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = dpadOffsetX, bottom = dpadOffsetY)
        )

        // === Right side: Action buttons (4 circular, diamond layout) ===
        // Top: Esc, Right: Enter, Bottom: Tab, Left: Space
        val actionSizeDp = if (isPortrait) 52.dp else 60.dp
        val actionOffsetX = if (isPortrait) 24.dp else 32.dp
        val actionOffsetY = if (isPortrait) 80.dp else 24.dp
        val actionSpacing = if (isPortrait) 56.dp else 66.dp

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = actionOffsetX, bottom = actionOffsetY),
            horizontalAlignment = Alignment.End
        ) {
            // Top row: Esc (top), then diamond: Enter / Space / Tab
            CircularKeyButton(
                label = "Esc",
                keyCode = DosKeys.ESCAPE,
                sizeDp = actionSizeDp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.ESCAPE in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.ESCAPE) else keyUp(DosKeys.ESCAPE) }
            )
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(actionSpacing - actionSizeDp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularKeyButton(
                    label = "Space",
                    keyCode = DosKeys.SPACE,
                    sizeDp = actionSizeDp,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.SPACE in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.SPACE) else keyUp(DosKeys.SPACE) }
                )
                // Spacer
                androidx.compose.foundation.layout.Spacer(Modifier.size(0.dp))
                CircularKeyButton(
                    label = "Enter",
                    keyCode = DosKeys.RETURN,
                    sizeDp = actionSizeDp,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = DosKeys.RETURN in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(DosKeys.RETURN) else keyUp(DosKeys.RETURN) }
                )
            }
            CircularKeyButton(
                label = "Tab",
                keyCode = DosKeys.TAB,
                sizeDp = actionSizeDp,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.TAB in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.TAB) else keyUp(DosKeys.TAB) }
            )
        }

        // === Modifier row (Ctrl/Alt/Shift) — bottom-center, transparent pills ===
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            PillKeyButton("Ctrl", DosKeys.LCTRL, bgColor, fgColor, borderColor,
                pressedColor, DosKeys.LCTRL in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.LCTRL) else keyUp(DosKeys.LCTRL) })
            PillKeyButton("Alt", DosKeys.LALT, bgColor, fgColor, borderColor,
                pressedColor, DosKeys.LALT in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.LALT) else keyUp(DosKeys.LALT) })
            PillKeyButton("Shift", DosKeys.LSHIFT, bgColor, fgColor, borderColor,
                pressedColor, DosKeys.LSHIFT in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.LSHIFT) else keyUp(DosKeys.LSHIFT) })
            PillKeyButton("Back", DosKeys.BACKSPACE, bgColor, fgColor, borderColor,
                pressedColor, DosKeys.BACKSPACE in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.BACKSPACE) else keyUp(DosKeys.BACKSPACE) })
        }

        // === Mouse: left/right buttons (top-left, transparent circular) ===
        // Plus: drag anywhere in the screen-center area to move the mouse.
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 16.dp, start = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            MouseKeyButton("L", mouseLeft, bgColor, fgColor, borderColor, pressedColor,
                onPressedChange = { p ->
                    mouseLeft = p
                    engine.injectMouseButton(0, p) // 0 = LEFT
                })
            MouseKeyButton("R", mouseRight, bgColor, fgColor, borderColor, pressedColor,
                onPressedChange = { p ->
                    mouseRight = p
                    engine.injectMouseButton(1, p) // 1 = RIGHT
                })
        }
    }
}

// ===========================================================================
// Keyboard overlay — full QWERTY transparent capsule-style keyboard.
// Layout (5 rows):
//   Row 1: Function keys F1-F12 (small capsules)
//   Row 2: 1-0 + symbols
//   Row 3: QWERTYUIOP + brackets
//   Row 4: ASDFGHJKL; + Enter
//   Row 5: ZXCVBNM,./ + Shift
//   Row 6: Ctrl/Alt/Space/Esc/Tab/Backspace + arrows
// ===========================================================================

@Composable
private fun DosKeyboardOverlay(
    engine: DosEngine,
    opacity: Float,
    isPortrait: Boolean,
    @Suppress("UNUSED_PARAMETER") surfaceSize: IntSize
) {
    val bgColor = Color.Black.copy(alpha = opacity * 0.5f)
    val fgColor = Color.White.copy(alpha = opacity)
    val borderColor = Color.White.copy(alpha = opacity * 0.6f)
    val pressedColor = Color(0xFFFFD66B).copy(alpha = opacity)

    var pressedKeys by remember { mutableStateOf(setOf<Int>()) }

    fun keyDown(code: Int) { pressedKeys = pressedKeys + code; engine.injectKeyDown(code, 0) }
    fun keyUp(code: Int)   { pressedKeys = pressedKeys - code; engine.injectKeyUp(code, 0) }

    val keySize = if (isPortrait) 30.dp else 36.dp
    val keySpacing = 3.dp
    val rowSpacing = 3.dp

    // Keyboard anchored to the bottom of the screen — wrap in a Box so we can
    // align the Column to the bottom.
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(rowSpacing)
        ) {
        // Function keys row (F1-F12)
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
        ) {
            val fkeys = listOf(
                "F1" to DosKeys.F1, "F2" to DosKeys.F2, "F3" to DosKeys.F3, "F4" to DosKeys.F4,
                "F5" to DosKeys.F5, "F6" to DosKeys.F6, "F7" to DosKeys.F7, "F8" to DosKeys.F8,
                "F9" to DosKeys.F9, "F10" to DosKeys.F10, "F11" to DosKeys.F11, "F12" to DosKeys.F12
            )
            fkeys.forEach { (label, code) ->
                CapsuleKeyButton(
                    label = label,
                    keyCode = code,
                    modifier = Modifier.weight(1f),
                    heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = code in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(code) else keyUp(code) }
                )
            }
        }

        // Number row: 1 2 3 4 5 6 7 8 9 0 - = Back
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
        ) {
            val row = listOf(
                "1" to DosKeys.K1, "2" to DosKeys.K2, "3" to DosKeys.K3,
                "4" to DosKeys.K4, "5" to DosKeys.K5, "6" to DosKeys.K6,
                "7" to DosKeys.K7, "8" to DosKeys.K8, "9" to DosKeys.K9,
                "0" to DosKeys.K0, "-" to DosKeys.MINUS, "=" to DosKeys.EQUALS
            )
            row.forEach { (label, code) ->
                CapsuleKeyButton(
                    label = label, keyCode = code,
                    modifier = Modifier.weight(1f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = code in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(code) else keyUp(code) }
                )
            }
            CapsuleKeyButton(
                label = "⌫", keyCode = DosKeys.BACKSPACE,
                modifier = Modifier.weight(1.5f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.BACKSPACE in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.BACKSPACE) else keyUp(DosKeys.BACKSPACE) }
            )
        }

        // QWERTY row: Q W E R T Y U I O P [ ] \
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
        ) {
            val row = listOf(
                "Q" to DosKeys.Q, "W" to DosKeys.W, "E" to DosKeys.E, "R" to DosKeys.R,
                "T" to DosKeys.T, "Y" to DosKeys.Y, "U" to DosKeys.U, "I" to DosKeys.I,
                "O" to DosKeys.O, "P" to DosKeys.P, "[" to DosKeys.LEFTBRACKET,
                "]" to DosKeys.RIGHTBRACKET, "\\" to DosKeys.BACKSLASH
            )
            row.forEach { (label, code) ->
                CapsuleKeyButton(
                    label = label, keyCode = code,
                    modifier = Modifier.weight(1f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = code in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(code) else keyUp(code) }
                )
            }
        }

        // ASDF row: A S D F G H J K L ; ' Enter
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
        ) {
            CapsuleKeyButton(
                label = "Caps", keyCode = DosKeys.CAPSLOCK,
                modifier = Modifier.weight(1.5f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.CAPSLOCK in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.CAPSLOCK) else keyUp(DosKeys.CAPSLOCK) }
            )
            val row = listOf(
                "A" to DosKeys.A, "S" to DosKeys.S, "D" to DosKeys.D, "F" to DosKeys.F,
                "G" to DosKeys.G, "H" to DosKeys.H, "J" to DosKeys.J, "K" to DosKeys.K,
                "L" to DosKeys.L, ";" to DosKeys.SEMICOLON, "'" to DosKeys.APOSTROPHE
            )
            row.forEach { (label, code) ->
                CapsuleKeyButton(
                    label = label, keyCode = code,
                    modifier = Modifier.weight(1f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = code in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(code) else keyUp(code) }
                )
            }
            CapsuleKeyButton(
                label = "⏎", keyCode = DosKeys.RETURN,
                modifier = Modifier.weight(1.5f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.RETURN in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.RETURN) else keyUp(DosKeys.RETURN) }
            )
        }

        // ZXCV row: Shift Z X C V B N M , . / Shift
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
        ) {
            CapsuleKeyButton(
                label = "Shift", keyCode = DosKeys.LSHIFT,
                modifier = Modifier.weight(1.5f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.LSHIFT in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.LSHIFT) else keyUp(DosKeys.LSHIFT) }
            )
            val row = listOf(
                "Z" to DosKeys.Z, "X" to DosKeys.X, "C" to DosKeys.C, "V" to DosKeys.V,
                "B" to DosKeys.B, "N" to DosKeys.N, "M" to DosKeys.M,
                "," to DosKeys.COMMA, "." to DosKeys.PERIOD, "/" to DosKeys.SLASH
            )
            row.forEach { (label, code) ->
                CapsuleKeyButton(
                    label = label, keyCode = code,
                    modifier = Modifier.weight(1f), heightDp = keySize,
                    bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                    pressedColor = pressedColor, pressed = code in pressedKeys,
                    onPressedChange = { p -> if (p) keyDown(code) else keyUp(code) }
                )
            }
            CapsuleKeyButton(
                label = "Shift", keyCode = DosKeys.RSHIFT,
                modifier = Modifier.weight(1.5f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.RSHIFT in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.RSHIFT) else keyUp(DosKeys.RSHIFT) }
            )
        }

        // Bottom row: Ctrl Alt Space Esc Tab + arrows + mouse L/R
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
        ) {
            CapsuleKeyButton(
                label = "Ctrl", keyCode = DosKeys.LCTRL,
                modifier = Modifier.weight(1.3f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.LCTRL in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.LCTRL) else keyUp(DosKeys.LCTRL) }
            )
            CapsuleKeyButton(
                label = "Alt", keyCode = DosKeys.LALT,
                modifier = Modifier.weight(1.3f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.LALT in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.LALT) else keyUp(DosKeys.LALT) }
            )
            CapsuleKeyButton(
                label = "Space", keyCode = DosKeys.SPACE,
                modifier = Modifier.weight(4f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.SPACE in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.SPACE) else keyUp(DosKeys.SPACE) }
            )
            CapsuleKeyButton(
                label = "Tab", keyCode = DosKeys.TAB,
                modifier = Modifier.weight(1.3f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.TAB in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.TAB) else keyUp(DosKeys.TAB) }
            )
            CapsuleKeyButton(
                label = "Esc", keyCode = DosKeys.ESCAPE,
                modifier = Modifier.weight(1.3f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.ESCAPE in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.ESCAPE) else keyUp(DosKeys.ESCAPE) }
            )
        }

        // Arrow keys row
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(keySpacing)
        ) {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            CapsuleKeyButton(
                label = "←", keyCode = DosKeys.LEFT,
                modifier = Modifier.weight(1f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.LEFT in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.LEFT) else keyUp(DosKeys.LEFT) }
            )
            CapsuleKeyButton(
                label = "↑", keyCode = DosKeys.UP,
                modifier = Modifier.weight(1f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.UP in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.UP) else keyUp(DosKeys.UP) }
            )
            CapsuleKeyButton(
                label = "↓", keyCode = DosKeys.DOWN,
                modifier = Modifier.weight(1f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.DOWN in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.DOWN) else keyUp(DosKeys.DOWN) }
            )
            CapsuleKeyButton(
                label = "→", keyCode = DosKeys.RIGHT,
                modifier = Modifier.weight(1f), heightDp = keySize,
                bgColor = bgColor, fgColor = fgColor, borderColor = borderColor,
                pressedColor = pressedColor, pressed = DosKeys.RIGHT in pressedKeys,
                onPressedChange = { p -> if (p) keyDown(DosKeys.RIGHT) else keyUp(DosKeys.RIGHT) }
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
        } // end Column
    } // end Box
}

// ===========================================================================
// Button primitives
// ===========================================================================

/** Mode toggle button (top-right corner). Tap to switch between gamepad/keyboard. */
@Composable
private fun ModeSwitchButton(
    isKeyboard: Boolean,
    opacity: Float,
    isPortrait: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = Color.Black.copy(alpha = opacity * 0.6f)
    val fgColor = Color.White.copy(alpha = opacity)
    val borderColor = Color.White.copy(alpha = opacity * 0.7f)
    val label = if (isKeyboard) "🎮" else "⌨"
    val tip = if (isKeyboard) "切换手柄" else "切换键盘"

    Box(
        modifier = modifier
            .size(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(bgColor)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onClick() })
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawCircle(borderColor, radius = w * 0.48f, center = Offset(w/2, h/2),
                style = Stroke(width = w * 0.04f))
        }
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

/**
 * Circular D-pad (cross shape with 4 directional buttons).
 * Drawn with Canvas primitives — transparent background, white border.
 */
@Composable
private fun CircularDpad(
    sizeDp: androidx.compose.ui.unit.Dp,
    bgColor: Color, fgColor: Color, borderColor: Color, pressedColor: Color,
    pressed: Int, // bitfield: BTN_UP | BTN_DOWN | BTN_LEFT | BTN_RIGHT
    onPressedChange: (dir: String, pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.toPx() }

    val pointers = remember { mutableMapOf<Long, String>() }  // pointerId → direction

    Box(
        modifier = modifier
            .size(sizeDp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val id = down.id.value
                    val pos = down.position
                    val dir = hitTestDpad(pos, sizePx)
                    if (dir.isNotEmpty()) {
                        pointers[id] = dir
                        onPressedChange(dir, true)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            val pid = change.id.value
                            val oldDir = pointers[pid]
                            val newDir = if (change.pressed) hitTestDpad(change.position, sizePx) else ""
                            if (oldDir != newDir) {
                                if (oldDir != null) onPressedChange(oldDir, false)
                                if (newDir.isNotEmpty()) onPressedChange(newDir, true)
                                if (newDir.isEmpty()) pointers.remove(pid) else pointers[pid] = newDir
                            }
                            if (!change.pressed) {
                                oldDir?.let { onPressedChange(it, false) }
                                pointers.remove(pid)
                            }
                        }
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawDpad(sizePx, bgColor, fgColor, borderColor, pressedColor, pressed)
        }
    }
}

/** Hit-test a touch point against the D-pad's 4 quadrants. Returns "up/down/left/right" or "". */
private fun hitTestDpad(pos: Offset, sizePx: Float): String {
    val cx = sizePx / 2; val cy = sizePx / 2
    val dx = pos.x - cx; val dy = pos.y - cy
    val radius = sizePx / 2
    // Outside the D-pad circle
    if (hypot(dx, dy) > radius) return ""
    // Determine direction by larger axis
    return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
        if (dx > 0) "right" else "left"
    } else {
        if (dy > 0) "down" else "up"
    }
}

/** Draw the D-pad cross using Canvas primitives. */
private fun DrawScope.drawDpad(
    sizePx: Float, bgColor: Color, fgColor: Color,
    borderColor: Color, pressedColor: Color, pressed: Int
) {
    val cx = sizePx / 2; val cy = sizePx / 2
    val armLen = sizePx * 0.42f
    val armW = sizePx * 0.32f
    val r = sizePx * 0.08f

    // Helper: draw a rounded-rect arm with arrow.
    fun arm(isVertical: Boolean, dir: Int) {
        val isPressed = (pressed and dir) != 0
        val color = if (isPressed) pressedColor else bgColor
        val left: Float; val top: Float; val right: Float; val bottom: Float
        if (isVertical) {
            left = cx - armW/2; right = cx + armW/2
            if (dir == BTN_UP) { top = cy - armLen; bottom = cy + armW/2 }
            else               { top = cy - armW/2; bottom = cy + armLen }
        } else {
            top = cy - armW/2; bottom = cy + armW/2
            if (dir == BTN_LEFT)  { left = cx - armLen; right = cx + armW/2 }
            else                  { left = cx - armW/2; right = cx + armLen }
        }
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r)
        )
        drawRoundRect(
            color = borderColor,
            topLeft = Offset(left, top),
            size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            style = Stroke(width = sizePx * 0.02f)
        )
        // Arrow tip
        val tipColor = if (isPressed) Color.Black.copy(alpha = 0.7f) else fgColor
        val arrowSize = sizePx * 0.08f
        when (dir) {
            BTN_UP -> drawArrow(Offset(cx, cy - armLen + arrowSize*1.5f), 0, -1, arrowSize, tipColor)
            BTN_DOWN -> drawArrow(Offset(cx, cy + armLen - arrowSize*1.5f), 0, 1, arrowSize, tipColor)
            BTN_LEFT -> drawArrow(Offset(cx - armLen + arrowSize*1.5f, cy), -1, 0, arrowSize, tipColor)
            BTN_RIGHT -> drawArrow(Offset(cx + armLen - arrowSize*1.5f, cy), 1, 0, arrowSize, tipColor)
        }
    }
    arm(false, BTN_LEFT)
    arm(false, BTN_RIGHT)
    arm(true, BTN_UP)
    arm(true, BTN_DOWN)
    // Center pivot dot
    drawCircle(fgColor.copy(alpha = 0.5f), radius = sizePx * 0.04f, center = Offset(cx, cy))
}

/** Draw an arrow (triangle) at `pos` pointing in direction (dx, dy). */
private fun DrawScope.drawArrow(pos: Offset, dx: Int, dy: Int, size: Float, color: Color) {
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(pos.x, pos.y)
        if (dx != 0) {
            lineTo(pos.x - dx * size, pos.y - size * 0.6f)
            lineTo(pos.x - dx * size, pos.y + size * 0.6f)
        } else {
            lineTo(pos.x - size * 0.6f, pos.y - dy * size)
            lineTo(pos.x + size * 0.6f, pos.y - dy * size)
        }
        close()
    }
    drawPath(path, color)
}

/** Circular key button with a text label (gamepad mode). */
@Composable
private fun CircularKeyButton(
    label: String,
    keyCode: Int,
    sizeDp: androidx.compose.ui.unit.Dp,
    bgColor: Color, fgColor: Color, borderColor: Color, pressedColor: Color,
    pressed: Boolean,
    onPressedChange: (pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val sizePx = with(density) { sizeDp.toPx() }
    var currentPointerId by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = modifier
            .size(sizeDp)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (currentPointerId == null) {
                        currentPointerId = down.id.value
                        onPressedChange(true)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            if (change.id.value == currentPointerId && !change.pressed) {
                                onPressedChange(false)
                                currentPointerId = null
                            }
                        }
                        if (currentPointerId == null) break
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            val r = size.width * 0.46f
            drawCircle(if (pressed) pressedColor else bgColor, r, Offset(cx, cy))
            drawCircle(borderColor, r, Offset(cx, cy), style = Stroke(width = size.width * 0.04f))
            // Inner highlight
            if (!pressed) {
                drawCircle(Color.White.copy(alpha = 0.1f), r * 0.85f,
                    Offset(cx, cy - r * 0.15f))
            }
        }
        Text(
            text = label,
            color = if (pressed) Color.Black.copy(alpha = 0.85f) else fgColor,
            fontSize = (sizeDp.value * 0.28f).sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** Pill-shaped (rounded rectangle) key button. */
@Composable
private fun PillKeyButton(
    label: String,
    keyCode: Int,
    bgColor: Color, fgColor: Color, borderColor: Color, pressedColor: Color,
    pressed: Boolean,
    onPressedChange: (pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPointerId by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (pressed) pressedColor else bgColor)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (currentPointerId == null) {
                        currentPointerId = down.id.value
                        onPressedChange(true)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            if (change.id.value == currentPointerId && !change.pressed) {
                                onPressedChange(false)
                                currentPointerId = null
                            }
                        }
                        if (currentPointerId == null) break
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                borderColor,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.width * 0.1f, size.width * 0.1f),
                style = Stroke(width = size.width * 0.04f)
            )
        }
        Text(
            text = label,
            color = if (pressed) Color.Black.copy(alpha = 0.85f) else fgColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** Capsule-style key button for the full keyboard overlay. */
@Composable
private fun CapsuleKeyButton(
    label: String,
    keyCode: Int,
    modifier: Modifier = Modifier,
    heightDp: androidx.compose.ui.unit.Dp,
    bgColor: Color, fgColor: Color, borderColor: Color, pressedColor: Color,
    pressed: Boolean,
    onPressedChange: (pressed: Boolean) -> Unit
) {
    var currentPointerId by remember { mutableStateOf<Long?>(null) }

    Box(
        modifier = modifier
            .height(heightDp)
            .clip(RoundedCornerShape(heightDp * 0.3f))
            .background(if (pressed) pressedColor else bgColor)
            .pointerInput(keyCode) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (currentPointerId == null) {
                        currentPointerId = down.id.value
                        onPressedChange(true)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            if (change.id.value == currentPointerId && !change.pressed) {
                                onPressedChange(false)
                                currentPointerId = null
                            }
                        }
                        if (currentPointerId == null) break
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRoundRect(
                borderColor,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height * 0.2f, size.height * 0.2f),
                style = Stroke(width = size.height * 0.05f)
            )
        }
        Text(
            text = label,
            color = if (pressed) Color.Black.copy(alpha = 0.85f) else fgColor,
            fontSize = (heightDp.value * 0.35f).sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** Mouse button (small circular button with letter label). */
@Composable
private fun MouseKeyButton(
    label: String,
    pressed: Boolean,
    bgColor: Color, fgColor: Color, borderColor: Color, pressedColor: Color,
    onPressedChange: (pressed: Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var currentPointerId by remember { mutableStateOf<Long?>(null) }
    val sizeDp = 40.dp

    Box(
        modifier = modifier
            .size(sizeDp)
            .pointerInput(label) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    if (currentPointerId == null) {
                        currentPointerId = down.id.value
                        onPressedChange(true)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            change.consume()
                            if (change.id.value == currentPointerId && !change.pressed) {
                                onPressedChange(false)
                                currentPointerId = null
                            }
                        }
                        if (currentPointerId == null) break
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            val r = size.width * 0.46f
            drawCircle(if (pressed) pressedColor else bgColor, r, Offset(cx, cy))
            drawCircle(borderColor, r, Offset(cx, cy), style = Stroke(width = size.width * 0.04f))
        }
        Text(
            text = label,
            color = if (pressed) Color.Black.copy(alpha = 0.85f) else fgColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

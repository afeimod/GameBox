package com.nesstation.app.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.ui.components.PixelBackdrop

// ---------------------------------------------------------------------------
// Key mapping model
// ---------------------------------------------------------------------------
/**
 * A single controller action (e.g. "A button") and the Android [keyCode]
 * that currently triggers it. [defaultKeyLabel] is the human-readable name
 * shown when no custom key has been assigned.
 */
data class KeyAction(
    val id: String,
    val name: String,
    val accent: Color,
    val defaultKeyCode: Int,
    val defaultKeyLabel: String
) {
    fun keyLabel(context: android.content.Context): String =
        KeyMapStore.get(context, id)?.let { KeyMapStore.keyCodeToLabel(it) } ?: defaultKeyLabel
}

// Per-platform button sets
private val NES_ACTIONS = listOf(
    KeyAction("nes_up",    "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("nes_down",  "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("nes_left",  "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("nes_right", "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("nes_a",     "A",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A,   "手柄 A"),
    KeyAction("nes_b",     "B",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B,   "手柄 B"),
    KeyAction("nes_select","Select", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("nes_start", "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start"),
    KeyAction("nes_ta",    "连发 A", Color(0xFF8E44AD), KeyEvent.KEYCODE_BUTTON_L2,  "L2"),
    KeyAction("nes_tb",    "连发 B", Color(0xFF8E44AD), KeyEvent.KEYCODE_BUTTON_R2,  "R2")
)

private val SNES_ACTIONS = NES_ACTIONS + listOf(
    KeyAction("snes_x", "X", Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_X, "手柄 X"),
    KeyAction("snes_y", "Y", Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_Y, "手柄 Y"),
    KeyAction("snes_l", "L", Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1, "L1"),
    KeyAction("snes_r", "R", Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1, "R1")
)

private val GBA_ACTIONS = NES_ACTIONS + listOf(
    KeyAction("gba_l", "L", Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1, "L1"),
    KeyAction("gba_r", "R", Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1, "R1")
)

// DOS gamepad mapping — dosbox_pure's auto-mapping converts these libretro
// gamepad buttons to DOS keys (A→Enter, B→Esc, X→Space, Y→Tab, L→mouse left,
// R→mouse right, etc.). The user can rebind which physical gamepad button
// triggers each libretro ID.
private val DOS_ACTIONS = listOf(
    KeyAction("dos_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("dos_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("dos_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("dos_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("dos_a",      "A / Enter",  Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A, "手柄 A"),
    KeyAction("dos_b",      "B / Esc",    Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B, "手柄 B"),
    KeyAction("dos_x",      "X / Space",  Color(0xFF3498DB), KeyEvent.KEYCODE_BUTTON_X, "手柄 X"),
    KeyAction("dos_y",      "Y / Tab",    Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_Y, "手柄 Y"),
    KeyAction("dos_l",      "L / 鼠标左",  Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1, "L1"),
    KeyAction("dos_r",      "R / 鼠标右",  Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1, "R1"),
    KeyAction("dos_l2",     "L2 / 鼠标中", Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L2, "L2"),
    KeyAction("dos_r2",     "R2",         Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R2, "R2"),
    KeyAction("dos_select", "Select",     Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("dos_start",  "Start",      Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start"),
    KeyAction("dos_l3",     "L3 / 切换键盘", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_THUMBL, "左摇杆按下"),
    KeyAction("dos_r3",     "R3 / 菜单",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_THUMBR, "右摇杆按下"),
    KeyAction("dos_mode",   "Mode / 菜单", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_MODE, "Mode")
)

// Java (J2ME) uses the same 12-key phone keypad mapping as the J2ME-Loader
// settings — but here we expose the gamepad buttons for D-pad + soft keys.
private val JAVA_ACTIONS = listOf(
    KeyAction("java_up",    "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("java_down",  "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("java_left",  "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("java_right", "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("java_a",     "A / 确认", Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A, "手柄 A"),
    KeyAction("java_b",     "B / 返回", Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B, "手柄 B"),
    KeyAction("java_select","左软键",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("java_start", "右软键",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start")
)

// Arcade (FBNeo) — 6-button fight-stick layout (CPS / NeoGeo style).
// Coin + Start correspond to MAME's "Insert Coin" and "Start Game" inputs.
// L/R map to the 5th/6th buttons on NeoGeo layouts (A/B/C/D + E/F).
private val ARCADE_ACTIONS = listOf(
    KeyAction("arc_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("arc_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("arc_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("arc_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("arc_a",      "A / 弱拳", Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A, "手柄 A"),
    KeyAction("arc_b",      "B / 中拳", Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B, "手柄 B"),
    KeyAction("arc_x",      "X / 弱脚", Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_X, "手柄 X"),
    KeyAction("arc_y",      "Y / 中脚", Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_Y, "手柄 Y"),
    KeyAction("arc_l",      "L / 强拳", Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_L1, "L1"),
    KeyAction("arc_r",      "R / 强脚", Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1, "R1"),
    KeyAction("arc_l2",     "L2 / 投币", Color(0xFFF57C00), KeyEvent.KEYCODE_BUTTON_L2, "L2 / 投币"),
    KeyAction("arc_r2",     "R2 / 开始", Color(0xFFF57C00), KeyEvent.KEYCODE_BUTTON_R2, "R2 / 开始"),
    KeyAction("arc_select", "Select / 投币", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_SELECT, "Select"),
    KeyAction("arc_start",  "Start / 开始", Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START,  "Start")
)

// SEGA Mega Drive / Genesis — supports both 3-button (B/A/C + Start) and
// 6-button (X/Y/Z + Mode) controllers. Genesis-Plus-GX auto-detects the
// layout per game. A/B/C are the primary 3-button face buttons; X/Y/Z are
// the extra 6-button row; Mode toggles 3-button compatibility mode.
// SMS / Game Gear / SG-1000 only use buttons 1 and 2 (mapped to A and B).
private val MD_ACTIONS = listOf(
    KeyAction("md_up",     "上",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_UP,    "方向上"),
    KeyAction("md_down",   "下",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_DOWN,  "方向下"),
    KeyAction("md_left",   "左",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_LEFT,  "方向左"),
    KeyAction("md_right",  "右",     Color(0xFF3498DB), KeyEvent.KEYCODE_DPAD_RIGHT, "方向右"),
    KeyAction("md_a",      "A",      Color(0xFFE74C3C), KeyEvent.KEYCODE_BUTTON_A, "手柄 A"),
    KeyAction("md_b",      "B",      Color(0xFFE67E22), KeyEvent.KEYCODE_BUTTON_B, "手柄 B"),
    KeyAction("md_c",      "C",      Color(0xFF2ECC71), KeyEvent.KEYCODE_BUTTON_R1, "R1 / C"),
    KeyAction("md_x",      "X",      Color(0xFF9C27B0), KeyEvent.KEYCODE_BUTTON_X, "手柄 X"),
    KeyAction("md_y",      "Y",      Color(0xFF00BCD4), KeyEvent.KEYCODE_BUTTON_Y, "手柄 Y"),
    KeyAction("md_z",      "Z",      Color(0xFFFFEB3B), KeyEvent.KEYCODE_BUTTON_L1, "L1 / Z"),
    KeyAction("md_mode",   "Mode",   Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_MODE, "Mode"),
    KeyAction("md_start",  "Start",  Color(0xFF1E2A3A), KeyEvent.KEYCODE_BUTTON_START, "Start")
)

private fun actionsFor(platform: GamePlatform): List<KeyAction> = when (platform) {
    GamePlatform.NES    -> NES_ACTIONS
    GamePlatform.SFC    -> SNES_ACTIONS
    GamePlatform.GB     -> NES_ACTIONS
    GamePlatform.GBA    -> GBA_ACTIONS
    GamePlatform.DOS    -> DOS_ACTIONS
    GamePlatform.ARCADE -> ARCADE_ACTIONS
    GamePlatform.MD     -> MD_ACTIONS
    GamePlatform.JAVA   -> JAVA_ACTIONS
}

// ---------------------------------------------------------------------------
// KeyMapScreen — per-core key mapping with TV D-pad support
// ---------------------------------------------------------------------------
@Composable
fun KeyMapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selectedPlatform by remember { mutableStateOf(GamePlatform.NES) }
    var capturingActionId by remember { mutableStateOf<String?>(null) }

    // Detect TV mode to show a hint
    val isTv = remember {
        !context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_TOUCHSCREEN
        )
    }

    val actions = actionsFor(selectedPlatform)

    Box(modifier = Modifier.fillMaxSize()) {
        PixelBackdrop()
        Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FocusableBackButton(onBack)
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("按键映射", color = Color(0xFF1E2A3A), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    if (isTv) {
                        Text(
                            "TV 模式 · 选择核心后按 OK 键重映射",
                            color = Color(0xFF4A5568),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // Platform selector tabs — focusable for D-pad
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    listOf(
                        GamePlatform.NES to "NES",
                        GamePlatform.SFC to "SFC",
                        GamePlatform.GBA to "GBA",
                        GamePlatform.GB to "GB/GBC",
                        GamePlatform.DOS to "DOS",
                        GamePlatform.JAVA to "Java"
                    )
                ) { (platform, label) ->
                    PlatformTab(
                        label = label,
                        selected = selectedPlatform == platform,
                        onClick = { selectedPlatform = platform }
                    )
                }
            }

            // Action list
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 4.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Gamepad,
                            contentDescription = null,
                            tint = Color(0xFF1E2A3A),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "${selectedPlatform.displayName} 按键 · 点击右侧按键框重映射",
                            color = Color(0xFF4A5568),
                            fontSize = 12.sp
                        )
                    }
                }
                items(actions, key = { it.id }) { action ->
                    KeyMapRow(
                        action = action,
                        currentLabel = action.keyLabel(context),
                        isCapturing = capturingActionId == action.id,
                        onClick = {
                            capturingActionId = if (capturingActionId == action.id) null else action.id
                        }
                    )
                }
                item {
                    // Reset button
                    Spacer(Modifier.size(8.dp))
                    ResetAllButton(
                        onClick = {
                            actions.forEach { KeyMapStore.remove(context, it.id) }
                        }
                    )
                }
            }
        }
    }

    // Key capture overlay — when capturingActionId != null, the user presses
    // any physical key to assign it. Uses an AndroidView with a View.OnKeyListener
    // for maximum reliability across Compose versions.
    if (capturingActionId != null) {
        val captureId = capturingActionId!!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center
        ) {
            // Transparent fullscreen View that captures physical key events
            // via Android's View.OnKeyListener — more reliable than Compose's
            // onKeyEvent across different Compose versions.
            AndroidView(
                factory = { ctx ->
                    View(ctx).apply {
                        isFocusable = true
                        isFocusableInTouchMode = true
                        requestFocus()
                        setOnKeyListener { _, keyCode, event ->
                            if (event.action == KeyEvent.ACTION_DOWN) {
                                // Don't allow mapping the Back key — it cancels capture
                                if (keyCode == KeyEvent.KEYCODE_BACK) {
                                    capturingActionId = null
                                    true
                                } else {
                                    // Store the mapping using the integer keyCode
                                    KeyMapStore.put(context, captureId, keyCode)
                                    capturingActionId = null
                                    true
                                }
                            } else {
                                false
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.Keyboard,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
                Spacer(Modifier.size(12.dp))
                Text(
                    "按下想要绑定的按键…",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    "按 Back / 返回 键取消",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Row components
// ---------------------------------------------------------------------------
@Composable
private fun PlatformTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                if (selected) Color(0xFF1E2A3A)
                else if (focused) Color.White.copy(alpha = 0.85f)
                else Color.White.copy(alpha = 0.5f)
            )
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Color(0xFF8A7BFF) else Color.Transparent,
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) Color.White else Color(0xFF1E2A3A),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun KeyMapRow(
    action: KeyAction,
    currentLabel: String,
    isCapturing: Boolean,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (isCapturing) Color(0xFF8A7BFF).copy(alpha = 0.2f)
                else if (focused) Color.White.copy(alpha = 0.85f)
                else Color.White.copy(alpha = 0.7f)
            )
            .border(
                width = if (isCapturing || focused) 2.dp else 0.dp,
                color = if (isCapturing) Color(0xFF8A7BFF)
                else if (focused) Color(0xFF4F8AC4)
                else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(action.accent, RoundedCornerShape(50))
        )
        Spacer(Modifier.size(12.dp))
        Text(
            action.name,
            color = Color(0xFF1E2A3A),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isCapturing) Color(0xFF8A7BFF)
                    else action.accent.copy(alpha = 0.15f)
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                if (isCapturing) "等待按键…" else currentLabel,
                color = if (isCapturing) Color.White else action.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun ResetAllButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (focused) Color(0xFFE74C3C).copy(alpha = 0.85f)
                else Color(0xFFE74C3C).copy(alpha = 0.15f)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .focusable(interactionSource = interaction)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "重置当前核心为默认按键",
            color = if (focused) Color.White else Color(0xFFE74C3C),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun FocusableBackButton(onBack: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) Color.White.copy(alpha = 0.8f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onBack)
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.ArrowBack,
            contentDescription = "返回",
            tint = Color(0xFF1E2A3A)
        )
    }
}

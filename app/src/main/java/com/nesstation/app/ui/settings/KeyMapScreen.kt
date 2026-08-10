package com.nesstation.app.ui.settings

import android.content.Context
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    fun keyLabel(context: Context): String =
        KeyMapStore.get(context, id)?.let { KeyMapStore.keyCodeToLabel(it) } ?: defaultKeyLabel
    fun keyCode(context: Context): Int =
        KeyMapStore.get(context, id) ?: defaultKeyCode
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

private fun actionsFor(platform: GamePlatform): List<KeyAction> = when (platform) {
    GamePlatform.NES  -> NES_ACTIONS
    GamePlatform.SFC  -> SNES_ACTIONS
    GamePlatform.GB   -> NES_ACTIONS
    GamePlatform.GBA  -> GBA_ACTIONS
    GamePlatform.JAVA -> JAVA_ACTIONS
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
                        modifier = Modifier.padding(horizontal = 8.dp, bottom = 4.dp)
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
    // any physical key to assign it.
    if (capturingActionId != null) {
        val captureId = capturingActionId!!
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000))
                .focusable()
                .onKeyEvent { keyEvent ->
                    if (keyEvent.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) {
                        val kc = keyEvent.keyCode
                        // Don't allow mapping the Back key — it cancels capture
                        if (kc == androidx.compose.ui.input.key.Key.Back) {
                            capturingActionId = null
                            true
                        } else {
                            // Store the mapping using the integer keyCode
                            KeyMapStore.put(context, captureId, keyCodeToInt(kc))
                            capturingActionId = null
                            true
                        }
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.Center
        ) {
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

// ---------------------------------------------------------------------------
// Helper: convert Compose Key to Android integer keyCode for storage
// ---------------------------------------------------------------------------
private fun keyCodeToInt(key: androidx.compose.ui.input.key.Key): Int {
    // Compose Key.keyCode is an Int on Android — we just store that value.
    return key.keyCode
}

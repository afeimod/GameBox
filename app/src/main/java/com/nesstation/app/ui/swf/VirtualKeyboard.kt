package com.nesstation.app.ui.swf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.storage.SwfButton
import com.nesstation.app.core.storage.SwfPadConfig

// ---------------------------------------------------------------------------
// Colour palette
// ---------------------------------------------------------------------------
private val BtnBg = Color(0xFF1E2A3A).copy(alpha = 0.72f)
private val BtnActive = Color(0xFF8A7BFF).copy(alpha = 0.85f)
private val BtnText = Color.White
private val EditModeBg = Color(0xFF2A3A4A).copy(alpha = 0.6f)
private val AccentColor = Color(0xFF8A7BFF)
private val DeleteColor = Color(0xFFE74C3C)

// ---------------------------------------------------------------------------
// Main virtual keyboard — renders buttons at custom positions
// ---------------------------------------------------------------------------

/**
 * SWF virtual keyboard overlay.
 *
 * In **play mode** each button dispatches key press/release events.
 * In **edit mode** buttons become draggable; a toolbar offers add / delete /
 * reset, and a size slider appears for the selected button.
 */
@Composable
fun VirtualKeyboard(
    config: SwfPadConfig,
    editMode: Boolean,
    onKeyPress: (String) -> Unit,
    onKeyRelease: (String) -> Unit,
    onConfigChange: (SwfPadConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        val maxW = constraints.maxWidth.toFloat()
        val maxH = constraints.maxHeight.toFloat()
        val density = LocalDensity.current

        // ---- Buttons ----
        config.buttons.forEach { btn ->
            // D-pad keys swap with WASD when useWASD is enabled
            val effectiveKey = when {
                !config.useWASD -> btn.key
                btn.id == "dpad_up" -> "w"
                btn.id == "dpad_down" -> "s"
                btn.id == "dpad_left" -> "a"
                btn.id == "dpad_right" -> "d"
                else -> btn.key
            }
            val effectiveLabel = when {
                !config.useWASD -> btn.label
                btn.id == "dpad_up" -> "W"
                btn.id == "dpad_down" -> "S"
                btn.id == "dpad_left" -> "A"
                btn.id == "dpad_right" -> "D"
                else -> btn.label
            }

            SwfKeyButton(
                button = btn,
                label = effectiveLabel,
                key = effectiveKey,
                editMode = editMode,
                isSelected = selectedId == btn.id,
                maxW = maxW,
                maxH = maxH,
                density = density,
                onPress = onKeyPress,
                onRelease = onKeyRelease,
                onPositionChange = { newX, newY ->
                    onConfigChange(config.copy(
                        buttons = config.buttons.map {
                            if (it.id == btn.id) it.copy(xPct = newX, yPct = newY) else it
                        }
                    ))
                },
                onSelect = { selectedId = btn.id },
                onDelete = {
                    if (btn.id !in SwfPadConfig.FIXED_IDS) {
                        onConfigChange(config.copy(
                            buttons = config.buttons.filter { it.id != btn.id }
                        ))
                        selectedId = null
                    }
                }
            )
        }

        // ---- Edit-mode toolbar ----
        if (editMode) {
            EditToolbar(
                modifier = Modifier.align(Alignment.TopCenter),
                onAdd = { showAddDialog = true },
                onDelete = {
                    selectedId?.let { sid ->
                        if (sid !in SwfPadConfig.FIXED_IDS) {
                            onConfigChange(config.copy(
                                buttons = config.buttons.filter { it.id != sid }
                            ))
                            selectedId = null
                        }
                    }
                },
                onReset = {
                    onConfigChange(SwfPadConfig(useWASD = config.useWASD, showPad = config.showPad))
                    selectedId = null
                },
                hasSelection = selectedId != null && selectedId !in SwfPadConfig.FIXED_IDS
            )

            // Size slider for selected button
            selectedId?.let { sid ->
                val selected = config.buttons.firstOrNull { it.id == sid }
                if (selected != null) {
                    SizeSlider(
                        size = selected.sizeDp,
                        onSizeChange = { newSize ->
                            onConfigChange(config.copy(
                                buttons = config.buttons.map {
                                    if (it.id == sid) it.copy(sizeDp = newSize) else it
                                }
                            ))
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .fillMaxWidth(0.6f)
                    )
                }
            }
        }
    }

    // ---- Add-button dialog ----
    if (showAddDialog) {
        AddButtonDialog(
            onAdd = { label, key ->
                onConfigChange(config.copy(
                    buttons = config.buttons + SwfPadConfig.newButton(label, key)
                ))
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}

// ---------------------------------------------------------------------------
// Individual key button
// ---------------------------------------------------------------------------

@Composable
private fun SwfKeyButton(
    button: SwfButton,
    label: String,
    key: String,
    editMode: Boolean,
    isSelected: Boolean,
    maxW: Float,
    maxH: Float,
    density: androidx.compose.ui.unit.Density,
    onPress: (String) -> Unit,
    onRelease: (String) -> Unit,
    onPositionChange: (Float, Float) -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val sizeDp = button.sizeDp.dp

    // Convert percentage to dp offset, centered on the position
    val xOffset = with(density) { (button.xPct / 100f * maxW).toDp() } - sizeDp / 2
    val yOffset = with(density) { (button.yPct / 100f * maxH).toDp() } - sizeDp / 2

    Box(
        modifier = Modifier
            .offset(x = xOffset, y = yOffset)
            .size(sizeDp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    editMode && isSelected -> AccentColor.copy(alpha = 0.4f)
                    editMode -> EditModeBg
                    pressed -> BtnActive
                    else -> BtnBg
                }
            )
            .pointerInput(button.id, editMode) {
                if (editMode) {
                    // Edit mode: drag to move
                    detectDragGestures(
                        onDragStart = { onSelect() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val newX = button.xPct + (dragAmount.x / maxW * 100f)
                            val newY = button.yPct + (dragAmount.y / maxH * 100f)
                            onPositionChange(
                                newX.coerceIn(2f, 98f),
                                newY.coerceIn(5f, 95f)
                            )
                        }
                    )
                }
            }
            .pointerInput(button.id, editMode) {
                if (!editMode) {
                    // Play mode: press and hold
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            onPress(key)
                            tryAwaitRelease()
                            pressed = false
                            onRelease(key)
                        }
                    )
                }
            }
            .clickable(enabled = editMode) { onSelect() },
        contentAlignment = Alignment.Center
    ) {
        // Delete badge (non-fixed buttons only, in edit mode)
        if (editMode && button.id !in SwfPadConfig.FIXED_IDS) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 4.dp, y = (-4).dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(DeleteColor)
                    .clickable { onDelete() },
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Font size scales with button size
        val fontSize = (button.sizeDp / 3.5f).coerceIn(8f, 20f)
        Text(
            text = label,
            color = BtnText,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

// ---------------------------------------------------------------------------
// Edit-mode toolbar
// ---------------------------------------------------------------------------

@Composable
private fun EditToolbar(
    onAdd: () -> Unit,
    onDelete: () -> Unit,
    onReset: () -> Unit,
    hasSelection: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .padding(top = 56.dp, start = 16.dp, end = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF1E2A3A).copy(alpha = 0.9f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolButton(Icons.Rounded.Add, "添加", AccentColor, onAdd)
        ToolButton(
            Icons.Rounded.Delete, "删除",
            if (hasSelection) DeleteColor else Color.Gray,
            onDelete
        )
        ToolButton(Icons.Rounded.Refresh, "重置", Color(0xFFFFD66B), onReset)
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.size(4.dp))
        Text(label, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

// ---------------------------------------------------------------------------
// Size slider (shown in edit mode for the selected button)
// ---------------------------------------------------------------------------

@Composable
private fun SizeSlider(
    size: Float,
    onSizeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1E2A3A).copy(alpha = 0.9f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("大小", color = Color(0xFF8899AA), fontSize = 11.sp)
        Spacer(Modifier.size(8.dp))
        Slider(
            value = size,
            onValueChange = onSizeChange,
            valueRange = 28f..80f,
            colors = SliderDefaults.colors(
                thumbColor = AccentColor,
                activeTrackColor = AccentColor.copy(alpha = 0.7f)
            ),
            modifier = Modifier.weight(1f)
        )
        Text("${size.toInt()}dp", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(36.dp))
    }
}

// ---------------------------------------------------------------------------
// Add-button dialog
// ---------------------------------------------------------------------------

@Composable
private fun AddButtonDialog(
    onAdd: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var label by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加按键") },
        text = {
            Column {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("显示名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.lowercase() },
                    label = { Text("按键 (如 a, b, 1, shift)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val l = if (label.isBlank()) key.uppercase() else label
                    val k = if (key.isBlank()) label.lowercase() else key
                    if (k.isNotBlank()) onAdd(l, k)
                }
            ) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

package com.nesstation.app.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.nesstation.app.ui.components.PixelBackdrop

data class SettingsItem(
    val title: String,
    val subtitle: String? = null,
    val icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    val trailing: Trailing = Trailing.Arrow,
    val accent: Color = Color(0xFF1E2A3A),
    val onClick: (() -> Unit)? = null
)

enum class Trailing { Arrow, Switch, Value }

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKeyMap: () -> Unit
) {
    val context = LocalContext.current
    var scanlines by remember { mutableStateOf(false) }
    var crtShader by remember { mutableStateOf(false) }
    var lowLatency by remember { mutableStateOf(true) }
    var showPad by remember { mutableStateOf(true) }
    var vibration by remember { mutableStateOf(true) }
    var tvMode by remember { mutableStateOf(false) }
    var dialogText by remember { mutableStateOf<String?>(null) }

    // Permission launcher for Android <= 10
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        dialogText = if (result.values.any { it }) {
            "存储权限已授予"
        } else {
            "权限被拒绝。可使用「导入ROM」按钮通过系统文件选择器导入，无需存储权限。"
        }
    }

    fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: request MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            } else {
                dialogText = "已有所有文件访问权限"
            }
        } else {
            val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            permissionLauncher.launch(permissions)
        }
    }

    fun openAppSettings() {
        val intent = Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
        context.startActivity(intent)
    }

    // Build settings sections — NOT wrapped in remember{} so state changes
    // (like switch toggles) trigger recomposition with fresh subtitle values.
    val sections = listOf(
        "视频" to listOf(
            SettingsItem("画面比例", "4:3", trailing = Trailing.Value) { dialogText = "画面比例已设为 4:3（NES 标准）" },
            SettingsItem("扫描线", if (scanlines) "开" else "关", trailing = Trailing.Switch) { scanlines = !scanlines },
            SettingsItem("CRT 着色器", if (crtShader) "开" else "关", trailing = Trailing.Switch) { crtShader = !crtShader },
            SettingsItem("画面滤镜", "Nearest", trailing = Trailing.Value) { dialogText = "画面滤镜: Nearest (像素完美)" },
        ),
        "音频" to listOf(
            SettingsItem("采样率", "44.1 kHz", trailing = Trailing.Value) { dialogText = "采样率: 44.1 kHz (CD音质)" },
            SettingsItem("低延迟模式", if (lowLatency) "开" else "关", trailing = Trailing.Switch) { lowLatency = !lowLatency },
            SettingsItem("音量", "90", trailing = Trailing.Value) { dialogText = "音量: 90%" },
        ),
        "输入" to listOf(
            SettingsItem("屏幕手柄", if (showPad) "显示" else "隐藏", trailing = Trailing.Switch) { showPad = !showPad },
            SettingsItem("按键映射", "自定义", trailing = Trailing.Arrow, onClick = onOpenKeyMap),
            SettingsItem("手柄震动", if (vibration) "开" else "关", trailing = Trailing.Switch) { vibration = !vibration },
        ),
        "存储" to listOf(
            SettingsItem("存储权限", "点击授权", trailing = Trailing.Arrow) { requestStoragePermission() },
            SettingsItem("应用详情", "系统设置", trailing = Trailing.Arrow) { openAppSettings() },
            SettingsItem("扫描ROM", "扫描本地目录", trailing = Trailing.Arrow) { dialogText = "请到游戏库点击「导入ROM」或「导入文件夹」按钮导入游戏文件" },
        ),
        "显示" to listOf(
            SettingsItem("TV 模式", if (tvMode) "开" else "关", trailing = Trailing.Switch) { tvMode = !tvMode },
            SettingsItem("主题", "跟随系统", trailing = Trailing.Value) { dialogText = "主题: 跟随系统" },
            SettingsItem("横屏锁定", "自动", trailing = Trailing.Value) { dialogText = "横屏锁定: 自动" },
        ),
        "关于" to listOf(
            SettingsItem("版本", "1.0.0", trailing = Trailing.Value) { dialogText = "NesStation v1.0.0" },
            SettingsItem("核心", "FCEUmm", trailing = Trailing.Value) { dialogText = "模拟器核心: FCEUmm (libretro)" },
            SettingsItem("开源许可", "MIT License", trailing = Trailing.Arrow) { dialogText = "NesStation 基于 FCEUmm 核心，遵循 MIT 许可证" },
        )
    )

    Box(modifier = Modifier.fillMaxSize()) {
        PixelBackdrop()
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = Color(0xFF1E2A3A))
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "设置",
                    color = Color(0xFF1E2A3A),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                sections.forEach { (title, items) ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = title,
                                color = Color(0xFF1E2A3A),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.65f))
                                    .padding(vertical = 2.dp)
                            ) {
                                items.forEachIndexed { i, s ->
                                    val checked = when (s.title) {
                                        "扫描线" -> scanlines
                                        "CRT 着色器" -> crtShader
                                        "低延迟模式" -> lowLatency
                                        "屏幕手柄" -> showPad
                                        "手柄震动" -> vibration
                                        "TV 模式" -> tvMode
                                        else -> false
                                    }
                                    SettingsRow(
                                        item = s,
                                        checked = checked
                                    ) {
                                        // Single click handler — call the item's onClick
                                        // which handles toggles, dialogs, navigation, etc.
                                        s.onClick?.invoke()
                                    }
                                    if (i != items.lastIndex) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 56.dp, end = 16.dp)
                                                .background(Color(0x22000000))
                                                .size(width = 1.dp, height = 0.5.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Info dialog
    dialogText?.let { text ->
        AlertDialog(
            onDismissRequest = { dialogText = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { dialogText = null }) { Text("确定") }
            }
        )
    }
}

@Composable
private fun SettingsRow(
    item: SettingsItem,
    checked: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (item.icon != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(item.accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(item.icon, contentDescription = null, tint = item.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.size(10.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = Color(0xFF1E2A3A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (item.subtitle != null && item.trailing != Trailing.Switch) {
                Text(item.subtitle, color = Color(0xFF4A5568), fontSize = 11.sp)
            }
        }
        when (item.trailing) {
            Trailing.Arrow -> Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color(0xFF4A5568), modifier = Modifier.size(18.dp))
            Trailing.Switch -> Switch(
                checked = checked,
                onCheckedChange = { onClick() },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFE74C3C))
            )
            Trailing.Value -> Text(item.subtitle ?: "", color = Color(0xFF4A5568), fontSize = 12.sp)
        }
    }
}

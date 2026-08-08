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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.nesstation.app.core.engine.NesEngine
import com.nesstation.app.core.storage.PadLayoutStore
import com.nesstation.app.ui.components.PixelBackdrop

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKeyMap: () -> Unit
) {
    val context = LocalContext.current
    var padLayout by remember { mutableStateOf(PadLayoutStore.load(context)) }
    var dialogText by remember { mutableStateOf<String?>(null) }

    fun updateLayout(new: com.nesstation.app.core.storage.PadLayout) {
        padLayout = new
        PadLayoutStore.save(context, new)
        // Apply core options immediately
        val engine = NesEngine.get()
        engine.setCoreOption("fceumm_ntsc_filter", new.ntscFilter)
        engine.setCoreOption("fceumm_palette", new.palette)
        engine.setCoreOption("fceumm_region", new.region)
        engine.setCoreOption("fceumm_overclocking", new.overclocking)
        // Audio options (sndquality, sndlowpass, sndvolume) are NOT set —
        // FCEUmm uses its own built-in defaults for correct audio.
        // Aspect ratio (fceumm_aspect) is NOT set — the frontend controls
        // display aspect ratio via videoScale (SurfaceView layout).
        val cropVal = if (new.cropOverscan == "enabled") "8" else "0"
        engine.setCoreOption("fceumm_overscan_h_left", cropVal)
        engine.setCoreOption("fceumm_overscan_h_right", cropVal)
        engine.setCoreOption("fceumm_overscan_v_top", cropVal)
        engine.setCoreOption("fceumm_overscan_v_bottom", cropVal)
        // Apply video filter (frontend post-processing)
        val filterInt = when (new.videoFilter) {
            "scanline" -> 1
            "crt" -> 2
            "dot" -> 3
            "xbr" -> 4
            "hq2x" -> 5
            "hq4x" -> 6
            "xbr_dot" -> 7
            "4xbr" -> 8
            "4xbr_dot" -> 9
            "hq4x_dot" -> 10
            else -> 0
        }
        engine.setVideoFilter(filterInt)
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
        PixelBackdrop()
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = Color(0xFF1E2A3A))
                }
                Spacer(Modifier.size(8.dp))
                Text("设置", color = Color(0xFF1E2A3A), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // === 视频 / 核心设置 ===
                item {
                    SettingsSection("视频") {
                        DropdownRow("NTSC 滤镜",
                            listOf("disabled" to "关闭", "composite" to "复合", "svideo" to "S-Video", "rgb" to "RGB", "monochrome" to "黑白"),
                            padLayout.ntscFilter
                        ) { updateLayout(padLayout.copy(ntscFilter = it)) }

                        DropdownRow("调色板",
                            listOf(
                                "default" to "默认", "asqrealc" to "AspiringSquire", "wii-vc" to "Wii VC",
                                "rgb" to "Nintendo RGB", "yuv-v3" to "FBX YUV-V3", "unsaturated-final" to "Unsaturated",
                                "sony-cxa2025as-us" to "Sony CXA", "pal" to "PAL", "bmf-final2" to "BMF Final 2",
                                "smooth-fbx" to "FBX Smooth", "composite-direct-fbx" to "FBX Composite",
                                "ntsc-hardware-fbx" to "FBX NTSC HW", "nes-classic-fbx" to "FBX NES Classic"
                            ),
                            padLayout.palette
                        ) { updateLayout(padLayout.copy(palette = it)) }

                        DropdownRow("裁剪过扫描",
                            listOf("disabled" to "关闭", "enabled" to "开启"),
                            padLayout.cropOverscan
                        ) { updateLayout(padLayout.copy(cropOverscan = it)) }

                        DropdownRow("画面缩放",
                            listOf("stretch" to "全屏拉伸(默认)", "4:3" to "4:3", "8:7" to "8:7", "16:9" to "16:9"),
                            padLayout.videoScale
                        ) { updateLayout(padLayout.copy(videoScale = it)) }

                        DropdownRow("视频滤镜",
                            listOf("none" to "关闭", "scanline" to "扫描线", "crt" to "CRT", "dot" to "点阵",
                                   "xbr" to "XBR", "hq2x" to "HQ2X", "hq4x" to "HQ4X", "xbr_dot" to "XBR+点阵",
                                   "4xbr" to "4XBR", "4xbr_dot" to "4XBR+点阵", "hq4x_dot" to "HQ4X+点阵"),
                            padLayout.videoFilter
                        ) { updateLayout(padLayout.copy(videoFilter = it)) }
                    }
                }

                // === 区域 ===
                item {
                    SettingsSection("系统") {
                        DropdownRow("区域",
                            listOf("Auto" to "自动", "NTSC" to "NTSC", "PAL" to "PAL", "Dendy" to "Dendy"),
                            padLayout.region
                        ) { updateLayout(padLayout.copy(region = it)) }
                    }
                }

                // === 性能(超频) ===
                item {
                    SettingsSection("性能") {
                        DropdownRow("超频(减少慢动作)",
                            listOf("disabled" to "关闭", "2x-Postrender" to "后渲染(兼容性好)", "2x-VBlank" to "VBlank(推荐·魂斗罗力量)"),
                            padLayout.overclocking
                        ) { updateLayout(padLayout.copy(overclocking = it)) }
                    }
                }

                // === 输入 ===
                item {
                    SettingsSection("输入") {
                        SettingsRow("屏幕手柄", if (padLayout.showPad) "显示" else "隐藏",
                            showSubtitle = false,
                            trailing = {
                                Switch(checked = padLayout.showPad, onCheckedChange = {
                                    updateLayout(padLayout.copy(showPad = it))
                                }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFE74C3C)))
                            }
                        )
                        SettingsRow("按键映射", "自定义", trailing = { Arrow() }) { onOpenKeyMap() }
                    }
                }

                // === 存储 ===
                item {
                    SettingsSection("存储") {
                        SettingsRow("存储权限", "点击授权", trailing = { Arrow() }) { requestStoragePermission() }
                        SettingsRow("应用详情", "系统设置", trailing = { Arrow() }) { openAppSettings() }
                        SettingsRow("扫描ROM", "去游戏库导入", trailing = { Arrow() }) {
                            dialogText = "请到游戏库点击「导入ROM」或「导入文件夹」按钮导入游戏文件"
                        }
                    }
                }

                // === 关于 ===
                item {
                    SettingsSection("关于") {
                        SettingsRow("版本", "2.0.0", trailing = { ValueText("2.0.0") })
                        SettingsRow("核心", "FCEUmm + Snes9x + mGBA", trailing = { ValueText("FCEUmm + Snes9x + mGBA") })
                        SettingsRow("开源许可", "MIT License", trailing = { Arrow() }) {
                            dialogText = "NesStation 基于 FCEUmm (NES)、Snes9x (SFC)、mGBA (GB/GBC/GBA) 核心，遵循 MIT 许可证"
                        }
                    }
                }
            }
        }
    }

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
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, color = Color(0xFF1E2A3A), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
        Column(
            modifier = Modifier.clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.65f))
                .padding(vertical = 2.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    showSubtitle: Boolean = true,
    trailing: @Composable () -> Unit = { Arrow() },
    onClick: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onClick?.invoke() }.padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color(0xFF1E2A3A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle != null && showSubtitle) {
                Text(subtitle, color = Color(0xFF4A5568), fontSize = 11.sp)
            }
        }
        trailing()
    }
}

@Composable
private fun DropdownRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selected }?.second ?: selected

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color(0xFF1E2A3A), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Box {
            Text(selectedLabel, color = Color(0xFFE74C3C), fontSize = 13.sp, modifier = Modifier.clickable { expanded = true })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(text = { Text(text, fontSize = 13.sp) }, onClick = { onSelect(value); expanded = false })
                }
            }
        }
    }
}

@Composable private fun Arrow() = Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color(0xFF4A5568), modifier = Modifier.size(18.dp))
@Composable private fun ValueText(v: String) = Text(v, color = Color(0xFF4A5568), fontSize = 12.sp)

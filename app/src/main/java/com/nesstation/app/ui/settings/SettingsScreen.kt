package com.nesstation.app.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.ui.components.PixelBackdrop

data class SettingsItem(
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    val trailing: Trailing = Trailing.Arrow,
    val accent: Color = Color(0xFF1E2A3A)
)

enum class Trailing { Arrow, Switch, Value }

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKeyMap: () -> Unit
) {
    val sections = remember {
        listOf(
            "视频" to listOf(
                SettingsItem("画面比例", "4:3", icon = null, trailing = Trailing.Value),
                SettingsItem("扫描线", "关", trailing = Trailing.Switch),
                SettingsItem("CRT 着色器", "关", trailing = Trailing.Switch),
                SettingsItem("画面滤镜", "Nearest", trailing = Trailing.Value),
            ),
            "音频" to listOf(
                SettingsItem("采样率", "44.1 kHz", trailing = Trailing.Value),
                SettingsItem("低延迟模式", "开", trailing = Trailing.Switch),
                SettingsItem("音量", "90", trailing = Trailing.Value),
            ),
            "输入" to listOf(
                SettingsItem("屏幕手柄", "显示", trailing = Trailing.Switch),
                SettingsItem("按键映射", "自定义", trailing = Trailing.Arrow),
                SettingsItem("手柄震动", "开", trailing = Trailing.Switch),
            ),
            "显示" to listOf(
                SettingsItem("TV 模式", "关", trailing = Trailing.Switch),
                SettingsItem("主题", "跟随系统", trailing = Trailing.Value),
                SettingsItem("横屏锁定", "自动", trailing = Trailing.Value),
            ),
            "关于" to listOf(
                SettingsItem("版本", "1.0.0", trailing = Trailing.Value),
                SettingsItem("核心", "FCEUmm", trailing = Trailing.Value),
                SettingsItem("开源许可", "", trailing = Trailing.Arrow),
            )
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PixelBackdrop()
        Column(modifier = Modifier.fillMaxSize().padding(top = 24.dp)) {
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
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                sections.forEach { (title, items) ->
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = title,
                                color = Color(0xFF1E2A3A),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color.White.copy(alpha = 0.65f))
                                    .padding(vertical = 4.dp)
                            ) {
                                items.forEachIndexed { i, s ->
                                    SettingsRow(s) {
                                        if (s.title == "按键映射") onOpenKeyMap()
                                    }
                                    if (i != items.lastIndex) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 60.dp, end = 16.dp)
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
}

@Composable
private fun SettingsRow(item: SettingsItem, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (item.icon != null) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(item.accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(item.icon, contentDescription = null, tint = item.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.size(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = Color(0xFF1E2A3A), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (item.subtitle != null) {
                Text(item.subtitle, color = Color(0xFF4A5568), fontSize = 12.sp)
            }
        }
        when (item.trailing) {
            Trailing.Arrow -> Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Color(0xFF4A5568))
            Trailing.Switch -> Switch(checked = item.subtitle == "开", onCheckedChange = { /* TODO */ },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFFE74C3C)))
            Trailing.Value -> Text(item.subtitle ?: "", color = Color(0xFF4A5568), fontSize = 13.sp)
        }
    }
}

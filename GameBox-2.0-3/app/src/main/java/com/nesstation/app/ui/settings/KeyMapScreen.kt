package com.nesstation.app.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.ui.components.PixelBackdrop

data class KeyMapping(val name: String, val key: String, val accent: Color)

@Composable
fun KeyMapScreen(onBack: () -> Unit) {
    val mappings = listOf(
        KeyMapping("A", "X", Color(0xFFE74C3C)),
        KeyMapping("B", "Z", Color(0xFFE67E22)),
        KeyMapping("Select", "Shift", Color(0xFF1E2A3A)),
        KeyMapping("Start", "Enter", Color(0xFF1E2A3A)),
        KeyMapping("Up", "↑", Color(0xFF3498DB)),
        KeyMapping("Down", "↓", Color(0xFF3498DB)),
        KeyMapping("Left", "←", Color(0xFF3498DB)),
        KeyMapping("Right", "→", Color(0xFF3498DB)),
        KeyMapping("Turbo A", "V", Color(0xFF8E44AD)),
        KeyMapping("Turbo B", "B", Color(0xFF8E44AD)),
    )
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
                Text("按键映射", color = Color(0xFF1E2A3A), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mappings) { m ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color.White.copy(alpha = 0.7f))
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(m.accent, RoundedCornerShape(50))
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(m.name, color = Color(0xFF1E2A3A), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(m.accent.copy(alpha = 0.15f))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(m.key, color = m.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

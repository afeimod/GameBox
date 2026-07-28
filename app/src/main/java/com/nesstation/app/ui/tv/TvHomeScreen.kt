package com.nesstation.app.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.ui.components.GameCard
import com.nesstation.app.ui.components.PixelBackdrop
import com.nesstation.app.ui.components.StatusBar

/**
 * TV home — large header, two horizontal sections, big pill shortcuts.
 * Uses the standard Compose Foundation LazyRow (TV focus traversal is
 * automatically wired up in Compose 1.6+).
 */
@Composable
fun TvHomeScreen(
    featured: List<GameEntry>,
    recents: List<GameEntry>,
    onOpenGame: (GameEntry) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        PixelBackdrop()
        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar()

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 48.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("NesStation", color = Color(0xFF1E2A3A), fontSize = 56.sp, fontWeight = FontWeight.ExtraBold)
                    Text("为客厅而生 · Android TV", color = Color(0xFF4A5568), fontSize = 18.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    TvPill("游戏库", onClick = onOpenLibrary)
                    TvPill("设置", onClick = onOpenSettings)
                }
            }

            SectionLabel("精选")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth().height(220.dp)
            ) {
                items(featured) { g ->
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        modifier = Modifier.size(width = 180.dp, height = 200.dp)
                    )
                }
            }

            SectionLabel("最近游玩")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth().height(220.dp)
            ) {
                items(recents) { g ->
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        modifier = Modifier.size(width = 180.dp, height = 200.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = Color(0xFF1E2A3A),
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 48.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun TvPill(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .background(
                color = Color.White.copy(alpha = 0.6f),
                shape = RoundedCornerShape(24.dp)
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(label, color = Color(0xFF1E2A3A), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

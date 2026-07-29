package com.nesstation.app.ui.home

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.ui.components.BottomDock
import com.nesstation.app.ui.components.GameCard
import com.nesstation.app.ui.components.PixelBackdrop
import kotlinx.coroutines.delay

/**
 * Main home screen — only shows "最近游玩" (recent games) section.
 * Compact header, content moved up, small centered dock at bottom.
 */
@Composable
fun HomeScreen(
    onOpenGame: (GameEntry) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) { time = System.currentTimeMillis(); delay(33) }
    }

    val recents = remember { HomeSamples.recents }

    Box(modifier = modifier.fillMaxSize()) {
        PixelBackdrop(timeMs = time)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp)
        ) {
            // Compact header — title + quick actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "NesStation",
                        color = Color(0xFF1E2A3A),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "为复古而生",
                        color = Color(0xFF4A5568),
                        fontSize = 11.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeaderPill(Icons.Rounded.Search, "搜索", onClick = onOpenSearch)
                    HeaderPill(Icons.Rounded.Save, "存档", onClick = onOpenHistory)
                }
            }

            // Section: 最近游玩 (only section on home)
            Text(
                text = "最近游玩",
                color = Color(0xFF1E2A3A),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 2.dp)
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.height(150.dp).fillMaxWidth()
            ) {
                items(recents) { g ->
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        modifier = Modifier.size(width = 120.dp, height = 145.dp)
                    )
                }
            }

            // Spacer to push dock to bottom
            Spacer(modifier = Modifier.weight(1f))
        }

        // Bottom dock — centered, small, doesn't block content
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 6.dp)
        ) {
            BottomDock(
                selectedIndex = 0,
                onSelect = { idx ->
                    when (idx) {
                        0 -> onOpenLibrary()
                        1 -> onOpenLibrary()
                        2 -> onOpenFavorites()
                        3 -> onOpenSettings()
                        4 -> { /* help */ }
                        5 -> { /* exit */ }
                    }
                }
            )
        }
    }
}

@Composable
private fun HeaderPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(
                color = Color.White.copy(alpha = 0.5f),
                shape = RoundedCornerShape(18.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF1E2A3A), modifier = Modifier.size(13.dp))
        Spacer(Modifier.size(3.dp))
        Text(label, color = Color(0xFF1E2A3A), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

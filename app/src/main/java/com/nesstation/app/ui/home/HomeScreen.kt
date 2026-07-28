package com.nesstation.app.ui.home

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.ui.components.BottomDock
import com.nesstation.app.ui.components.GameCard
import com.nesstation.app.ui.components.PixelBackdrop
import com.nesstation.app.ui.components.StatusBar
import kotlinx.coroutines.delay

/**
 * Main home screen — the "Pico-8 console boot" look. Horizontal carousel of
 * recent/favorite games, big bottom dock, pixel sky backdrop with drift.
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
    val featured = remember { HomeSamples.featured }

    Box(modifier = modifier.fillMaxSize()) {
        PixelBackdrop(timeMs = time)

        Column(modifier = Modifier.fillMaxSize()) {
            StatusBar()

            // Headline + actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "NesStation",
                        color = Color(0xFF1E2A3A),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = "为复古而生 · For phone & TV",
                        color = Color(0xFF4A5568),
                        fontSize = 13.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    DockPill(Icons.Rounded.Search, "搜索", onClick = onOpenSearch)
                    DockPill(Icons.Rounded.Save, "存档", onClick = onOpenHistory)
                }
            }

            // Section: 最近游玩
            SectionTitle("最近游玩", Icons.Rounded.History)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.height(178.dp).fillMaxWidth()
            ) {
                items(recents) { g ->
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        modifier = Modifier.size(width = 150.dp, height = 170.dp)
                    )
                }
            }

            // Section: 精选
            SectionTitle("精选收藏", Icons.Rounded.Casino)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.height(178.dp).fillMaxWidth()
            ) {
                items(featured) { g ->
                    GameCard(
                        title = g.title,
                        accent = g.accent,
                        onClick = { onOpenGame(g) },
                        modifier = Modifier.size(width = 150.dp, height = 170.dp)
                    )
                }
            }

            Box(modifier = Modifier.fillMaxSize().weight(1f))
        }

        // Bottom dock overlay
        Box(modifier = Modifier.align(Alignment.BottomCenter)) {
            BottomDock(
                selectedIndex = 0,
                onSelect = { idx ->
                    when (idx) {
                        0 -> onOpenLibrary()
                        1 -> onOpenLibrary()
                        2 -> onOpenFavorites()
                        3 -> onOpenSettings()
                        4 -> onOpenSettings()
                        5 -> { /* exit placeholder */ }
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color(0xFF1E2A3A),
            modifier = Modifier.size(18.dp)
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        Text(
            text = text,
            color = Color(0xFF1E2A3A),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DockPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(2.dp)
            .background(
                color = Color.White.copy(alpha = 0.5f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onClick)
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFF1E2A3A), modifier = Modifier.size(16.dp))
        androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
        Text(label, color = Color(0xFF1E2A3A), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

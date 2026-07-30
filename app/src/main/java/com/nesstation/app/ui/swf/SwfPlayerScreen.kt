package com.nesstation.app.ui.swf

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nesstation.app.core.storage.SwfPadStore
import java.io.File

// ---------------------------------------------------------------------------
// Colour palette (dark retro)
// ---------------------------------------------------------------------------
private val Bg = Color(0xFF0D1117)
private val PrimaryText = Color(0xFFE2E8F0)
private val SecondaryText = Color(0xFF8899AA)
private val Accent = Color(0xFF8A7BFF)

/**
 * SWF player screen — loads a .swf file via Ruffle in a WebView.
 *
 * Features:
 *  - Ruffle loaded from CDN via assets/ruffle/ruffle.js loader
 *  - Virtual keyboard overlay with configurable layout
 *  - Edit mode: drag buttons, resize, add / delete buttons
 *  - WASD / Arrow key toggle for D-pad
 *  - Quality selector (low / medium / high)
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SwfPlayerScreen(
    swfPath: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("medium") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Load persisted pad config
    var padConfig by remember { mutableStateOf(SwfPadStore.load(context)) }

    // Copy SWF to cache dir for WebView access
    val swfCachePath = remember {
        try {
            val src = File(swfPath)
            val dest = File(context.cacheDir, "current.swf")
            src.copyTo(dest, overwrite = true)
            "file://${dest.absolutePath}"
        } catch (_: Exception) {
            swfPath
        }
    }

    // System back: exit edit mode first, then exit player
    BackHandler {
        if (editMode) {
            editMode = false
        } else if (showMenu) {
            showMenu = false
        } else {
            onExit()
        }
    }

    // Save config whenever it changes
    fun updateConfig(newConfig: com.nesstation.app.core.storage.SwfPadConfig) {
        padConfig = newConfig
        SwfPadStore.save(context, newConfig)
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // ---- WebView (Ruffle player) ----
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = true
                    settings.allowFileAccessFromFileURLs = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    webViewClient = WebViewClient()
                    webChromeClient = WebChromeClient()
                    addJavascriptInterface(SwfInterface(swfCachePath), "Android")

                    val html = buildHtml(swfCachePath, quality)
                    loadDataWithBaseURL(
                        "file:///android_asset/ruffle/",
                        html, "text/html", "UTF-8", null
                    )
                    webViewRef = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // ---- Top bar ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onExit) {
                Icon(Icons.Rounded.ArrowBack, "退出", tint = PrimaryText)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { editMode = !editMode }) {
                Icon(
                    Icons.Rounded.DragHandle, "布局编辑",
                    tint = if (editMode) Accent else PrimaryText
                )
            }
            IconButton(onClick = { showMenu = !showMenu }) {
                Icon(Icons.Rounded.MoreVert, "菜单", tint = PrimaryText)
            }
        }

        // ---- Menu dropdown ----
        if (showMenu) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { showMenu = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp)
                    .background(Color(0xFF1E2A3A))
            ) {
                // Toggle keyboard visibility
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Keyboard, null, tint = Accent, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(
                                if (padConfig.showPad) "隐藏键盘" else "显示键盘",
                                color = PrimaryText, fontSize = 13.sp
                            )
                        }
                    },
                    onClick = {
                        updateConfig(padConfig.copy(showPad = !padConfig.showPad))
                        showMenu = false
                    }
                )
                // WASD toggle
                DropdownMenuItem(
                    text = {
                        Text(
                            if (padConfig.useWASD) "切换到方向键" else "切换到 WASD",
                            color = PrimaryText, fontSize = 13.sp
                        )
                    },
                    onClick = {
                        updateConfig(padConfig.copy(useWASD = !padConfig.useWASD))
                        showMenu = false
                    }
                )
                // Layout editor toggle
                DropdownMenuItem(
                    text = {
                        Text(
                            if (editMode) "退出布局编辑" else "进入布局编辑",
                            color = Accent, fontSize = 13.sp
                        )
                    },
                    onClick = {
                        editMode = !editMode
                        showMenu = false
                    }
                )
                HorizontalDivider(color = Color(0xFF2A3A4A))
                // Quality header
                Text(
                    "画质: $quality",
                    color = SecondaryText, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                listOf("low", "medium", "high").forEach { q ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = quality == q,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Accent)
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(q, color = PrimaryText, fontSize = 12.sp)
                            }
                        },
                        onClick = {
                            quality = q
                            webViewRef?.evaluateJavascript(
                                "if(window._rufflePlayer){window._rufflePlayer.config.quality='$q';}", null
                            )
                            showMenu = false
                        }
                    )
                }
            }
        }

        // ---- Virtual keyboard overlay ----
        if (padConfig.showPad) {
            VirtualKeyboard(
                config = padConfig,
                editMode = editMode,
                onKeyPress = { key -> injectKey(webViewRef, key, true) },
                onKeyRelease = { key -> injectKey(webViewRef, key, false) },
                onConfigChange = { newConfig -> updateConfig(newConfig) },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---- Edit-mode hint banner ----
        if (editMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 52.dp),
                color = Accent.copy(alpha = 0.2f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "拖动按钮移动 · 点击选择 · × 删除 · 完成后点击右上角图标退出",
                    color = PrimaryText,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Key injection into WebView
// ---------------------------------------------------------------------------

private fun injectKey(webView: WebView?, key: String, down: Boolean) {
    val type = if (down) "keydown" else "keyup"
    val code = keyToCode(key)
    val keyCode = keyToKeyCode(key)
    val js = """
        (function(){
            var e = new KeyboardEvent('$type', {
                key: '$key',
                code: '$code',
                keyCode: $keyCode,
                which: $keyCode,
                bubbles: true
            });
            document.dispatchEvent(e);
            window.dispatchEvent(e);
        })();
    """.trimIndent()
    webView?.evaluateJavascript(js, null)
}

private fun keyToCode(key: String): String = when (key.lowercase()) {
    "arrowup" -> "ArrowUp"
    "arrowdown" -> "ArrowDown"
    "arrowleft" -> "ArrowLeft"
    "arrowright" -> "ArrowRight"
    " " -> "Space"
    "enter" -> "Enter"
    "shift" -> "ShiftLeft"
    "control" -> "ControlLeft"
    "tab" -> "Tab"
    "escape" -> "Escape"
    else -> "Key${key.uppercase()}"
}

private fun keyToKeyCode(key: String): Int = when (key.lowercase()) {
    "arrowup" -> 38
    "arrowdown" -> 40
    "arrowleft" -> 37
    "arrowright" -> 39
    " " -> 32
    "enter" -> 13
    "shift" -> 16
    "control" -> 17
    "tab" -> 9
    "escape" -> 27
    else -> key.firstOrNull()?.code ?: 0
}

// ---------------------------------------------------------------------------
// JavaScript interface — exposes the SWF URL to the page
// ---------------------------------------------------------------------------

private class SwfInterface(val swfUrl: String) {
    @JavascriptInterface
    fun getSwfUrl(): String = swfUrl
}

// ---------------------------------------------------------------------------
// HTML template — loads Ruffle from CDN via assets/ruffle/ruffle.js
// ---------------------------------------------------------------------------

private fun buildHtml(swfUrl: String, quality: String): String {
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
<style>
  html,body{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden}
  #container{width:100%;height:100%}
  #loading{position:absolute;top:50%;left:50%;transform:translate(-50%,-50%);
    color:#8A7BFF;font-family:sans-serif;font-size:14px;text-align:center}
  #error{color:#ff6b6b}
</style>
</head>
<body>
<div id="loading">加载中...<br><small style="color:#8899AA">正在初始化 Ruffle 引擎</small></div>
<div id="container"></div>
<script src="ruffle.js"></script>
<script>
window.RufflePlayer = window.RufflePlayer || {};
window.RufflePlayer.config = {
    autoplay: "on",
    unmuteOverlay: "visible",
    letterbox: "fullscreen",
    allowScriptAccess: false,
    quality: "$quality",
    scale: "showAll",
    backgroundColor: "#000000"
};

window.addEventListener("load", function() {
    function tryCreatePlayer() {
        if (typeof window.RufflePlayer === "undefined" || !window.RufflePlayer.newest) {
            // Ruffle not loaded yet — retry after a short delay
            setTimeout(tryCreatePlayer, 300);
            return;
        }
        var ruffle = window.RufflePlayer.newest();
        if (!ruffle) {
            setTimeout(tryCreatePlayer, 300);
            return;
        }
        var player = ruffle.createPlayer();
        var container = document.getElementById("container");
        container.innerHTML = "";
        container.appendChild(player);
        window._rufflePlayer = player;
        player.style.width = "100%";
        player.style.height = "100%";
        player.ruffle().load("$swfUrl")
            .then(function() {
                document.getElementById("loading").style.display = "none";
            })
            .catch(function(e) {
                var el = document.getElementById("loading");
                el.innerHTML = "加载失败<br><small style='color:#ff6b6b'>" + e + "</small>";
                el.id = "error";
            });
    }
    // Small delay to ensure ruffle.js (CDN loader) has finished
    setTimeout(tryCreatePlayer, 500);
});
</script>
</body>
</html>
    """.trimIndent()
}

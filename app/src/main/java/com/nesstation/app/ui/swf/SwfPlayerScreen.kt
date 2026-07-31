package com.nesstation.app.ui.swf

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nesstation.app.core.storage.SwfPadStore
import java.net.URLEncoder
import java.util.Collections

// ---------------------------------------------------------------------------
// Colour palette (dark retro)
// ---------------------------------------------------------------------------
private val PrimaryText = Color(0xFFE2E8F0)
private val SecondaryText = Color(0xFF8899AA)
private val Accent = Color(0xFF8A7BFF)

// ---------------------------------------------------------------------------
// Key mapping — converts key name strings to JS KeyboardEvent info
// ---------------------------------------------------------------------------

private data class JsKeyInfo(val keyCode: Int, val key: String, val code: String)

private fun keyToJsInfo(key: String): JsKeyInfo = when (key.lowercase()) {
    "arrowup"    -> JsKeyInfo(38, "ArrowUp", "ArrowUp")
    "arrowdown"  -> JsKeyInfo(40, "ArrowDown", "ArrowDown")
    "arrowleft"  -> JsKeyInfo(37, "ArrowLeft", "ArrowLeft")
    "arrowright" -> JsKeyInfo(39, "ArrowRight", "ArrowRight")
    " "          -> JsKeyInfo(32, " ", "Space")
    "enter"      -> JsKeyInfo(13, "Enter", "Enter")
    "shift"      -> JsKeyInfo(16, "Shift", "ShiftLeft")
    "control"    -> JsKeyInfo(17, "Control", "ControlLeft")
    "tab"        -> JsKeyInfo(9, "Tab", "Tab")
    "escape"     -> JsKeyInfo(27, "Escape", "Escape")
    else -> {
        if (key.length == 1 && key[0].isLetter()) {
            JsKeyInfo(key[0].code, key, "Key${key.uppercase()}")
        } else if (key.length == 1 && key[0].isDigit()) {
            JsKeyInfo(key[0].code, key, "Digit$key")
        } else {
            JsKeyInfo(0, key, key)
        }
    }
}

// ---------------------------------------------------------------------------
// SWF Player Screen
// ---------------------------------------------------------------------------

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
    var engine by remember { mutableStateOf(FlashPrefs.getEngine(context)) }
    var quality by remember { mutableStateOf(FlashPrefs.getQuality(context)) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var orientation by remember { mutableStateOf("landscape") }

    // Apply orientation
    fun applyOrientation(mode: String) {
        val activity = context as? Activity ?: return
        activity.requestedOrientation = when (mode) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // Track pressed keys for heartbeat sync
    val pressedKeys = remember { Collections.synchronizedSet(HashSet<Int>()) }

    // Load persisted pad config
    var padConfig by remember { mutableStateOf(SwfPadStore.load(context)) }

    // Heartbeat handler — syncs key state every 300ms
    val heartbeatHandler = remember { Handler(Looper.getMainLooper()) }
    val heartbeatRunnable = remember {
        object : Runnable {
            override fun run() {
                val keys = synchronized(pressedKeys) { pressedKeys.toIntArray() }
                if (keys.isNotEmpty()) {
                    val keysStr = keys.joinToString(",")
                    webViewRef?.evaluateJavascript(
                        "window.__gameKeys && window.__gameKeys.sync([$keysStr]);", null
                    )
                }
                heartbeatHandler.postDelayed(this, 300)
            }
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

    // Lifecycle: pause/resume heartbeat, release keys on pause
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    heartbeatHandler.postDelayed(heartbeatRunnable, 300)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    heartbeatHandler.removeCallbacks(heartbeatRunnable)
                    releaseAllKeys(webViewRef, pressedKeys)
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            heartbeatHandler.removeCallbacks(heartbeatRunnable)
        }
    }

    // Release keys when keyboard is hidden
    LaunchedEffect(padConfig.showPad) {
        if (!padConfig.showPad) {
            releaseAllKeys(webViewRef, pressedKeys)
        }
    }

    fun updateConfig(newConfig: com.nesstation.app.core.storage.SwfPadConfig) {
        padConfig = newConfig
        SwfPadStore.save(context, newConfig)
    }

    // Build player URL based on engine selection（统一走 NavHelper，与 WebGameScreen 行为一致）
    val playerUrl = remember(swfPath, engine, quality) {
        val swfProxy = "https://flash.local/local.swf?t=${System.currentTimeMillis()}"
        NavHelper.playerUrl(
            swfUrl = swfProxy,
            base = null,
            engine = engine,
            quality = quality,
            autoplay = FlashPrefs.isAutoplay(context),
            title = null
        )
    }

    // Track which engine was loaded to trigger reload on change
    var loadedEngine by remember { mutableStateOf<FlashPrefs.Engine?>(null) }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // ---- WebView (Flash player) ----
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
                    settings.allowUniversalAccessFromFileURLs = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    webViewClient = FlashWebViewClient(swfPath)
                    webChromeClient = WebChromeClient()
                    isHorizontalScrollBarEnabled = false
                    isVerticalScrollBarEnabled = false
                    isFocusable = true
                    isFocusableInTouchMode = true
                    loadUrl(playerUrl)
                    webViewRef = this
                    loadedEngine = engine
                }
            },
            update = { web ->
                if (loadedEngine != engine) {
                    web.loadUrl(playerUrl)
                    loadedEngine = engine
                    releaseAllKeys(web, pressedKeys)
                }
            },
            onRelease = { web ->
                releaseAllKeys(web, pressedKeys)
                web.stopLoading()
                web.destroy()
                webViewRef = null
                // Restore landscape orientation on exit
                applyOrientation("landscape")
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
            IconButton(onClick = {
                releaseAllKeys(webViewRef, pressedKeys)
                onExit()
            }) {
                Icon(Icons.Rounded.ArrowBack, "退出", tint = PrimaryText)
            }
            Spacer(Modifier.weight(1f))
            // Engine badge
            Text(
                engine.value.uppercase(),
                color = Accent,
                fontSize = 10.sp,
                modifier = Modifier.padding(end = 4.dp)
            )
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
                // D-pad mode cycle: JOYSTICK -> DPAD -> WASD -> JOYSTICK
                DropdownMenuItem(
                    text = {
                        Text(
                            "方向模式: " + when (padConfig.dpadMode) {
                                com.nesstation.app.core.storage.DpadMode.JOYSTICK -> "摇杆WASD (点击切换→方向键)"
                                com.nesstation.app.core.storage.DpadMode.DPAD -> "方向键 (点击切换→WASD)"
                                com.nesstation.app.core.storage.DpadMode.WASD -> "WASD十字 (点击切换→摇杆)"
                            },
                            color = PrimaryText, fontSize = 12.sp
                        )
                    },
                    onClick = {
                        val nextMode = when (padConfig.dpadMode) {
                            com.nesstation.app.core.storage.DpadMode.JOYSTICK -> com.nesstation.app.core.storage.DpadMode.DPAD
                            com.nesstation.app.core.storage.DpadMode.DPAD -> com.nesstation.app.core.storage.DpadMode.WASD
                            com.nesstation.app.core.storage.DpadMode.WASD -> com.nesstation.app.core.storage.DpadMode.JOYSTICK
                        }
                        updateConfig(padConfig.copy(dpadMode = nextMode))
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
                // Screen orientation
                Text(
                    "屏幕方向: " + when (orientation) {
                        "portrait" -> "竖屏"
                        "auto" -> "自动旋转"
                        else -> "横屏"
                    },
                    color = SecondaryText, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                DropdownMenuItem(
                    text = { Text("横屏", color = if (orientation == "landscape") Accent else PrimaryText, fontSize = 12.sp) },
                    onClick = { orientation = "landscape"; applyOrientation("landscape"); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("竖屏", color = if (orientation == "portrait") Accent else PrimaryText, fontSize = 12.sp) },
                    onClick = { orientation = "portrait"; applyOrientation("portrait"); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("自动旋转", color = if (orientation == "auto") Accent else PrimaryText, fontSize = 12.sp) },
                    onClick = { orientation = "auto"; applyOrientation("auto"); showMenu = false }
                )
                HorizontalDivider(color = Color(0xFF2A3A4A))
                // Engine selection — Ruffle / WAFlash
                Text(
                    "引擎: " + engine.displayName,
                    color = Accent, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = engine == FlashPrefs.Engine.RUFFLE,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Accent)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("Ruffle (AS1/2/3, 内置中文字体)", color = PrimaryText, fontSize = 12.sp)
                        }
                    },
                    onClick = {
                        engine = FlashPrefs.Engine.RUFFLE
                        FlashPrefs.setEngine(context, FlashPrefs.Engine.RUFFLE)
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = engine == FlashPrefs.Engine.WAFLASH,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Accent)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("WAFlash (AS2/AS3, Canvas渲染)", color = PrimaryText, fontSize = 12.sp)
                        }
                    },
                    onClick = {
                        engine = FlashPrefs.Engine.WAFLASH
                        FlashPrefs.setEngine(context, FlashPrefs.Engine.WAFLASH)
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
                listOf("low", "medium", "high", "best").forEach { q ->
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
                            FlashPrefs.setQuality(context, q)
                            webViewRef?.evaluateJavascript(
                                "window.__setQuality && window.__setQuality('$q');", null
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
                onKeyPress = { key -> injectKeyDown(webViewRef, key, pressedKeys) },
                onKeyRelease = { key -> injectKeyUp(webViewRef, key, pressedKeys) },
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
// Key injection into WebView via __gameKeys JS manager
// ---------------------------------------------------------------------------

private fun injectKeyDown(webView: WebView?, key: String, pressedKeys: MutableSet<Int>) {
    val info = keyToJsInfo(key)
    pressedKeys.add(info.keyCode)
    webView?.evaluateJavascript(
        "window.__gameKeys && window.__gameKeys.down(${info.keyCode}, '${info.key}', '${info.code}');",
        null
    )
}

private fun injectKeyUp(webView: WebView?, key: String, pressedKeys: MutableSet<Int>) {
    val info = keyToJsInfo(key)
    pressedKeys.remove(info.keyCode)
    webView?.evaluateJavascript(
        "window.__gameKeys && window.__gameKeys.up(${info.keyCode}, '${info.key}', '${info.code}');",
        null
    )
}

private fun releaseAllKeys(webView: WebView?, pressedKeys: MutableSet<Int>) {
    synchronized(pressedKeys) {
        pressedKeys.clear()
    }
    webView?.evaluateJavascript(
        "window.__gameKeys && window.__gameKeys.releaseAll();",
        null
    )
}

package com.nesstation.app.ui.online

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nesstation.app.ui.swf.FlashPrefs
import com.nesstation.app.ui.swf.FlashWebViewClient
import com.nesstation.app.ui.swf.GameWebView
import com.nesstation.app.ui.swf.NavHelper
import com.nesstation.app.ui.swf.VirtualKeyboard
import com.nesstation.app.core.storage.SwfPadStore
import com.nesstation.app.core.storage.SwfPadConfig
import kotlin.math.roundToInt

// ---- Dark palette (matches app theme) ----
private val Accent = Color(0xFF8A7BFF)
private val PrimaryText = Color(0xFFE2E8F0)
private val SecondaryText = Color(0xFF8899AA)
private val MenuBg = Color(0xFF1E2A3A)
private val FloatBtnBg = Color(0xFF1E2A3A).copy(alpha = 0.8f)
private val DividerColor = Color(0xFF2A3A4A)

// ---- Floating button geometry ----
private val FloatingButtonSize = 44.dp
private val FloatingButtonMargin = 16.dp

// ---- User-Agent strings ----
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val MOBILE_UA_SUFFIX = " NesStation/1.0"

/**
 * Build viewport meta injection script for zoom control.
 */
private fun viewportScript(zoomPct: Int): String {
    val scale = (zoomPct / 100.0).let { String.format("%.2f", it) }
    return """
(function(){
  var metas = document.querySelectorAll('meta[name="viewport"]');
  metas.forEach(function(m){ m.remove(); });
  var meta = document.createElement('meta');
  meta.name = 'viewport';
  meta.content = 'width=device-width, initial-scale=$scale, minimum-scale=0.1, maximum-scale=10.0, user-scalable=yes';
  document.head.appendChild(meta);
})();
""".trimIndent()
}

/**
 * 在线网页游戏浏览界面（完全按 3.3-fix2 模式重写）。
 *
 * 关键修复（v3 全面重写）：
 * 1. 使用 [GameWebView]（自定义 WebView）替代普通 WebView → 自动注入 `__gameKeys` 状态管理器
 *    → 虚拟手柄能通过 `injectKey()` 把按键分发到 window → Ruffle 必定收到
 * 2. 完整悬浮菜单（参考 3.3-fix2 FloatingMenuView）：Flash 引擎 / 画质 / UA 模式 / 缩放 /
 *    提取 SWF / 手柄开关 等
 * 3. SWF 检测到时通过 [WebAppInterface.openSwf] 回调，按当前 [FlashPrefs.engine] 选
 *    `player.html` 或 `waflash.html`（统一走 [NavHelper.playerUrl]）
 * 4. 拦截 .swf 直链 → 同上处理
 * 5. 支持 SWF 嗅探器（[FlashWebViewClient.SWF_SNIFFER_SCRIPT]）从 [WebAppInterface.onSwfFound] 回调
 * 6. 虚拟手柄（DPad + 动作按钮）通过 SwfPadConfig 持久化配置
 * 7. 物理键盘透传（GAME_KEYS 白名单）
 *
 * @param url       要加载的游戏网址
 * @param uaMode    初始 UA 模式："desktop" 或 "mobile"
 * @param onExit    退出当前界面回调
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebGameScreen(
    url: String,
    uaMode: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var currentUaMode by remember(uaMode) {
        mutableStateOf(if (uaMode == "mobile") "mobile" else "desktop")
    }
    var webViewRef by remember { mutableStateOf<GameWebView?>(null) }
    var defaultUA by remember { mutableStateOf<String?>(null) }
    var loadedUrl by remember { mutableStateOf(url) }
    var showMenu by remember { mutableStateOf(false) }
    var showZoomSlider by remember { mutableStateOf(false) }

    var zoomPct by remember { mutableFloatStateOf(100f) }
    var orientation by remember { mutableStateOf("landscape") }

    // Flash 引擎设置（从 FlashPrefs 同步，菜单改动会触发 reload）
    var flashEnabled by remember { mutableStateOf(true) }
    var flashEngine by remember { mutableStateOf(FlashPrefs.getEngine(context)) }
    var flashQuality by remember { mutableStateOf(FlashPrefs.getQuality(context)) }

    // 提取 SWF 对话框状态
    var swfExtractJson by remember { mutableStateOf<String?>(null) }
    var showExtractDialog by remember { mutableStateOf(false) }

    // 虚拟手柄状态
    var padConfig by remember { mutableStateOf(SwfPadStore.load(context)) }
    var editMode by remember { mutableStateOf(false) }

    // 屏幕方向
    fun applyOrientation(mode: String) {
        val activity = context as? Activity ?: return
        activity.requestedOrientation = when (mode) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun applyFlashInject(view: WebView?, pageUrl: String?) {
        if (view == null) return
        if (!flashEnabled) return
        // 排除内置 flash.local / file://android_asset 页面
        val u = pageUrl ?: ""
        if (u.startsWith("https://flash.local/")) return
        if (u.startsWith("file:///android_asset/")) return
        view.evaluateJavascript(
            FlashWebViewClient.buildFlashInjectScript(
                pageUrl = u,
                engine = flashEngine.value,
                autoplay = FlashPrefs.isAutoplay(context),
                quality = flashQuality
            ),
            null
        )
    }

    // SWF 打开处理：按当前引擎选 player.html / waflash.html
    fun openSwfWithEngine(swfUrl: String, pageUrl: String) {
        val web = webViewRef ?: return
        val playerUrl = NavHelper.playerUrl(
            swfUrl = swfUrl,
            base = pageUrl.takeIf { it.isNotEmpty() },
            engine = flashEngine,
            quality = flashQuality,
            autoplay = FlashPrefs.isAutoplay(context),
            title = null
        )
        Log.d("WebGameScreen", "openSwf: $swfUrl engine=${flashEngine.value} → $playerUrl")
        web.loadUrl(playerUrl)
    }

    // 系统返回：先关菜单/对话框/手柄编辑，再 WebView 后退，最后退出
    BackHandler {
        when {
            showMenu -> showMenu = false
            showZoomSlider -> showZoomSlider = false
            showExtractDialog -> showExtractDialog = false
            editMode -> editMode = false
            else -> {
                val web = webViewRef
                if (web != null && web.canGoBack()) web.goBack() else onExit()
            }
        }
    }

    // 生命周期：暂停时释放所有按键
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                webViewRef?.releaseAllKeys()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webViewRef?.releaseAllKeys()
        }
    }

    // 切换手柄可见性时释放按键
    LaunchedEffect(padConfig.showPad) {
        if (!padConfig.showPad) webViewRef?.releaseAllKeys()
    }

    fun updatePadConfig(newConfig: SwfPadConfig) {
        padConfig = newConfig
        SwfPadStore.save(context, newConfig)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val btnPx = with(density) { FloatingButtonSize.toPx() }
        val marginPx = with(density) { FloatingButtonMargin.toPx() }

        val initX = if (widthPx.isFinite() && widthPx > btnPx + marginPx) {
            widthPx - btnPx - marginPx
        } else marginPx
        val initY = if (heightPx.isFinite()) marginPx else 0f
        var floatOffset by remember { mutableStateOf(Offset(initX, initY)) }

        // ---- GameWebView (全屏) ----
        AndroidView(
            factory = { ctx ->
                GameWebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    val mainHandler = Handler(Looper.getMainLooper())

                    // WebAppInterface：openSwf / onSwfFound / toast / vibrate 等
                    val webAppInterface = WebAppInterface(ctx)
                    webAppInterface.onSwfDetected = { swfUrl, pageUrl ->
                        mainHandler.post { openSwfWithEngine(swfUrl, pageUrl) }
                    }
                    webAppInterface.swfExtractCallback = { json ->
                        mainHandler.post {
                            swfExtractJson = json
                            showExtractDialog = true
                        }
                    }
                    addJavascriptInterface(webAppInterface, "Android")

                    // FlashWebViewClient：拦截 flash.local + .swf + 远程 SWF
                    webViewClient = object : FlashWebViewClient(
                        swfFilePath = "",
                        blockAds = false,
                        callback = object : FlashWebViewClient.Callback {
                            override fun isFlashEnabled() = flashEnabled
                            override fun getFlashEngine() = flashEngine.value
                            override fun getFlashQuality() = flashQuality
                            override fun isFlashAutoplay() = FlashPrefs.isAutoplay(context)
                            override fun shouldInjectRuffle(url: String?): Boolean {
                                if (!flashEnabled) return false
                                if (url == null) return false
                                // 不注入内置播放器页（避免循环）
                                if (url.startsWith("https://flash.local/")) return false
                                if (url.startsWith("file:///android_asset/")) return false
                                return true
                            }
                            override fun onSwfIntercepted(swfUrl: String, pageUrl: String) {
                                mainHandler.post { openSwfWithEngine(swfUrl, pageUrl) }
                            }
                            override fun onSwfFound(json: String) {
                                mainHandler.post {
                                    swfExtractJson = json
                                    showExtractDialog = true
                                }
                            }
                        }
                    ) {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            // 页面切换释放按键，防止 Ruffle 角色卡住
                            webViewRef?.releaseAllKeys()
                            view?.evaluateJavascript(viewportScript(zoomPct.toInt()), null)
                            applyFlashInject(view, url)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(viewportScript(zoomPct.toInt()), null)
                            applyFlashInject(view, url)
                        }
                    }

                    // 抓取默认 UA
                    val baseUA = settings.userAgentString
                    defaultUA = baseUA
                    settings.userAgentString =
                        if (currentUaMode == "desktop") DESKTOP_UA else baseUA + MOBILE_UA_SUFFIX

                    applyOrientation(orientation)
                    loadUrl(url)
                    webViewRef = this
                }
            },
            update = { web ->
                val baseUA = defaultUA ?: web.settings.userAgentString
                val targetUA = if (currentUaMode == "desktop") DESKTOP_UA else baseUA + MOBILE_UA_SUFFIX
                if (web.settings.userAgentString != targetUA) {
                    web.settings.userAgentString = targetUA
                    web.reload()
                }
                if (url != loadedUrl) {
                    web.loadUrl(url)
                    loadedUrl = url
                }
            },
            onRelease = { web ->
                web.releaseAllKeys()
                web.stopLoading()
                web.destroy()
                webViewRef = null
                applyOrientation("landscape")
            },
            modifier = Modifier.fillMaxSize()
        )

        // ---- 虚拟手柄（在 WebView 之上） ----
        if (padConfig.showPad && webViewRef != null) {
            VirtualKeyboard(
                config = padConfig,
                editMode = editMode,
                onKeyPress = { key ->
                    val code = keyCodeFromString(key)
                    if (code != 0) webViewRef?.injectKeyDown(code)
                },
                onKeyRelease = { key ->
                    val code = keyCodeFromString(key)
                    if (code != 0) webViewRef?.injectKeyUp(code)
                },
                onConfigChange = { newConfig -> updatePadConfig(newConfig) },
                modifier = Modifier.fillMaxSize()
            )
        }

        // ---- 悬浮菜单按钮 ----
        Box(
            modifier = Modifier
                .offset { IntOffset(floatOffset.x.roundToInt(), floatOffset.y.roundToInt()) }
                .size(FloatingButtonSize)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(FloatBtnBg)
                .pointerInput(widthPx, heightPx) {
                    detectDragGestures { change, dragAmount ->
                        val maxX = (widthPx - btnPx).coerceAtLeast(0f)
                        val maxY = (heightPx - btnPx).coerceAtLeast(0f)
                        floatOffset = Offset(
                            (floatOffset.x + dragAmount.x).coerceIn(0f, maxX),
                            (floatOffset.y + dragAmount.y).coerceIn(0f, maxY)
                        )
                        change.consume()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { showMenu = true }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "菜单", tint = Accent)
        }

        // ---- 菜单 ----
        if (showMenu) {
            DropdownMenu(
                expanded = true,
                onDismissRequest = { showMenu = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 8.dp)
                    .background(MenuBg)
            ) {
                // 标题栏：UA
                Text(
                    "UA：" + if (currentUaMode == "desktop") "桌面模式" else "移动模式",
                    color = Accent, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                DropdownMenuItem(
                    text = { Text("切换UA", color = PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        currentUaMode = if (currentUaMode == "desktop") "mobile" else "desktop"
                        showMenu = false
                    }
                )
                HorizontalDivider(color = DividerColor)

                // 方向
                Text(
                    "方向：" + when (orientation) {
                        "portrait" -> "竖屏"
                        "auto" -> "自动旋转"
                        else -> "横屏"
                    },
                    color = Accent, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                DropdownMenuItem(
                    text = { Text("横屏", color = if (orientation == "landscape") Accent else PrimaryText, fontSize = 13.sp) },
                    onClick = { orientation = "landscape"; applyOrientation("landscape"); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("竖屏", color = if (orientation == "portrait") Accent else PrimaryText, fontSize = 13.sp) },
                    onClick = { orientation = "portrait"; applyOrientation("portrait"); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("自动旋转", color = if (orientation == "auto") Accent else PrimaryText, fontSize = 13.sp) },
                    onClick = { orientation = "auto"; applyOrientation("auto"); showMenu = false }
                )
                HorizontalDivider(color = DividerColor)

                // 缩放
                DropdownMenuItem(
                    text = { Text("页面缩放: ${zoomPct.toInt()}%", color = PrimaryText, fontSize = 13.sp) },
                    onClick = { showZoomSlider = true; showMenu = false }
                )
                HorizontalDivider(color = DividerColor)

                // 虚拟手柄开关
                DropdownMenuItem(
                    text = { Text(if (padConfig.showPad) "隐藏虚拟手柄" else "显示虚拟手柄", color = PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        updatePadConfig(padConfig.copy(showPad = !padConfig.showPad))
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text(if (editMode) "退出布局编辑" else "进入布局编辑", color = if (editMode) Accent else PrimaryText, fontSize = 13.sp) },
                    onClick = { editMode = !editMode; showMenu = false }
                )
                HorizontalDivider(color = DividerColor)

                // Flash 引擎
                Text(
                    "Flash引擎: " + if (flashEnabled) flashEngine.displayName else "已关闭",
                    color = if (flashEnabled) Accent else SecondaryText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = flashEnabled && flashEngine == FlashPrefs.Engine.RUFFLE,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Accent)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("Ruffle (内置中文字体 simhei.ttf)", color = PrimaryText, fontSize = 12.sp)
                        }
                    },
                    onClick = {
                        flashEnabled = true
                        flashEngine = FlashPrefs.Engine.RUFFLE
                        FlashPrefs.setEngine(context, FlashPrefs.Engine.RUFFLE)
                        webViewRef?.reload()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = flashEnabled && flashEngine == FlashPrefs.Engine.WAFLASH,
                                onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Accent)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text("WAFlash (Canvas 渲染)", color = PrimaryText, fontSize = 12.sp)
                        }
                    },
                    onClick = {
                        flashEnabled = true
                        flashEngine = FlashPrefs.Engine.WAFLASH
                        FlashPrefs.setEngine(context, FlashPrefs.Engine.WAFLASH)
                        webViewRef?.reload()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("关闭 Flash", color = if (!flashEnabled) Color(0xFFE74C3C) else SecondaryText, fontSize = 12.sp) },
                    onClick = { flashEnabled = false; webViewRef?.reload(); showMenu = false }
                )
                HorizontalDivider(color = DividerColor)

                // 画质
                Text(
                    "画质: $flashQuality", color = SecondaryText, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                listOf("low", "medium", "high", "best").forEach { q ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = flashQuality == q, onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Accent)
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(q, color = PrimaryText, fontSize = 12.sp)
                            }
                        },
                        onClick = {
                            flashQuality = q
                            FlashPrefs.setQuality(context, q)
                            showMenu = false
                        }
                    )
                }
                HorizontalDivider(color = DividerColor)

                // 提取 SWF
                DropdownMenuItem(
                    text = { Text("提取页面中的 SWF", color = PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        webViewRef?.evaluateJavascript(
                            FlashWebViewClient.SWF_SNIFFER_SCRIPT, null
                        )
                        showMenu = false
                    }
                )
                HorizontalDivider(color = DividerColor)

                // 导航
                DropdownMenuItem(
                    text = { Text("刷新页面", color = PrimaryText, fontSize = 13.sp) },
                    onClick = { webViewRef?.reload(); showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("返回", color = PrimaryText, fontSize = 13.sp) },
                    onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() }; showMenu = false }
                )
                DropdownMenuItem(
                    text = { Text("前进", color = PrimaryText, fontSize = 13.sp) },
                    onClick = { webViewRef?.let { if (it.canGoForward()) it.goForward() }; showMenu = false }
                )
                HorizontalDivider(color = DividerColor)

                DropdownMenuItem(
                    text = { Text("退出", color = Color(0xFFE74C3C), fontSize = 13.sp) },
                    onClick = { showMenu = false; onExit() }
                )
            }
        }

        // ---- 缩放滑块 ----
        if (showZoomSlider) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .pointerInput(Unit) { detectTapGestures { showZoomSlider = false } },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 32.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .background(MenuBg)
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("25%", color = SecondaryText, fontSize = 10.sp)
                    Spacer(Modifier.width(8.dp))
                    Slider(
                        value = zoomPct,
                        onValueChange = { zoomPct = it },
                        onValueChangeFinished = {
                            webViewRef?.evaluateJavascript(
                                viewportScript(zoomPct.toInt()), null
                            )
                        },
                        valueRange = 25f..200f,
                        colors = SliderDefaults.colors(
                            thumbColor = Accent,
                            activeTrackColor = Accent.copy(alpha = 0.7f)
                        ),
                        modifier = Modifier.width(180.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("${zoomPct.toInt()}%", color = PrimaryText, fontSize = 12.sp)
                }
            }
        }

        // ---- 提取 SWF 列表对话框 ----
        if (showExtractDialog && swfExtractJson != null) {
            SwfExtractDialog(
                json = swfExtractJson!!,
                onDismiss = { showExtractDialog = false; swfExtractJson = null },
                onPick = { swfUrl ->
                    showExtractDialog = false
                    swfExtractJson = null
                    openSwfWithEngine(swfUrl, webViewRef?.url ?: "")
                }
            )
        }
    }
}

/**
 * SWF 列表选择对话框（从 JS 嗅探器结果解析）。
 */
@Composable
private fun SwfExtractDialog(
    json: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    data class Item(val url: String, val title: String)

    val swfList = remember(json) {
        try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("url", "")
                if (u.isEmpty()) null else Item(u, o.optString("title", ""))
            }
        } catch (_: Exception) { emptyList() }
    }
    if (swfList.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("提取 SWF") },
            text = { Text("未在页面中发现 SWF 文件") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("确定") }
            }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现 ${swfList.size} 个 SWF") },
            text = {
                LazyColumn {
                    items(count = swfList.size) { idx ->
                        val it = swfList[idx]
                        androidx.compose.material3.TextButton(
                            onClick = { onPick(it.url) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                Text(it.title.ifEmpty { it.url.substringAfterLast('/') }, color = PrimaryText, fontSize = 13.sp)
                                Text(it.url, color = SecondaryText, fontSize = 10.sp, maxLines = 1)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭") }
            }
        )
    }
}

private fun keyCodeFromString(key: String): Int = when (key.lowercase()) {
    "arrowup", "up"       -> KeyEvent.KEYCODE_DPAD_UP
    "arrowdown", "down"   -> KeyEvent.KEYCODE_DPAD_DOWN
    "arrowleft", "left"   -> KeyEvent.KEYCODE_DPAD_LEFT
    "arrowright", "right" -> KeyEvent.KEYCODE_DPAD_RIGHT
    " "                   -> KeyEvent.KEYCODE_SPACE
    "enter"               -> KeyEvent.KEYCODE_ENTER
    "tab"                 -> KeyEvent.KEYCODE_TAB
    "escape", "esc"       -> KeyEvent.KEYCODE_ESCAPE
    "shift"               -> KeyEvent.KEYCODE_SHIFT_LEFT
    "control", "ctrl"     -> KeyEvent.KEYCODE_CTRL_LEFT
    "alt"                 -> KeyEvent.KEYCODE_ALT_LEFT
    else -> {
        if (key.length == 1 && key[0].isLetter()) {
            KeyEvent.KEYCODE_A + (key[0].lowercaseChar() - 'a')
        } else if (key.length == 1 && key[0].isDigit()) {
            KeyEvent.KEYCODE_0 + (key[0].digitToInt())
        } else 0
    }
}

package com.nesstation.app.ui.online

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nesstation.app.ui.swf.FlashPrefs
import com.nesstation.app.ui.swf.FlashWebViewClient
import java.net.URLEncoder
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
 * @param zoomPct  zoom percentage (25..200, 100 = default)
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
"""
}

/**
 * 在线网页游戏浏览界面。
 *
 * - 全屏 [WebView] 加载指定 URL；
 * - 自动注入 Flash 引擎（Ruffle polyfill 或 WAFlash 检测），使网页中的 Flash 游戏可正常播放；
 * - 右上角悬浮可拖拽按钮提供菜单（切换 UA / 缩放 / 横竖屏 / 引擎 / 刷新 / 返回 / 前进 / 退出）；
 * - 系统返回键优先关闭菜单，其次在 WebView 历史中后退，最后退出界面。
 *
 * @param url       要加载的游戏网址
 * @param uaMode    初始 UA 模式："desktop" 或 "mobile"
 * @param onExit    退出当前界面回调
 * @param modifier  修饰符
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
    // 当前 UA 模式（可在菜单内切换，初始值取自入参）
    var currentUaMode by remember(uaMode) {
        mutableStateOf(if (uaMode == "mobile") "mobile" else "desktop")
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var defaultUA by remember { mutableStateOf<String?>(null) }
    var loadedUrl by remember { mutableStateOf(url) }
    var showMenu by remember { mutableStateOf(false) }
    var showZoomSlider by remember { mutableStateOf(false) }

    // 缩放百分比 (100 = 默认)
    var zoomPct by remember { mutableFloatStateOf(100f) }

    // 屏幕方向: "landscape" / "portrait" / "auto"
    var orientation by remember { mutableStateOf("landscape") }

    // Flash 引擎注入开关
    var flashEnabled by remember { mutableStateOf(true) }

    // Flash 引擎选择：Ruffle (polyfill) 或 WAFlash (检测+跳转播放器)
    var flashEngine by remember { mutableStateOf(FlashPrefs.getEngine(context)) }

    // 应用屏幕方向
    fun applyOrientation(mode: String) {
        val activity = context as? Activity ?: return
        activity.requestedOrientation = when (mode) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "auto" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    // 系统返回：先关菜单 → 再 WebView 后退 → 最后退出
    BackHandler {
        val web = webViewRef
        when {
            showMenu -> showMenu = false
            showZoomSlider -> showZoomSlider = false
            web != null && web.canGoBack() -> web.goBack()
            else -> onExit()
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val btnPx = with(density) { FloatingButtonSize.toPx() }
        val marginPx = with(density) { FloatingButtonMargin.toPx() }

        val initX = if (widthPx.isFinite() && widthPx > btnPx + marginPx) {
            widthPx - btnPx - marginPx
        } else {
            marginPx
        }
        val initY = if (heightPx.isFinite()) marginPx else 0f
        var floatOffset by remember { mutableStateOf(Offset(initX, initY)) }

        // ---- WebView（全屏） ----
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    // 基础能力：JS / DOM 存储 / 宽视口 / 混合内容 / 缩放
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.setSupportZoom(true)
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.javaScriptCanOpenWindowsAutomatically = true

                    // 允许 Cookie（含第三方）
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    // FlashWebViewClient: 拦截 flash.local 虚拟域名 + 远程 SWF 下载 + .swf 链接拦截
                    val mainHandler = Handler(Looper.getMainLooper())
                    webViewClient = object : FlashWebViewClient(swfFilePath = "", blockAds = false) {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            // 注入 viewport 缩放
                            view?.evaluateJavascript(viewportScript(zoomPct.toInt()), null)
                            // 注入 Flash 引擎（Ruffle polyfill 或 WAFlash 检测脚本）
                            if (flashEnabled) {
                                view?.evaluateJavascript(
                                    FlashWebViewClient.buildFlashInjectScript(
                                        url ?: "", flashEngine.value, true
                                    ), null
                                )
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 页面加载完成后再次注入（确保动态加载的内容也能被处理）
                            view?.evaluateJavascript(viewportScript(zoomPct.toInt()), null)
                            if (flashEnabled) {
                                view?.evaluateJavascript(
                                    FlashWebViewClient.buildFlashInjectScript(
                                        url ?: "", flashEngine.value, true
                                    ), null
                                )
                            }
                        }
                    }

                    // WebAppInterface: WAFlash 检测到 SWF 后回调，加载 WAFlash 播放器
                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun openSwf(swfUrl: String?, pageUrl: String?) {
                            if (swfUrl.isNullOrEmpty()) return
                            Log.d("WebGameScreen", "WAFlash openSwf: $swfUrl (from: $pageUrl)")
                            mainHandler.post {
                                val encodedSwf = URLEncoder.encode(swfUrl, "UTF-8")
                                val encodedBase = pageUrl?.let { 
                                    "&base=" + URLEncoder.encode(it, "UTF-8") 
                                } ?: ""
                                val playerUrl = "https://flash.local/waflash.html?swf=$encodedSwf$encodedBase"
                                Log.d("WebGameScreen", "Loading WAFlash player: $playerUrl")
                                webViewRef?.loadUrl(playerUrl)
                            }
                        }
                    }, "Android")

                    // 抓取默认 UA 并应用初始 UA 模式
                    val baseUA = settings.userAgentString
                    defaultUA = baseUA
                    settings.userAgentString =
                        if (currentUaMode == "desktop") DESKTOP_UA else baseUA + MOBILE_UA_SUFFIX

                    // 应用初始屏幕方向
                    applyOrientation(orientation)

                    loadUrl(url)
                    webViewRef = this
                }
            },
            update = { web ->
                // UA 模式变化时重新设置并刷新页面
                val baseUA = defaultUA ?: web.settings.userAgentString
                val targetUA =
                    if (currentUaMode == "desktop") DESKTOP_UA else baseUA + MOBILE_UA_SUFFIX
                if (web.settings.userAgentString != targetUA) {
                    web.settings.userAgentString = targetUA
                    web.reload()
                }
                // 外部 url 变化时重新加载
                if (url != loadedUrl) {
                    web.loadUrl(url)
                    loadedUrl = url
                }
            },
            onRelease = { web ->
                // 恢复横屏
                applyOrientation("landscape")
                web.stopLoading()
                web.destroy()
                webViewRef = null
            },
            modifier = Modifier.fillMaxSize()
        )

        // ---- 可拖拽的悬浮菜单按钮 ----
        Box(
            modifier = Modifier
                .offset { IntOffset(floatOffset.x.roundToInt(), floatOffset.y.roundToInt()) }
                .size(FloatingButtonSize)
                .clip(CircleShape)
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
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = "菜单",
                tint = Accent
            )

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(MenuBg)
            ) {
                // ---- UA 模式 ----
                Text(
                    text = "UA：" + if (currentUaMode == "desktop") "桌面模式" else "移动模式",
                    color = Accent,
                    fontSize = 12.sp,
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

                // ---- 屏幕方向 ----
                Text(
                    text = "方向：" + when (orientation) {
                        "portrait" -> "竖屏"
                        "auto" -> "自动旋转"
                        else -> "横屏"
                    },
                    color = Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                DropdownMenuItem(
                    text = { Text("横屏", color = if (orientation == "landscape") Accent else PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        orientation = "landscape"
                        applyOrientation("landscape")
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("竖屏", color = if (orientation == "portrait") Accent else PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        orientation = "portrait"
                        applyOrientation("portrait")
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("自动旋转", color = if (orientation == "auto") Accent else PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        orientation = "auto"
                        applyOrientation("auto")
                        showMenu = false
                    }
                )

                HorizontalDivider(color = DividerColor)

                // ---- 缩放 ----
                DropdownMenuItem(
                    text = {
                        Text(
                            "页面缩放: ${zoomPct.toInt()}%",
                            color = PrimaryText, fontSize = 13.sp
                        )
                    },
                    onClick = {
                        showZoomSlider = true
                        showMenu = false
                    }
                )

                HorizontalDivider(color = DividerColor)

                // ---- Flash 引擎 ----
                Text(
                    text = "Flash引擎: " + if (flashEnabled) flashEngine.displayName else "已关闭",
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
                            Text("Ruffle (AS1/2/3, 内置中文字体)", color = PrimaryText, fontSize = 12.sp)
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
                            Text("WAFlash (AS2/AS3, Canvas渲染)", color = PrimaryText, fontSize = 12.sp)
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
                    text = {
                        Text(
                            "关闭 Flash 引擎",
                            color = if (!flashEnabled) Color(0xFFE74C3C) else SecondaryText,
                            fontSize = 12.sp
                        )
                    },
                    onClick = {
                        flashEnabled = false
                        webViewRef?.reload()
                        showMenu = false
                    }
                )

                HorizontalDivider(color = DividerColor)

                // ---- 导航 ----
                DropdownMenuItem(
                    text = { Text("刷新页面", color = PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        webViewRef?.reload()
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("返回", color = PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        webViewRef?.let { if (it.canGoBack()) it.goBack() }
                        showMenu = false
                    }
                )
                DropdownMenuItem(
                    text = { Text("前进", color = PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        webViewRef?.let { if (it.canGoForward()) it.goForward() }
                        showMenu = false
                    }
                )

                HorizontalDivider(color = DividerColor)

                DropdownMenuItem(
                    text = { Text("退出", color = Color(0xFFE74C3C), fontSize = 13.sp) },
                    onClick = {
                        showMenu = false
                        onExit()
                    }
                )
            }
        }

        // ---- 缩放滑块浮层 ----
        if (showZoomSlider) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .pointerInput(Unit) {
                        detectTapGestures { showZoomSlider = false }
                    },
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
                            // 注入新的 viewport
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
    }
}
}

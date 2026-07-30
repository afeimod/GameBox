package com.nesstation.app.ui.online

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.roundToInt

// ---- Dark palette (matches app theme) ----
private val Accent = Color(0xFF8A7BFF)
private val PrimaryText = Color(0xFFE2E8F0)
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
 * 在线网页游戏浏览界面。
 *
 * - 全屏 [WebView] 加载指定 URL；
 * - 右上角悬浮可拖拽按钮提供菜单（切换 UA / 刷新 / 返回 / 前进 / 退出）；
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
    // 当前 UA 模式（可在菜单内切换，初始值取自入参）
    var currentUaMode by remember(uaMode) {
        mutableStateOf(if (uaMode == "mobile") "mobile" else "desktop")
    }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // WebView 默认 UA（仅抓取一次，用于「移动模式」追加 NesStation 后缀）
    var defaultUA by remember { mutableStateOf<String?>(null) }
    // 已加载的 URL，用于在外部 url 变化时重新加载
    var loadedUrl by remember { mutableStateOf(url) }
    var showMenu by remember { mutableStateOf(false) }

    // 系统返回：先关菜单 → 再 WebView 后退 → 最后退出
    BackHandler {
        val web = webViewRef
        when {
            showMenu -> showMenu = false
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

        // 悬浮按钮初始位置：右上角
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

                    // 允许 Cookie（含第三方），便于游戏站点保持登录态
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    // 链接在当前 WebView 内打开
                    webViewClient = WebViewClient()

                    // 抓取默认 UA 并应用初始 UA 模式
                    val baseUA = settings.userAgentString
                    defaultUA = baseUA
                    settings.userAgentString =
                        if (currentUaMode == "desktop") DESKTOP_UA else baseUA + MOBILE_UA_SUFFIX

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
                // 拖拽移动（detectDragGestures 内部以 requireUnconsumed=false 取得 down 事件，
                // 因此可与下方的 detectTapGestures 共存：移动超 touch slop 即拖动，否则视为点击）
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
                // 点击（非拖动）弹出菜单
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
                // 当前 UA 模式徽标
                Text(
                    text = "UA：" + if (currentUaMode == "desktop") "桌面模式" else "移动模式",
                    color = Accent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                HorizontalDivider(color = DividerColor)

                DropdownMenuItem(
                    text = { Text("切换UA", color = PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        currentUaMode = if (currentUaMode == "desktop") "mobile" else "desktop"
                        showMenu = false
                    }
                )
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
                    text = { Text("退出", color = PrimaryText, fontSize = 13.sp) },
                    onClick = {
                        showMenu = false
                        onExit()
                    }
                )
            }
        }
    }
}

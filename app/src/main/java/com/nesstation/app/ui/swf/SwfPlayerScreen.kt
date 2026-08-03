package com.nesstation.app.ui.swf

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// 注:不同 Compose 版本下 Float.toDp() 可能在不同包。这里用 density.density 手动换算 px → dp,
// 避免 unresolved reference。
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.nesstation.app.flash.data.PrefsManager
import com.nesstation.app.flash.input.ActionButtonView
import com.nesstation.app.flash.input.DPadView
import com.nesstation.app.flash.input.KeyMapper
import com.nesstation.app.flash.input.MouseControlView
import com.nesstation.app.flash.webview.GameWebChromeClient
import com.nesstation.app.flash.webview.GameWebView
import com.nesstation.app.flash.webview.GameWebViewClient
import com.nesstation.app.flash.webview.NavHelper
import com.nesstation.app.flash.webview.WebAppInterface
import com.nesstation.app.flash.widget.FloatingMenuView

/**
 * SWF 播放器 Compose 入口。
 * 1:1 移植自 3.3-fix2 GameActivity（仅 SWF 模式）。所有 webview/input/widget 都用
 * com.nesstation.app.flash 包下的 3.3 类。
 *
 * @param swfPath  本地 SWF 文件 URI（content:// / file://）
 * @param onExit   退出回调
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SwfPlayerScreen(
    swfPath: String,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    remember { PrefsManager.init(context) }
    val activity = context as? Activity

    var gamepadVisible by remember { mutableStateOf(PrefsManager.isGamepadEnabled) }
    var isMouseEnabled by remember { mutableStateOf(PrefsManager.isMouseEnabled) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(true) }
    val localSwfUri = remember { mutableStateOf(swfPath) }
    val showFlashDialog = remember { mutableStateOf(false) }
    val showKeyDialog = remember { mutableStateOf(false) }

    // WebView 自定义全屏视图（Ruffle 等元素 requestFullscreen 触发）：
    // 真实把 view 加到 Activity DecorView 上,避免 WebView 内部状态不一致导致卡死。
    val customViewRef = remember { mutableStateOf<View?>(null) }
    val customViewCallbackRef = remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val showZoomDialog = remember { mutableStateOf(false) }
    val showUaDialog = remember { mutableStateOf(false) }
    val showAspectRatioDialog = remember { mutableStateOf(false) }

    // 自定义四角布局状态:实时拖动 overlay 用
    val isCustomLayoutEditMode = remember { mutableStateOf(false) }
    val currentBoxLeft = remember { mutableFloatStateOf(PrefsManager.customLayoutLeft) }
    val currentBoxTop = remember { mutableFloatStateOf(PrefsManager.customLayoutTop) }
    val currentBoxRight = remember { mutableFloatStateOf(PrefsManager.customLayoutRight) }
    val currentBoxBottom = remember { mutableFloatStateOf(PrefsManager.customLayoutBottom) }
    val containerWidthPx = remember { mutableStateOf(0) }
    val containerHeightPx = remember { mutableStateOf(0) }
    // 本地 SWF 打开时先弹出引擎选择（仅弹一次）
    val enginePickerShown = remember { mutableStateOf(false) }
    // 位置编辑模式（拖动调整方向键/动作键/鼠标按钮位置）
    var isPositionEditMode by remember { mutableStateOf(false) }

    val webViewRef = remember { mutableStateOf<GameWebView?>(null) }
    val dpadRef = remember { mutableStateOf<DPadView?>(null) }
    val actionRef = remember { mutableStateOf<ActionButtonView?>(null) }
    val mouseRef = remember { mutableStateOf<MouseControlView?>(null) }
    val floatingMenuRef = remember { mutableStateOf<FloatingMenuView?>(null) }
    val webAppInterfaceRef = remember { mutableStateOf<WebAppInterface?>(null) }
    val mainBoxRef = remember { mutableStateOf<ViewGroup?>(null) }

    // 虚拟手柄重建触发器：添加/删除/修改按键后递增，触发 LaunchedEffect 重建手柄
    val gamepadRebuildTrigger = remember { mutableStateOf(0) }

    fun rebuildGamepad() {
        gamepadRebuildTrigger.value = gamepadRebuildTrigger.value + 1
    }

    BackHandler {
        if (webViewRef.value?.canGoBack() == true) webViewRef.value?.goBack() else onExit()
    }

    fun applyOrientation(landscape: Boolean) {
        activity?.requestedOrientation = if (landscape)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        activity?.window?.let { w ->
            if (isFullscreen) {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).hide(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                )
            } else {
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, true)
                androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).show(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                )
            }
        }
        floatingMenuRef.value?.isFullscreen = isFullscreen
    }

    fun toggleOrientation() {
        isLandscape = !isLandscape
        applyOrientation(isLandscape)
        floatingMenuRef.value?.isLandscape = isLandscape
    }

    fun toggleGamepad() {
        gamepadVisible = !gamepadVisible
    }

    fun toggleMouse() {
        isMouseEnabled = !isMouseEnabled
        PrefsManager.sp.edit()
            .putBoolean("mouse_enabled", isMouseEnabled)
            .putBoolean("mouse_buttons_visible", isMouseEnabled)
            .apply()
        val wv = webViewRef.value
        if (wv != null) {
            if (isMouseEnabled) {
                wv.evaluateJavascript(GameWebViewClient.MOUSE_CURSOR_SCRIPT, null)
                Toast.makeText(context, "鼠标光标已开启", Toast.LENGTH_SHORT).show()
            } else {
                wv.evaluateJavascript(
                    "(function(){var c=document.getElementById('__mouseCursor');if(c)c.remove();window.__mouseEnabled=false;})();", null
                )
                Toast.makeText(context, "鼠标光标已关闭", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun toggleCameraRotation() {
        val enabled = !PrefsManager.isCameraRotationEnabled
        PrefsManager.sp.edit().putBoolean("camera_rotation_enabled", enabled).apply()
        val wv = webViewRef.value
        if (wv != null) {
            wv.cameraRotationEnabled = enabled
            if (enabled) {
                wv.evaluateJavascript(GameWebViewClient.CAMERA_ROTATION_SCRIPT, null)
                Toast.makeText(context, "视角旋转已开启，拖动屏幕旋转视角", Toast.LENGTH_LONG).show()
            } else {
                wv.evaluateJavascript(
                    "(function(){window.__cameraRotation=false;var s=document.getElementById('__cameraRotateStyle');if(s)s.remove();})();", null
                )
                Toast.makeText(context, "视角旋转已关闭", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 应用自定义四角布局:把 WebView 父容器按 left/top/right/bottom 归一化值定位。
     * 0/0/1/1 = 全屏(默认值),用户拖动可缩小到任意矩形。
     */
    fun applyCustomLayout(enabled: Boolean, left: Float, top: Float, right: Float, bottom: Float) {
        PrefsManager.setCustomLayout(enabled, left, top, right, bottom)
        val container = mainBoxRef.value as? android.widget.FrameLayout ?: return
        val wv = webViewRef.value ?: return
        val parentW = container.width
        val parentH = container.height
        if (parentW <= 0 || parentH <= 0) return
        val l = (left.coerceIn(0f, 1f) * parentW).toInt()
        val t = (top.coerceIn(0f, 1f) * parentH).toInt()
        val r = (right.coerceIn(0f, 1f) * parentW).toInt()
        val b = (bottom.coerceIn(0f, 1f) * parentH).toInt()
        val lp = android.widget.FrameLayout.LayoutParams(
            (r - l).coerceAtLeast(1),
            (b - t).coerceAtLeast(1)
        )
        lp.leftMargin = l
        lp.topMargin = t
        wv.layoutParams = lp
        wv.requestLayout()
    }

    fun applyAspectRatio(ratio: String) {
        PrefsManager.sp.edit().putString("game_aspect_ratio", ratio).apply()
        val wv = webViewRef.value
        if (wv != null) {
            // 按当前引擎选择正确的 letterbox 脚本。
            // - Ruffle: 用 buildRuffleAspectRatioScript（让 ruffle-player 内部 letterbox 生效，
            //           只控制 #stage 外框大小+居中,不破坏引擎内部 stage 尺寸）。
            // - WAFlash: 用 buildAspectRatioScript（WAFlash 引擎自身不做 letterbox,必须外层 CSS 强制）。
            val script = if (PrefsManager.flashEngine == "waflash") {
                GameWebViewClient.buildAspectRatioScript(ratio)
            } else {
                GameWebViewClient.buildRuffleAspectRatioScript(ratio)
            }
            wv.evaluateJavascript(script, null)
        }
    }

    fun applyZoom(mode: String, manual: Int) {
        PrefsManager.sp.edit()
            .putString("page_zoom_mode", mode)
            .putInt("page_zoom_manual", manual)
            .apply()
        webViewRef.value?.reload()
    }

    fun applyUa(mode: String) {
        PrefsManager.sp.edit().putString("ua_mode", mode).apply()
        val wv = webViewRef.value
        if (wv != null) {
            if (mode == "desktop") {
                wv.useDesktopMode(true)
            } else {
                wv.useUaMode(mode)
            }
        }
        webViewRef.value?.reload()
    }

    fun reload() {
        val wv = webViewRef.value ?: return
        val swfProxy = "https://flash.local/local.swf?t=${System.currentTimeMillis()}"
        val playerUrl = NavHelper.playerUrl(swfProxy, base = null, title = null)
        wv.releaseAllKeys()
        wv.loadUrl(playerUrl)
    }

    fun applyEngineAndReload(engine: String) {
        PrefsManager.sp.edit().putString("flash_engine", engine).putBoolean("flash_enabled", true).apply()
        reload()
    }

    fun extractSwfFromPage() {
        Toast.makeText(context, "正在扫描页面中的 SWF...", Toast.LENGTH_SHORT).show()
        val iface = webAppInterfaceRef.value
        if (iface != null) {
            iface.swfExtractCallback = { json ->
                if (json.isNullOrEmpty()) {
                    Toast.makeText(context, "未发现 SWF", Toast.LENGTH_SHORT).show()
                } else {
                    // 解析 JSON 列出 SWF + 资源
                    showExtractResultDialog(context, json)
                }
            }
        }
        webViewRef.value?.evaluateJavascript(SWF_SNIFFER_SCRIPT, null)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayoutSwfContainer(ctx).apply {
                    mainBoxRef.value = this
                }
            }
        )
    }

    LaunchedEffect(mainBoxRef.value) {
        val container = mainBoxRef.value ?: return@LaunchedEffect
        if (webViewRef.value == null) {
            val wv = GameWebView(container.context)
            val webAppInterface = WebAppInterface(container.context)
            webAppInterfaceRef.value = webAppInterface

            webAppInterface.openSwfCallback = { swfUrl, _ ->
                val wv2 = webViewRef.value
                if (wv2 != null) {
                    val playerUrl = NavHelper.playerUrl(swfUrl, base = wv2.url, title = null)
                    wv2.loadUrl(playerUrl)
                }
            }
            webAppInterface.fullscreenCallback = { toggleFullscreen() }

            val chromeCallback = object : GameWebChromeClient.Callback {
                override fun onProgress(progress: Int) {}
                override fun onTitle(title: String?) {}
                override fun onConsole(level: String, msg: String, sourceId: String?, line: Int) {}

                // 实现 WebView 自定义全屏协议（按 Android 标准）：
                // 把 WebView 内部生成的全屏 view 加到 Activity 的 DecorView 上,
                // 实现 WebView 自定义全屏协议(Android 标准):
                // 把 WebView 内部生成的全屏 view 加到 DecorView 上层,让 WebView 内部
                // 状态正常推进,避免 Ruffle 等元素 requestFullscreen 触发 onShowCustomView 后
                // WebView 内部状态不一致而卡死。
                //
                // 关键:不要在这里 hide/show Android systemBars(状态栏/虚拟按键)。
                // 那些是 Activity 级,跟 WebView 全屏是独立的两套——
                //   - WebView 全屏:view 加到 DecorView,占满 viewport
                //   - 手机全屏(浮动菜单的全屏按钮):用 WindowInsetsControllerCompat
                // 之前实现错把 WebView 全屏跟手机全屏耦合,导致:
                //   1. WebView 全屏时 systemBars 被 hide,虚拟按键被覆盖
                //   2. 退出 WebView 全屏时调 show(systemBars),等于退出手机全屏
                override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
                    customViewRef.value = view
                    customViewCallbackRef.value = callback
                    // 把 view 加到 WebView 父容器(mainBoxRef),覆盖在 WebView 上层。
                    // 之前加到 DecorView 会盖住 floatingMenu + navigation bar(虚拟按键),
                    // 加到 WebView 父容器只覆盖 WebView 区域,虚拟按键仍可见可按。
                    val parent = mainBoxRef.value as? ViewGroup
                    if (parent != null) {
                        parent.addView(view, FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        ))
                    }
                    isFullscreen = true
                    floatingMenuRef.value?.isFullscreen = true
                }

                override fun onHideFullscreen() {
                    val view = customViewRef.value
                    val cb = customViewCallbackRef.value
                    if (view != null) {
                        val parent = view.parent as? ViewGroup
                        parent?.removeView(view)
                    }
                    cb?.onCustomViewHidden()
                    customViewRef.value = null
                    customViewCallbackRef.value = null
                    // 不调用 setDecorFitsSystemWindows/show(systemBars)。
                    // 那些只用于 Android 端浮动菜单的全屏按钮(Web 端全屏不影响 systemBars)。
                    isFullscreen = false
                    floatingMenuRef.value?.isFullscreen = false
                }

                override fun onFileChooser(callback: ValueCallback<Array<android.net.Uri>>, accept: String?): Boolean { return true }
            }

            val viewClientCallback = object : GameWebViewClient.Callback {
                override fun onPageStarted(url: String?) { webViewRef.value?.releaseAllKeys() }
                override fun onPageFinished(url: String?) {}
                override fun onProgress(progress: Int) {}
                override fun onError(url: String?, errorCode: Int, description: String?) {}
                override fun onSwfIntercepted(swfUrl: String, pageUrl: String) {
                    val wv2 = webViewRef.value
                    if (wv2 != null) {
                        val playerUrl = NavHelper.playerUrl(swfUrl, base = pageUrl, title = null)
                        wv2.loadUrl(playerUrl)
                    }
                }
                override fun shouldInjectRuffle(url: String?): Boolean = false
                override fun getCachedSwfPath(): String? = null
                override fun getLocalSwfUri(): String? = localSwfUri.value
                override fun getLocalSwfDir(): String? {
                    val uri = localSwfUri.value ?: return null
                    return try {
                        when {
                            uri.startsWith("content://") -> {
                                // For content:// URIs, return the parent document URI
                                val parsed = android.net.Uri.parse(uri)
                                // Try to get parent by removing the last path segment
                                val parentUri = parsed.toString().substringBeforeLast("%2F")
                                    .substringBeforeLast("/")
                                if (parentUri != uri) parentUri else null
                            }
                            uri.startsWith("file://") -> {
                                val path = android.net.Uri.parse(uri).path ?: return null
                                val file = java.io.File(path)
                                file.parentFile?.absolutePath
                            }
                            else -> {
                                val file = java.io.File(uri)
                                if (file.parentFile != null) file.parentFile?.absolutePath else null
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("SwfPlayerScreen", "获取SWF目录失败: ${e.message}")
                        null
                    }
                }
            }

            wv.apply {
                addJavascriptInterface(webAppInterface, "Android")
                webChromeClient = object : GameWebChromeClient(chromeCallback) {}
                webViewClient = GameWebViewClient(viewClientCallback)
                useDesktopMode(true)
                cameraRotationEnabled = PrefsManager.isCameraRotationEnabled
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) onExit()
                        true
                    } else if (keyCode in GameWebView.GAME_KEYS) {
                        dispatchKeyEvent(event)
                        true
                    } else false
                }
            }
            wv.injectDocumentStartScripts()

            container.addView(wv, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            webViewRef.value = wv
        }
    }

    LaunchedEffect(mainBoxRef.value, gamepadVisible, isMouseEnabled, gamepadRebuildTrigger.value) {
        val container = mainBoxRef.value ?: return@LaunchedEffect
        val density = container.resources.displayMetrics.density

        listOfNotNull(
            dpadRef.value, actionRef.value, mouseRef.value, floatingMenuRef.value
        ).forEach { container.removeView(it) }

        val dpad = DPadView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
        }
        dpadRef.value = dpad
        val dpadSize = (140 * density).toInt()
        val dpadLp = android.widget.FrameLayout.LayoutParams(dpadSize, dpadSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            bottomMargin = (24 * density).toInt()
            marginStart = (16 * density).toInt()
        }
        if (gamepadVisible) container.addView(dpad, dpadLp)

        val action = ActionButtonView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
        }
        actionRef.value = action
        val baseSize = (160 * density).toInt()
        val count = PrefsManager.gamepadKeyCount
        val sizeMult = if (count > 6) 1f + (count - 6) * 0.12f else 1f
        val actionSize = (baseSize * PrefsManager.gamepadScale * sizeMult).toInt()
        val actionLp = android.widget.FrameLayout.LayoutParams(actionSize, actionSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
            bottomMargin = (24 * density).toInt()
            marginEnd = (16 * density).toInt()
        }
        if (gamepadVisible) container.addView(action, actionLp)

        val mouse = MouseControlView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
        }
        mouseRef.value = mouse
        val mouseSize = (220 * density).toInt()
        val mouseLp = android.widget.FrameLayout.LayoutParams(mouseSize, (80 * density).toInt()).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (200 * density).toInt()
        }
        if (isMouseEnabled) container.addView(mouse, mouseLp)

        val menu = FloatingMenuView(container.context)
        menu.setCallbacks(object : FloatingMenuView.Callbacks {
            override fun onToggleFullscreen() = toggleFullscreen()
            override fun onToggleOrientation() = toggleOrientation()
            override fun onToggleGamepad() = toggleGamepad()
            override fun onToggleMouse() = toggleMouse()
            override fun onOpenKeyMapping() { showKeyDialog.value = true }
            override fun onOpenFlashSettings() { showFlashDialog.value = true }
            override fun onOpenPageZoom() { showZoomDialog.value = true }
            override fun onOpenUaMode() { showUaDialog.value = true }
            override fun onRefresh() = reload()
            override fun onBack() {
                if (webViewRef.value?.canGoBack() == true) webViewRef.value?.goBack() else onExit()
            }
            override fun onClose() = onExit()
            override fun onExtractSwf() = extractSwfFromPage()
            override fun onToggleCameraRotation() = toggleCameraRotation()
            override fun onOpenAspectRatio() { showAspectRatioDialog.value = true }
        })
        menu.isFullscreen = isFullscreen
        menu.isLandscape = isLandscape
        floatingMenuRef.value = menu
        menu.attachTo(container)
    }

    LaunchedEffect(gamepadVisible) {
        if (!gamepadVisible) webViewRef.value?.releaseAllKeys()
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
    }

    // ========== 本地 SWF 引擎选择弹窗（仅弹一次） ==========
    LaunchedEffect(Unit) {
        if (!enginePickerShown.value) {
            enginePickerShown.value = true
            android.app.AlertDialog.Builder(context)
                .setTitle("选择 Flash 引擎")
                .setMessage("本地 SWF 文件需要选择播放引擎")
                .setPositiveButton("Ruffle (推荐)") { dlg, _ ->
                    applyEngineAndReload("ruffle")
                    dlg.dismiss()
                }
                .setNegativeButton("WAFlash") { dlg, _ ->
                    applyEngineAndReload("waflash")
                    dlg.dismiss()
                }
                .setOnCancelListener { onExit() }
                .show()
        }
    }

    // ========== Flash 引擎切换弹窗 ==========
    if (showFlashDialog.value) {
        val engines = arrayOf("Ruffle", "WAFlash", "关闭 Flash")
        val values = arrayOf("ruffle", "waflash", "off")
        val current = if (PrefsManager.isFlashEnabled) PrefsManager.flashEngine else "off"
        val checked = values.indexOf(current).coerceAtLeast(0)
        android.app.AlertDialog.Builder(context)
            .setTitle("Flash 引擎")
            .setSingleChoiceItems(engines, checked) { dlg, which ->
                if (values[which] == "off") {
                    PrefsManager.sp.edit().putBoolean("flash_enabled", false).apply()
                } else {
                    applyEngineAndReload(values[which])
                }
                dlg.dismiss()
                showFlashDialog.value = false
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { showFlashDialog.value = false }
            .show()
    }

    // ========== 按键映射弹窗（增强版） ==========
    if (showKeyDialog.value) {
        val allKeys = arrayOf(
            "J", "K", "L", "U", "I", "O",
            "A", "B", "C", "D", "E", "F", "G", "H", "M", "N",
            "P", "Q", "R", "S", "T", "V", "W", "X", "Y", "Z",
            "SPACE", "ENTER", "TAB", "ESC",
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9"
        )
        val items = arrayOf(
            "按键映射 (选择按键修改)",
            "添加按键",
            "删除按键",
            "Start/Select 映射",
            "方向键模式 (DPAD/WASD/摇杆)",
            "方向键大小",
            "动作按键大小",
            "显示/隐藏按键",
            "添加/隐藏鼠标按钮",
            "位置编辑模式 (拖动调整)",
            "视角旋转 (3D游戏)",
            "恢复默认"
        )
        android.app.AlertDialog.Builder(context)
            .setTitle("按键映射 (共 ${PrefsManager.gamepadKeyCount} 个)")
            .setItems(items) { _, which ->
                when (which) {
                    // 0: 按键映射 - 选择某个按键修改其映射
                    0 -> {
                        val keys = PrefsManager.gamepadKeys
                        val labels = keys.mapIndexed { i, k -> "按键 ${i + 1} ($k)" }.toTypedArray()
                        android.app.AlertDialog.Builder(context)
                            .setTitle("选择要修改的按键")
                            .setItems(labels) { _, idx ->
                                val cur = PrefsManager.gamepadKeys.getOrElse(idx) { "J" }
                                android.app.AlertDialog.Builder(context)
                                    .setTitle("按键 ${idx + 1} 映射")
                                    .setSingleChoiceItems(allKeys, allKeys.indexOf(cur).coerceAtLeast(0)) { dlg, w ->
                                        PrefsManager.sp.edit().putString("gamepad_key_${idx + 1}", allKeys[w]).apply()
                                        dlg.dismiss()
                                        rebuildGamepad()
                                        Toast.makeText(context, "按键 ${idx + 1} 已设为 ${allKeys[w]}", Toast.LENGTH_SHORT).show()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 1: 添加按键（最多 18 个）
                    1 -> {
                        val c = PrefsManager.gamepadKeyCount
                        if (c < 18) {
                            PrefsManager.sp.edit().putInt("gamepad_key_count", c + 1).apply()
                            rebuildGamepad()
                            Toast.makeText(context, "已添加按键，当前 ${c + 1} 个", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "已达最大按键数 18", Toast.LENGTH_SHORT).show()
                        }
                    }
                    // 2: 删除按键（最少 2 个）
                    2 -> {
                        val c = PrefsManager.gamepadKeyCount
                        if (c > 2) {
                            PrefsManager.sp.edit().putInt("gamepad_key_count", c - 1).apply()
                            rebuildGamepad()
                            Toast.makeText(context, "已删除按键，当前 ${c - 1} 个", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "已达最小按键数 2", Toast.LENGTH_SHORT).show()
                        }
                    }
                    // 3: Start/Select 映射
                    3 -> {
                        val sysItems = arrayOf(
                            "Start 键 (${PrefsManager.startKey})",
                            "Select 键 (${PrefsManager.selectKey})"
                        )
                        android.app.AlertDialog.Builder(context)
                            .setTitle("Start/Select 映射")
                            .setItems(sysItems) { _, idx ->
                                val title = if (idx == 0) "Start 键映射" else "Select 键映射"
                                val current = if (idx == 0) PrefsManager.startKey else PrefsManager.selectKey
                                android.app.AlertDialog.Builder(context)
                                    .setTitle(title)
                                    .setSingleChoiceItems(allKeys, allKeys.indexOf(current).coerceAtLeast(0)) { dlg, w ->
                                        val prefKey = if (idx == 0) "start_key" else "select_key"
                                        PrefsManager.sp.edit().putString(prefKey, allKeys[w]).apply()
                                        dlg.dismiss()
                                        rebuildGamepad()
                                        Toast.makeText(context, "$title 已设为 ${allKeys[w]}", Toast.LENGTH_SHORT).show()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 4: 方向键模式（摇杆/十字键/WASD）
                    4 -> {
                        val modes = arrayOf("摇杆 (joystick)", "十字键 (dpad)", "WASD (wasd)")
                        val values = arrayOf("joystick", "dpad", "wasd")
                        val checked = values.indexOf(PrefsManager.dpadMode).coerceAtLeast(0)
                        android.app.AlertDialog.Builder(context)
                            .setTitle("方向键模式")
                            .setSingleChoiceItems(modes, checked) { dlg, w ->
                                PrefsManager.sp.edit().putString("dpad_mode", values[w]).apply()
                                dlg.dismiss()
                                rebuildGamepad()
                                Toast.makeText(context, "方向键模式已设为 ${modes[w]}", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 5: 方向键大小
                    5 -> {
                        val sizes = arrayOf("小 (80%)", "中 (100%)", "大 (120%)", "超大 (150%)")
                        val values = intArrayOf(80, 100, 120, 150)
                        val current = PrefsManager.sp.getInt("dpad_scale", 100)
                        val checked = values.indexOf(current).coerceAtLeast(0)
                        android.app.AlertDialog.Builder(context)
                            .setTitle("方向键大小")
                            .setSingleChoiceItems(sizes, checked) { dlg, w ->
                                PrefsManager.sp.edit().putInt("dpad_scale", values[w]).apply()
                                dlg.dismiss()
                                rebuildGamepad()
                                Toast.makeText(context, "方向键大小已设为 ${sizes[w]}", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 6: 动作按键大小
                    6 -> {
                        val sizes = arrayOf("小 (80%)", "中 (100%)", "大 (120%)", "超大 (150%)")
                        val values = intArrayOf(80, 100, 120, 150)
                        val current = PrefsManager.sp.getInt("gamepad_scale", 100)
                        val checked = values.indexOf(current).coerceAtLeast(0)
                        android.app.AlertDialog.Builder(context)
                            .setTitle("动作按键大小")
                            .setSingleChoiceItems(sizes, checked) { dlg, w ->
                                PrefsManager.sp.edit().putInt("gamepad_scale", values[w]).apply()
                                dlg.dismiss()
                                rebuildGamepad()
                                Toast.makeText(context, "动作按键大小已设为 ${sizes[w]}", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 7: 显示/隐藏按键（多选）
                    7 -> {
                        val keys = PrefsManager.gamepadKeys
                        val keyVisible = PrefsManager.gamepadKeyVisible
                        val labels = ArrayList<String>()
                        val prefKeys = ArrayList<String>()
                        val checkedArr = ArrayList<Boolean>()
                        keys.forEachIndexed { i, k ->
                            labels.add("按键 ${i + 1} ($k)")
                            prefKeys.add("gamepad_key_${i + 1}_visible")
                            checkedArr.add(keyVisible.getOrElse(i) { true })
                        }
                        labels.add("方向键")
                        prefKeys.add("dpad_visible")
                        checkedArr.add(PrefsManager.isDpadVisible)
                        labels.add("Start/Select")
                        prefKeys.add("system_buttons_visible")
                        checkedArr.add(PrefsManager.isSystemButtonsVisible)
                        val checkedBooleans = checkedArr.toBooleanArray()
                        android.app.AlertDialog.Builder(context)
                            .setTitle("显示/隐藏按键")
                            .setMultiChoiceItems(labels.toTypedArray(), checkedBooleans) { _, whichItem, isChecked ->
                                checkedBooleans[whichItem] = isChecked
                            }
                            .setPositiveButton("确定") { dlg, _ ->
                                val editor = PrefsManager.sp.edit()
                                prefKeys.forEachIndexed { i, pk -> editor.putBoolean(pk, checkedBooleans[i]) }
                                editor.apply()
                                dlg.dismiss()
                                rebuildGamepad()
                                Toast.makeText(context, "按键显示设置已保存", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    // 8: 添加/隐藏鼠标按钮
                    8 -> {
                        val visible = !PrefsManager.isMouseButtonsVisible
                        PrefsManager.sp.edit()
                            .putBoolean("mouse_buttons_visible", visible)
                            .putBoolean("mouse_enabled", visible)
                            .apply()
                        isMouseEnabled = visible
                        val wv = webViewRef.value
                        if (wv != null) {
                            if (visible) {
                                wv.evaluateJavascript(GameWebViewClient.MOUSE_CURSOR_SCRIPT, null)
                            } else {
                                wv.evaluateJavascript(
                                    "(function(){var c=document.getElementById('__mouseCursor');if(c)c.remove();window.__mouseEnabled=false;})();",
                                    null
                                )
                            }
                        }
                        Toast.makeText(context, if (visible) "鼠标按钮已显示" else "鼠标按钮已隐藏", Toast.LENGTH_SHORT).show()
                    }
                    // 9: 位置编辑模式（拖动调整）
                    9 -> {
                        isPositionEditMode = !isPositionEditMode
                        dpadRef.value?.isDragMode = isPositionEditMode
                        actionRef.value?.isDragMode = isPositionEditMode
                        mouseRef.value?.isDragMode = isPositionEditMode
                        Toast.makeText(
                            context,
                            if (isPositionEditMode) "位置编辑模式已开启" else "位置编辑模式已关闭",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    // 10: 视角旋转 (3D游戏)
                    10 -> {
                        toggleCameraRotation()
                    }
                    // 11: 恢复默认
                    11 -> {
                        PrefsManager.sp.edit()
                            .putInt("gamepad_key_count", 6)
                            .putString("gamepad_key_1", "J")
                            .putString("gamepad_key_2", "K")
                            .putString("gamepad_key_3", "L")
                            .putString("gamepad_key_4", "U")
                            .putString("gamepad_key_5", "I")
                            .putString("gamepad_key_6", "O")
                            .putString("select_key", "TAB")
                            .putString("start_key", "ENTER")
                            .putString("dpad_mode", "joystick")
                            .putInt("dpad_scale", 100)
                            .putInt("gamepad_scale", 100)
                            .putFloat("dpad_pos_x", -1f)
                            .putFloat("dpad_pos_y", -1f)
                            .putFloat("action_pos_x", -1f)
                            .putFloat("action_pos_y", -1f)
                            .putFloat("mouse_pos_x", -1f)
                            .putFloat("mouse_pos_y", -1f)
                            .putFloat("system_pos_x", -1f)
                            .putFloat("system_pos_y", -1f)
                            .apply()
                        rebuildGamepad()
                        Toast.makeText(context, "已恢复默认按键设置", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { showKeyDialog.value = false }
            .show()
    }

    // ========== 页面缩放弹窗 ==========
    if (showZoomDialog.value) {
        val mode = PrefsManager.pageZoomMode
        val manual = PrefsManager.pageZoomManual

        val container = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val currentText = android.widget.TextView(context).apply {
            text = if (mode == "auto") "模式：自动" else "缩放：$manual%"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        container.addView(currentText)
        val seekBar = android.widget.SeekBar(context).apply {
            max = 175
            progress = (manual - 25).coerceIn(0, 175)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    currentText.text = "缩放：${p + 25}%"
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        seekBar.layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        container.addView(seekBar)
        val hint = android.widget.TextView(context).apply {
            text = "范围：25% — 200%"
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.GRAY)
        }
        container.addView(hint)

        android.app.AlertDialog.Builder(context)
            .setTitle("页面缩放")
            .setView(container)
            .setPositiveButton("应用") { _, _ -> applyZoom("manual", seekBar.progress + 25); showZoomDialog.value = false }
            .setNegativeButton("自动") { _, _ -> applyZoom("auto", manual); showZoomDialog.value = false }
            .setNeutralButton("取消") { _, _ -> showZoomDialog.value = false }
            .setOnDismissListener { showZoomDialog.value = false }
            .show()
    }

    // ========== 兼容模式（UA）弹窗 ==========
    if (showUaDialog.value) {
        val modes = arrayOf("桌面模式 (Chrome)", "兼容模式 (IE11)", "移动模式")
        val values = arrayOf("desktop", "ie_compat", "mobile")
        val current = PrefsManager.uaMode
        val checked = values.indexOf(current).coerceAtLeast(0)
        android.app.AlertDialog.Builder(context)
            .setTitle("浏览器兼容模式")
            .setSingleChoiceItems(modes, checked) { dlg, which ->
                applyUa(values[which])
                dlg.dismiss()
                showUaDialog.value = false
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { showUaDialog.value = false }
            .show()
    }

    // ========== 画面比例弹窗(包含自定义布局入口) ==========
    if (showAspectRatioDialog.value) {
        val ratios = arrayOf("全屏自适应 (auto)", "4:3", "16:9", "16:10", "5:4", "自定义(拖动四角)")
        val values = arrayOf("auto", "4:3", "16:9", "16:10", "5:4", "__custom__")
        val current = PrefsManager.gameAspectRatio
        // 如果当前是自定义布局,把"自定义"标为选中
        val checked = if (PrefsManager.isCustomLayoutEnabled) 5
                       else values.indexOf(current).coerceAtLeast(0)
        android.app.AlertDialog.Builder(context)
            .setTitle("画面比例")
            .setSingleChoiceItems(ratios, checked) { dlg, which ->
                if (values[which] == "__custom__") {
                    // 进入自定义布局编辑模式(在游戏画面上拖 4 角)
                    currentBoxLeft.floatValue = PrefsManager.customLayoutLeft
                    currentBoxTop.floatValue = PrefsManager.customLayoutTop
                    currentBoxRight.floatValue = PrefsManager.customLayoutRight
                    currentBoxBottom.floatValue = PrefsManager.customLayoutBottom
                    val parent = mainBoxRef.value
                    if (parent != null) {
                        containerWidthPx.value = parent.width
                        containerHeightPx.value = parent.height
                    }
                    (mainBoxRef.value as? FrameLayoutSwfContainer)?.interceptAllTouch = true
                    isCustomLayoutEditMode.value = true
                } else {
                    // 标准比例:关掉自定义
                    (mainBoxRef.value as? FrameLayoutSwfContainer)?.interceptAllTouch = false
                    isCustomLayoutEditMode.value = false
                    PrefsManager.setCustomLayout(false, 0f, 0f, 1f, 1f)
                    val w = webViewRef.value
                    if (w != null) {
                        val parent = mainBoxRef.value as? ViewGroup
                        if (parent != null) {
                            val lp = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            w.layoutParams = lp
                            w.requestLayout()
                        }
                    }
                    applyAspectRatio(values[which])
                }
                dlg.dismiss()
                showAspectRatioDialog.value = false
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { showAspectRatioDialog.value = false }
            .show()
    }

    // ========== 自定义四角布局 Overlay ==========
    // 直接在游戏画面 4 个角显示拖动手柄(进入编辑模式时),实时改 WebView LayoutParams。
    // 不需要 dialog / 预览框,边拖边看效果。
    if (isCustomLayoutEditMode.value) {
        CustomLayoutOverlay(
            initialLeft = currentBoxLeft.value,
            initialTop = currentBoxTop.value,
            initialRight = currentBoxRight.value,
            initialBottom = currentBoxBottom.value,
            parentWidth = containerWidthPx.value,
            parentHeight = containerHeightPx.value,
            onChange = { l, t, r, b ->
                currentBoxLeft.value = l
                currentBoxTop.value = t
                currentBoxRight.value = r
                currentBoxBottom.value = b
                // 实时同步到 WebView LayoutParams
                val w = webViewRef.value ?: return@CustomLayoutOverlay
                val parent = mainBoxRef.value as? ViewGroup ?: return@CustomLayoutOverlay
                val pw = parent.width
                val ph = parent.height
                if (pw <= 0 || ph <= 0) return@CustomLayoutOverlay
                val lp = FrameLayout.LayoutParams(
                    ((r - l) * pw).toInt().coerceAtLeast(1),
                    ((b - t) * ph).toInt().coerceAtLeast(1)
                )
                lp.leftMargin = (l * pw).toInt()
                lp.topMargin = (t * ph).toInt()
                w.layoutParams = lp
                w.requestLayout()
                // 同时存到 PrefsManager(实时保存)
                PrefsManager.setCustomLayout(true, l, t, r, b)
            },
            onClose = {
                // 退出编辑模式:把最终值保存
                PrefsManager.setCustomLayout(
                    true,
                    currentBoxLeft.value,
                    currentBoxTop.value,
                    currentBoxRight.value,
                    currentBoxBottom.value
                )
                // 恢复 WebView 接收触摸
                (mainBoxRef.value as? FrameLayoutSwfContainer)?.interceptAllTouch = false
                isCustomLayoutEditMode.value = false
            },
            onReset = {
                // 重置全屏
                currentBoxLeft.value = 0f
                currentBoxTop.value = 0f
                currentBoxRight.value = 1f
                currentBoxBottom.value = 1f
                PrefsManager.setCustomLayout(false, 0f, 0f, 1f, 1f)
                val w = webViewRef.value ?: return@CustomLayoutOverlay
                val parent = mainBoxRef.value as? ViewGroup ?: return@CustomLayoutOverlay
                val lp = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                w.layoutParams = lp
                w.requestLayout()
            }
        )
    }
}

/**
 * 自定义四角布局 Overlay:
 * - 直接在游戏画面上显示 4 个角的拖动手柄(28dp 圆形,绿色)
 * - 矩形 4 边用 2dp 绿线标记选中区域
 * - 拖动手柄实时改 WebView LayoutParams,边拖边看到游戏画面变化
 * - 右上角关闭按钮 + 重置全屏按钮
 *
 * 关键:Handle 用 pointerInput + detectDragGestures(只处理拖动手柄本身,不影响 WebView 触摸)
 * BoxWithConstraints 拿到实际像素宽高,转成归一化 0..1 值
 */
@Composable
private fun CustomLayoutOverlay(
    initialLeft: Float, initialTop: Float, initialRight: Float, initialBottom: Float,
    parentWidth: Int, parentHeight: Int,
    onChange: (Float, Float, Float, Float) -> Unit,
    onClose: () -> Unit,
    onReset: () -> Unit
) {
    val left = remember { mutableFloatStateOf(initialLeft) }
    val top = remember { mutableFloatStateOf(initialTop) }
    val right = remember { mutableFloatStateOf(initialRight) }
    val bottom = remember { mutableFloatStateOf(initialBottom) }

    // 同步外部 initial 到内部(每次重组时)
    androidx.compose.runtime.LaunchedEffect(initialLeft, initialTop, initialRight, initialBottom) {
        left.floatValue = initialLeft
        top.floatValue = initialTop
        right.floatValue = initialRight
        bottom.floatValue = initialBottom
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val w = if (parentWidth > 0) parentWidth.toFloat() else constraints.maxWidth.toFloat()
        val h = if (parentHeight > 0) parentHeight.toFloat() else constraints.maxHeight.toFloat()

        // 选中矩形 (4 边绿线)
        val lpx = left.floatValue * w
        val tpx = top.floatValue * h
        val rpx = right.floatValue * w
        val bpx = bottom.floatValue * h
        val rectWDp = (rpx - lpx) / LocalDensity.current.density
        val rectHDp = (bpx - tpx) / LocalDensity.current.density

        // 矩形边框
        Box(
            modifier = Modifier
                .offset { IntOffset(lpx.toInt(), tpx.toInt()) }
                .size(
                    width = rectWDp.dp,
                    height = rectHDp.dp
                )
                .border(2.dp, Color(0xFF4CAF50))
        )

        // 顶部状态条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "拖动四角调节画面 (L=${"%.2f".format(left.floatValue)} T=${"%.2f".format(top.floatValue)} R=${"%.2f".format(right.floatValue)} B=${"%.2f".format(bottom.floatValue)})",
                    color = Color.White,
                    fontSize = 11.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onReset) {
                        Text("重置", color = Color(0xFFFFC107), fontSize = 12.sp)
                    }
                    TextButton(onClick = onClose) {
                        Text("完成", color = Color(0xFF4CAF50), fontSize = 12.sp)
                    }
                }
            }
        }

        // 4 个角拖动手柄
        Box(modifier = Modifier.fillMaxSize()) {
            CornerHandle(
                x = lpx, y = tpx, which = "tl", w = w, h = h,
                startL = left.floatValue, startT = top.floatValue,
                startR = right.floatValue, startB = bottom.floatValue
            ) { nl, nt, nr, nb ->
                left.floatValue = nl; top.floatValue = nt
                right.floatValue = nr; bottom.floatValue = nb
                onChange(nl, nt, nr, nb)
            }
            CornerHandle(
                x = rpx, y = tpx, which = "tr", w = w, h = h,
                startL = left.floatValue, startT = top.floatValue,
                startR = right.floatValue, startB = bottom.floatValue
            ) { nl, nt, nr, nb ->
                left.floatValue = nl; top.floatValue = nt
                right.floatValue = nr; bottom.floatValue = nb
                onChange(nl, nt, nr, nb)
            }
            CornerHandle(
                x = lpx, y = bpx, which = "bl", w = w, h = h,
                startL = left.floatValue, startT = top.floatValue,
                startR = right.floatValue, startB = bottom.floatValue
            ) { nl, nt, nr, nb ->
                left.floatValue = nl; top.floatValue = nt
                right.floatValue = nr; bottom.floatValue = nb
                onChange(nl, nt, nr, nb)
            }
            CornerHandle(
                x = rpx, y = bpx, which = "br", w = w, h = h,
                startL = left.floatValue, startT = top.floatValue,
                startR = right.floatValue, startB = bottom.floatValue
            ) { nl, nt, nr, nb ->
                left.floatValue = nl; top.floatValue = nt
                right.floatValue = nr; bottom.floatValue = nb
                onChange(nl, nt, nr, nb)
            }
        }
    }
}

/**
 * 单个角的拖动手柄:
 * - 28dp 圆形绿色手柄
 * - 拖动时实时计算新归一化值(0..1)
 * - 不同角:tl 只控制 left/top, tr 只控制 right/top, bl 只控制 left/bottom, br 只控制 right/bottom
 */
@Composable
private fun CornerHandle(
    x: Float, y: Float, which: String,
    w: Float, h: Float,
    startL: Float, startT: Float, startR: Float, startB: Float,
    onChange: (Float, Float, Float, Float) -> Unit
) {
    // 用 remember 缓存拖动起始值,避免每次重组重置
    val dragStart = remember { mutableStateOf(0f to 0f) }
    val initialBox = remember { mutableStateOf(QuadF(startL, startT, startR, startB)) }

    Box(
        modifier = Modifier
            .offset { IntOffset((x - 18f).toInt(), (y - 18f).toInt()) }
            .size(36.dp)
            .background(Color(0xFF4CAF50), CircleShape)
            .border(2.dp, Color.White, CircleShape)
            .pointerInput(which) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragStart.value = offset.x to offset.y
                        initialBox.value = QuadF(startL, startT, startR, startB)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val dx = dragAmount.x / w
                        val dy = dragAmount.y / h
                        val init = initialBox.value
                        when (which) {
                            "tl" -> {
                                val nl = (init.l + dx).coerceIn(0f, init.r - 0.02f)
                                val nt = (init.t + dy).coerceIn(0f, init.b - 0.02f)
                                onChange(nl, nt, init.r, init.b)
                            }
                            "tr" -> {
                                val nr = (init.r + dx).coerceIn(init.l + 0.02f, 1f)
                                val nt = (init.t + dy).coerceIn(0f, init.b - 0.02f)
                                onChange(init.l, nt, nr, init.b)
                            }
                            "bl" -> {
                                val nl = (init.l + dx).coerceIn(0f, init.r - 0.02f)
                                val nb = (init.b + dy).coerceIn(init.t + 0.02f, 1f)
                                onChange(nl, init.t, init.r, nb)
                            }
                            "br" -> {
                                val nr = (init.r + dx).coerceIn(init.l + 0.02f, 1f)
                                val nb = (init.b + dy).coerceIn(init.t + 0.02f, 1f)
                                onChange(init.l, init.t, nr, nb)
                            }
                        }
                    }
                )
            }
    )
}

private data class QuadF(val l: Float, val t: Float, val r: Float, val b: Float)

@SuppressLint("ViewConstructor")
private class FrameLayoutSwfContainer(context: Context) : android.widget.FrameLayout(context) {
    /** 编辑模式开关:开启后所有触摸事件都拦截,不让 WebView 收到 */
    var interceptAllTouch: Boolean = false
    override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean = interceptAllTouch
}

/**
 * 把 SWF_SNIFFER_SCRIPT 返回的 JSON 解析并以 AlertDialog 列出 SWF + 资源。
 * 顶级函数(不嵌在 @Composable 内),避免 Kotlin 嵌套函数前向引用 + 访问控制问题。
 */
private fun showExtractResultDialog(context: Context, json: String) {
    try {
        val arr = org.json.JSONArray(json)
        val lines = mutableListOf<String>()
        var swfCount = 0
        var resCount = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url", "")
            val t = o.optString("title", u.substringAfterLast('/'))
            val type = o.optString("type", "swf")
            if (type == "resource") {
                resCount++
                lines.add("[资源] $t\n  $u")
            } else {
                swfCount++
                lines.add("[SWF] $t\n  $u")
            }
        }
        val msg = buildString {
            append("发现 $swfCount 个 SWF" + if (resCount > 0) " + $resCount 个资源" else "")
            if (lines.isNotEmpty()) {
                append("\n\n")
                append(lines.joinToString("\n\n").take(1500))
                if (lines.size > 8) append("\n\n... (共 ${lines.size} 条)")
            }
        }
        android.app.AlertDialog.Builder(context)
            .setTitle("提取 SWF")
            .setMessage(msg)
            .setPositiveButton("好") { d, _ -> d.dismiss() }
            .show()
    } catch (e: Exception) {
        Toast.makeText(context, "解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

// ============== SWF 嗅探器脚本（与 3.3 一致） ==============
private const val SWF_SNIFFER_SCRIPT = """
(function(){
  var found = {};
  var resources = {};
  // 资源文件扩展名(Flash 游戏常见外部资源)
  var resExts = /\.(jpe?g|png|gif|bmp|svg|mp3|wav|ogg|flv|mp4|f4v|xml|json|txt|csv|css|js|php|asp)([?#]|$)/i;
  function addSwf(url, title){
    if(!url) return;
    try { url = new URL(url, location.href).href; } catch(e) { return; }
    if(!/\.swf([?#]|$)/i.test(url) && !/application\/x-shockwave-flash/i.test(url)){
      if(!/^data:application\/x-shockwave-flash/i.test(url)) return;
    }
    if(found[url]) return;
    var t = title || '';
    if(!t){
      try { t = decodeURIComponent(url.split('/').pop().split('?')[0].replace(/\.swf$/i,'')); } catch(e){}
    }
    found[url] = {url:url, title:t, size:'', type:'swf'};
  }
  function addResource(url){
    if(!url) return;
    try { url = new URL(url, location.href).href; } catch(e) { return; }
    if(!/^https?:/i.test(url)) return;
    if(/\.swf([?#]|$)/i.test(url)) return;
    if(!resExts.test(url)) return;
    if(resources[url]) return;
    var subDir = '';
    try {
      var p = new URL(url).pathname;
      var dir = p.substring(0, p.lastIndexOf('/') + 1);
      if(dir.startsWith('/')) dir = dir.substring(1);
      subDir = dir;
    } catch(e) {}
    var name = url.split('/').pop().split('?')[0] || 'resource';
    resources[url] = {url:url, title:name, size:'', type:'resource', subDir:subDir};
  }
  function report(){
    var arr = [];
    for(var u in found) arr.push(found[u]);
    for(var u in resources) arr.push(resources[u]);
    if(arr.length > 0 && window.Android && window.Android.onSwfFound){
      window.Android.onSwfFound(JSON.stringify(arr));
    } else if (window.Android && window.Android.toast) {
      window.Android.toast('未发现 SWF 或资源');
    }
  }
  function scanDOM(){
    var objs = document.querySelectorAll('object[data], embed[src]');
    objs.forEach(function(el){
      var u = el.getAttribute('data') || el.getAttribute('src') || '';
      var t = el.getAttribute('title') || el.getAttribute('name') || '';
      if(u) addSwf(u, t);
    });
    var params = document.querySelectorAll('param[name="movie"], param[name="src"]');
    params.forEach(function(p){
      var v = p.getAttribute('value') || '';
      if(v) addSwf(v, '');
    });
    var all = document.querySelectorAll('[data*=".swf"], [src*=".swf"], [href*=".swf"]');
    all.forEach(function(el){
      ['data','src','href'].forEach(function(attr){
        var v = el.getAttribute(attr);
        if(v && /\.swf/i.test(v)) addSwf(v, el.getAttribute('title') || '');
      });
    });
    var imgs = document.querySelectorAll('img[src]');
    imgs.forEach(function(el){
      var s = el.getAttribute('src') || '';
      if(s) addResource(s);
    });
    var sources = document.querySelectorAll('source[src], video[src], audio[src]');
    sources.forEach(function(el){
      var s = el.getAttribute('src') || '';
      if(s) addResource(s);
    });
    var links = document.querySelectorAll('link[href]');
    links.forEach(function(el){
      var s = el.getAttribute('href') || '';
      if(s && resExts.test(s)) addResource(s);
    });
  }
  function scanPerformance(){
    try {
      var entries = performance.getEntriesByType('resource');
      entries.forEach(function(e){
        if(/\.swf([?#]|$)/i.test(e.name)) addSwf(e.name, '');
        else if(resExts.test(e.name)) addResource(e.name);
      });
    } catch(e) {}
  }
  // 关键:hook XHR/fetch,捕获 mhhf 等动态加载的 SWF/资源
  if(!window.__swfSniffHooked){
    window.__swfSniffHooked = true;
    try {
      var origOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(method, url){
        if(url && /\.swf([?#]|$)/i.test(url)) addSwf(url, '');
        else if(url && resExts.test(url)) addResource(url);
        return origOpen.apply(this, arguments);
      };
    } catch(e) {}
    try {
      var origFetch = window.fetch;
      if(origFetch){
        window.fetch = function(input){
          var u = typeof input === 'string' ? input : (input && input.url ? input.url : '');
          if(u && /\.swf([?#]|$)/i.test(u)) addSwf(u, '');
          else if(u && resExts.test(u)) addResource(u);
          return origFetch.apply(this, arguments);
        };
      }
    } catch(e) {}
  }
  scanDOM();
  scanPerformance();
  report();
  // 多次扫描捕获延迟加载(mhhf 等 SPA 路由后加载)
  setTimeout(function(){ scanDOM(); scanPerformance(); report(); }, 1000);
  setTimeout(function(){ scanDOM(); scanPerformance(); report(); }, 3000);
  setTimeout(function(){ scanDOM(); scanPerformance(); report(); }, 6000);
  // MutationObserver:监听 DOM 变化(mhhf 切页/弹窗)
  if(window.MutationObserver){
    var mo = new MutationObserver(function(){
      scanDOM();
    });
    try {
      mo.observe(document.documentElement || document.body || document, {childList:true, subtree:true});
      setTimeout(function(){ mo.disconnect(); }, 8000);
    } catch(e) {}
  }
})();
"""

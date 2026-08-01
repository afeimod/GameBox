package com.nesstation.app.ui.online

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.nesstation.app.flash.data.GameType
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
import kotlinx.coroutines.flow.MutableStateFlow

private val PrimaryBackground = Color(0xFF0F1115)

/**
 * 在线网页游戏/Flash 游戏的 Compose 入口。
 *
 * 1:1 移植自 3.3-fix2 GameActivity：把 3.3 的 setupWebView / setupGamepad / setupFloatingMenu
 * 全部用 AndroidView 嵌入到 Compose 里。
 *
 * @param url   入口 URL（页面 URL 或 .swf 直链）
 * @param uaMode  入口 UA 模式：desktop / mobile / ie_compat
 * @param onExit  退出回调
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebGameScreen(
    url: String,
    uaMode: String = "desktop",
    onExit: () -> Unit
) {
    val context = LocalContext.current

    // 确保 PrefsManager 已初始化（应用启动时也应该调用一次）
    remember { PrefsManager.init(context) }

    var gamepadVisible by remember { mutableStateOf(PrefsManager.isGamepadEnabled) }
    var isMouseEnabled by remember { mutableStateOf(PrefsManager.isMouseEnabled) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isLandscape by remember { mutableStateOf(true) }
    val errorMessage = remember { mutableStateOf<String?>(null) }
    val reloadTrigger = remember { mutableStateOf(0) }
    val swfExtractJson = remember { mutableStateOf<String?>(null) }
    val showKeyDialog = remember { mutableStateOf(false) }
    val showFlashDialog = remember { mutableStateOf(false) }
    val showZoomDialog = remember { mutableStateOf(false) }
    val showUaDialog = remember { mutableStateOf(false) }
    val gamepadVersion = remember { mutableStateOf(0) } // 按键设置变更后递增，触发手柄重建

    // 持有各 view 的引用
    val webViewRef = remember { mutableStateOf<GameWebView?>(null) }
    val dpadRef = remember { mutableStateOf<DPadView?>(null) }
    val actionRef = remember { mutableStateOf<ActionButtonView?>(null) }
    val mouseRef = remember { mutableStateOf<MouseControlView?>(null) }
    val floatingMenuRef = remember { mutableStateOf<FloatingMenuView?>(null) }
    val webAppInterfaceRef = remember { mutableStateOf<WebAppInterface?>(null) }
    val systemButtonsRef = remember { mutableStateOf<View?>(null) }
    val startBtnRef = remember { mutableStateOf<View?>(null) }
    val selectBtnRef = remember { mutableStateOf<View?>(null) }
    val localSwfUri = remember { mutableStateOf<String?>(null) }
    val mainBoxRef = remember { mutableStateOf<ViewGroup?>(null) }

    // 引擎选择对话框 / 提取 SWF 对话框
    var pendingSwfAction by remember { mutableStateOf<String?>(null) }

    // ---------- File chooser ----------
    val filePathCallback = remember { mutableStateOf<ValueCallback<Array<android.net.Uri>>?>(null) }
    val activityResultLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val arr = uris?.takeIf { it.isNotEmpty() }?.toTypedArray()
        filePathCallback.value?.onReceiveValue(arr)
        filePathCallback.value = null
    }

    // ---------- Activity (for fullscreen / orientation) ----------
    val activity = context as? Activity

    // ---------- 物理键盘（实体键盘）透传 ----------
    val keyEventHandler: (KeyEvent) -> Boolean = { event ->
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) onExit()
            true
        } else if (event.keyCode in GameWebView.GAME_KEYS) {
            val wv = webViewRef.value
            if (wv != null) {
                wv.dispatchKeyEvent(event)
                true
            } else false
        } else false
    }
    BackHandler(enabled = true, onBack = {
        val wv = webViewRef.value
        if (wv != null && wv.canGoBack()) wv.goBack() else onExit()
    })

    fun applyOrientation(landscape: Boolean) {
        activity?.requestedOrientation = if (landscape)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    fun applyFullscreen(full: Boolean) {
        if (full) {
            // 简单实现：隐藏系统栏
            activity?.window?.let { w ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
                androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).hide(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                )
            }
        } else {
            activity?.window?.let { w ->
                androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, true)
                androidx.core.view.WindowInsetsControllerCompat(w, w.decorView).show(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                )
            }
        }
    }

    // 切换全屏
    fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        applyFullscreen(isFullscreen)
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
        PrefsManager.sp.edit().putBoolean("mouse_enabled", isMouseEnabled).apply()
        val wv = webViewRef.value
        if (wv != null) {
            if (isMouseEnabled) {
                wv.evaluateJavascript(MOUSE_CURSOR_SCRIPT, null)
            } else {
                wv.evaluateJavascript(
                    "(function(){var c=document.getElementById('__mouseCursor');if(c)c.remove();window.__mouseEnabled=false;})();", null
                )
            }
        }
    }

    fun reload() {
        reloadTrigger.value = reloadTrigger.value + 1
    }

    fun applyEngineAndReload(engine: String) {
        PrefsManager.sp.edit().putString("flash_engine", engine).putBoolean("flash_enabled", true).apply()
        reload()
    }

    fun applyFlashEnabled(enabled: Boolean) {
        PrefsManager.sp.edit().putBoolean("flash_enabled", enabled).apply()
        reload()
    }

    fun applyZoom(mode: String, manual: Int) {
        PrefsManager.sp.edit()
            .putString("page_zoom_mode", mode)
            .putInt("page_zoom_manual", manual)
            .apply()
        reload()
    }

    fun applyUa(mode: String) {
        PrefsManager.sp.edit().putString("ua_mode", mode).apply()
        val wv = webViewRef.value
        if (wv != null) {
            if (mode == "desktop") {
                // desktop 模式由 GameWebViewClient 智能判断
                // 这里简单 reload 让 GameWebViewClient 走判断
            } else {
                wv.useUaMode(mode)
            }
        }
        reload()
    }

    fun showSwfActions(swfUrl: String) {
        pendingSwfAction = swfUrl
    }

    fun playSwfWithEngine(swfUrl: String) {
        val wv = webViewRef.value
        if (wv != null) {
            val playerUrl = NavHelper.playerUrl(swfUrl, base = wv.url, title = null)
            wv.loadUrl(playerUrl)
        }
    }

    // ---- SWF 嗅探器 (与 3.3 SWF_SNIFFER_SCRIPT 一致) ----
    fun extractSwfFromPage() {
        Toast.makeText(context, "正在扫描页面中的 SWF...", Toast.LENGTH_SHORT).show()
        val iface = webAppInterfaceRef.value
        if (iface != null) {
            iface.swfExtractCallback = { json ->
                swfExtractJson.value = json
            }
        }
        webViewRef.value?.evaluateJavascript(SWF_SNIFFER_SCRIPT, null)
    }

    // ---- 释放按键 ----
    fun releaseAllKeys() {
        webViewRef.value?.releaseAllKeys()
    }

    // Compose 主容器
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(PrimaryBackground)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                FrameLayoutGameContainer(ctx).apply {
                    mainBoxRef.value = this
                }
            },
            update = { container ->
                // 每次 reload 触发重新加载 URL
                val wv = webViewRef.value
                if (wv != null) {
                    val currentUrl = wv.url ?: url
                    val isSwf = NavHelper.isSwf(currentUrl) || NavHelper.isSwf(url) || NavHelper.isLocalFile(url)
                    if (isSwf) {
                        val playerUrl = NavHelper.playerUrl(url, base = null, title = null)
                        wv.loadUrl(playerUrl)
                    } else if (url.contains("4399.com")) {
                        wv.loadUrl(url, mapOf("Referer" to "https://www.4399.com/"))
                    } else {
                        wv.loadUrl(url)
                    }
                }
            }
        )
    }

    // 在容器创建后用 LaunchedEffect 把各 view 加进去
    LaunchedEffect(mainBoxRef.value) {
        val container = mainBoxRef.value ?: return@LaunchedEffect
        if (webViewRef.value == null) {
            // 创建 WebView
            val wv = GameWebView(container.context)
            val webAppInterface = WebAppInterface(container.context)
            webAppInterfaceRef.value = webAppInterface

            // 注入 SWF 打开回调
            webAppInterface.openSwfCallback = { swfUrl, _ ->
                val wv2 = webViewRef.value
                if (wv2 != null) {
                    val playerUrl = NavHelper.playerUrl(swfUrl, base = wv2.url, title = null)
                    wv2.loadUrl(playerUrl)
                }
            }
            // 注入 SWF 提取回调
            webAppInterface.swfExtractCallback = { json ->
                swfExtractJson.value = json
            }

            // 客户端与回调
            val chromeCallback = object : GameWebChromeClient.Callback {
                override fun onProgress(progress: Int) {}
                override fun onTitle(title: String?) {}
                override fun onConsole(level: String, msg: String, sourceId: String?, line: Int) {}
                override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {}
                override fun onHideFullscreen() {}
                override fun onFileChooser(callback: ValueCallback<Array<android.net.Uri>>, accept: String?): Boolean {
                    filePathCallback.value?.onReceiveValue(null)
                    filePathCallback.value = callback
                    val mimes = accept?.split(",")?.toTypedArray() ?: arrayOf("*/*")
                    try { activityResultLauncher.launch(mimes) } catch (e: Exception) { callback.onReceiveValue(null); filePathCallback.value = null }
                    return true
                }
            }

            val viewClientCallback = object : GameWebViewClient.Callback {
                override fun onPageStarted(url: String?) {
                    webViewRef.value?.releaseAllKeys()
                }
                override fun onPageFinished(url: String?) {}
                override fun onProgress(progress: Int) {}
                override fun onError(url: String?, errorCode: Int, description: String?) {
                    if (errorCode == -1) return
                    errorMessage.value = "加载失败: $description"
                }
                override fun onSwfIntercepted(swfUrl: String, pageUrl: String) {
                    val wv2 = webViewRef.value
                    if (wv2 != null) {
                        val playerUrl = NavHelper.playerUrl(swfUrl, base = pageUrl, title = null)
                        wv2.loadUrl(playerUrl)
                    }
                }
                override fun shouldInjectRuffle(url: String?): Boolean {
                    if (url == null) return false
                    if (url.startsWith("file:///android_asset/")) return false
                    if (url.startsWith("https://flash.local/")) return false
                    val lower = url.lowercase()
                    if (lower.contains("/login") || lower.contains("/signin") ||
                        lower.contains("/register") || lower.contains("/api/") ||
                        lower.contains("/ajax/") || lower.contains("/account") ||
                        lower.contains("/user/") || lower.contains("/passport") ||
                        lower.contains("/auth") || lower.contains("/logout")) return false
                    return PrefsManager.isFlashEnabled
                }
                override fun getCachedSwfPath(): String? = null
                override fun getLocalSwfUri(): String? = localSwfUri.value
            }

            val wvClient = GameWebViewClient(viewClientCallback)

            wv.apply {
                addJavascriptInterface(webAppInterface, "Android")
                webChromeClient = object : GameWebChromeClient(chromeCallback) {}
                webViewClient = wvClient
                // UA 模式
                if (uaMode == "ie_compat") {
                    useUaMode("ie_compat")
                } else if (uaMode == "mobile") {
                    useUaMode("mobile")
                } else {
                    useDesktopMode(true)
                }
                setOnKeyListener { _, keyCode, event ->
                    keyEventHandler(event)
                }
            }
            wv.injectDocumentStartScripts()

            container.addView(wv, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ))
            webViewRef.value = wv

            // 初始加载
            val isSwf = NavHelper.isSwf(url) || NavHelper.isLocalFile(url)
            if (isSwf) {
                val playerUrl = NavHelper.playerUrl(url, base = null, title = null)
                wv.loadUrl(playerUrl)
            } else if (url.contains("4399.com")) {
                wv.loadUrl(url, mapOf("Referer" to "https://www.4399.com/"))
            } else {
                wv.loadUrl(url)
            }
        }
    }

    // 创建手柄与菜单
    LaunchedEffect(mainBoxRef.value, gamepadVisible, isMouseEnabled, gamepadVersion.value) {
        val container = mainBoxRef.value ?: return@LaunchedEffect
        val density = container.resources.displayMetrics.density

        // 清掉旧手柄控件
        listOfNotNull(
            dpadRef.value, actionRef.value, mouseRef.value,
            systemButtonsRef.value, floatingMenuRef.value
        ).forEach { container.removeView(it) }

        // 创建 DPad
        val dpad = DPadView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
            isDragMode = false
        }
        dpadRef.value = dpad
        val dpadSize = (140 * density).toInt()
        val dpadLp = android.widget.FrameLayout.LayoutParams(dpadSize, dpadSize).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.START
            bottomMargin = (24 * density).toInt()
            marginStart = (16 * density).toInt()
        }
        if (gamepadVisible) container.addView(dpad, dpadLp)

        // 创建 Action Buttons
        val action = ActionButtonView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
            isDragMode = false
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

        // 创建 System Buttons (Start/Select)
        val sysContainer = android.widget.LinearLayout(container.context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        val selectBtn = android.widget.Button(container.context).apply {
            text = "Select"
            setOnClickListener { webViewRef.value?.injectKey(KeyMapper.toKeyCode(PrefsManager.selectKey)) }
        }
        val startBtn = android.widget.Button(container.context).apply {
            text = "Start"
            setOnClickListener { webViewRef.value?.injectKey(KeyMapper.toKeyCode(PrefsManager.startKey)) }
        }
        sysContainer.addView(selectBtn)
        sysContainer.addView(startBtn)
        systemButtonsRef.value = sysContainer
        startBtnRef.value = startBtn
        selectBtnRef.value = selectBtn
        val sysLp = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            topMargin = (80 * density).toInt()
        }
        if (gamepadVisible && PrefsManager.isSystemButtonsVisible) container.addView(sysContainer, sysLp)

        // 创建 MouseControl
        val mouse = MouseControlView(container.context).apply {
            targetWebView = webViewRef.value
            overlayAlpha = PrefsManager.gamepadAlpha
            isDragMode = false
        }
        mouseRef.value = mouse
        val mouseSize = (220 * density).toInt()
        val mouseLp = android.widget.FrameLayout.LayoutParams(mouseSize, (80 * density).toInt()).apply {
            gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
            bottomMargin = (200 * density).toInt()
        }
        if (PrefsManager.isMouseButtonsVisible) container.addView(mouse, mouseLp)

        // 创建悬浮菜单
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
                val wv = webViewRef.value
                if (wv != null && wv.canGoBack()) wv.goBack() else onExit()
            }
            override fun onClose() = onExit()
            override fun onExtractSwf() = extractSwfFromPage()
        })
        menu.isFullscreen = isFullscreen
        menu.isLandscape = isLandscape
        floatingMenuRef.value = menu
        menu.attachTo(container)
    }

    // 切换手柄可见性时释放按键
    LaunchedEffect(gamepadVisible) {
        if (!gamepadVisible) webViewRef.value?.releaseAllKeys()
    }

    // 释放资源
    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.let { wv ->
                (wv.parent as? ViewGroup)?.removeView(wv)
                wv.destroy()
            }
        }
    }

    // 错误提示
    errorMessage.value?.let { msg ->
        AlertDialog(
            onDismissRequest = { errorMessage.value = null },
            title = { Text("错误") },
            text = { Text(msg) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { errorMessage.value = null }) {
                    Text("确定")
                }
            }
        )
    }

    // SWF 提取结果对话框
    swfExtractJson.value?.let { json ->
        SwfExtractDialog(
            json = json,
            onDismiss = { swfExtractJson.value = null },
            onPlay = { url ->
                playSwfWithEngine(url)
                swfExtractJson.value = null
            },
            onDownload = { url ->
                Toast.makeText(context, "开始下载 $url", Toast.LENGTH_SHORT).show()
                swfExtractJson.value = null
            }
        )
    }

    // 引擎选择对话框
    if (showFlashDialog.value) {
        FlashEngineDialog(
            onPick = { engine ->
                applyEngineAndReload(engine)
                showFlashDialog.value = false
            },
            onDismiss = { showFlashDialog.value = false }
        )
    }

    // 页面缩放对话框
    if (showZoomDialog.value) {
        PageZoomDialog(
            onApply = { mode, manual ->
                applyZoom(mode, manual)
                showZoomDialog.value = false
            },
            onDismiss = { showZoomDialog.value = false }
        )
    }

    // UA 模式对话框
    if (showUaDialog.value) {
        UaModeDialog(
            onPick = { mode ->
                applyUa(mode)
                showUaDialog.value = false
            },
            onDismiss = { showUaDialog.value = false }
        )
    }

    // 按键设置对话框
    if (showKeyDialog.value) {
        KeyMappingDialog(
            onAdd = {
                val c = PrefsManager.gamepadKeyCount
                if (c < 18) PrefsManager.sp.edit().putInt("gamepad_key_count", c + 1).apply()
                showKeyDialog.value = false
                gamepadVersion.value++
                reload()
            },
            onRemove = {
                val c = PrefsManager.gamepadKeyCount
                if (c > 2) PrefsManager.sp.edit().putInt("gamepad_key_count", c - 1).apply()
                showKeyDialog.value = false
                gamepadVersion.value++
                reload()
            },
            onPickKey = { idx ->
                // 直接选择 key（J/K/L/U/I/O 等）
                val keys = arrayOf("J","K","L","U","I","O","A","B","C","D","E","F","G","H","M","N","P","Q","R","S","T","W","X","Y","Z","SPACE","ENTER","TAB","ESC","0","1","2","3","4","5","6","7","8","9")
                val current = PrefsManager.gamepadKeys.getOrElse(idx) { "J" }
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle("按键 ${idx + 1} 映射")
                    .setSingleChoiceItems(keys, keys.indexOf(current).coerceAtLeast(0)) { dlg, which ->
                        PrefsManager.sp.edit().putString("gamepad_key_${idx + 1}", keys[which]).apply()
                        dlg.dismiss()
                        showKeyDialog.value = false
                        gamepadVersion.value++
                        reload()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            },
            onReset = {
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
                    .putFloat("system_pos_x", -1f)
                    .putFloat("system_pos_y", -1f)
                    .apply()
                showKeyDialog.value = false
                gamepadVersion.value++
                reload()
            },
            onDismiss = { showKeyDialog.value = false }
        )
    }
}

/** FrameLayout 子类，作为手柄 + WebView + 菜单的承载容器 */
@SuppressLint("ViewConstructor")
private class FrameLayoutGameContainer(context: Context) : android.widget.FrameLayout(context)

@Composable
private fun SwfExtractDialog(
    json: String,
    onDismiss: () -> Unit,
    onPlay: (String) -> Unit,
    onDownload: (String) -> Unit
) {
    val swfList = remember(json) {
        try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val u = o.optString("url", "")
                if (u.isEmpty()) null else SwfItem(
                    url = u,
                    title = o.optString("title", u.substringAfterLast('/'))
                )
            }
        } catch (e: Exception) { emptyList() }
    }
    if (swfList.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("提取 SWF") },
            text = { Text("未在页面中发现 SWF 文件") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("确定") } }
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("发现 ${swfList.size} 个 SWF") },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(count = swfList.size) { idx ->
                        val it = swfList[idx]
                        androidx.compose.material3.TextButton(
                            onClick = { onPlay(it.url) },
                            modifier = Modifier.fillMaxSize()
                        ) {
                            androidx.compose.foundation.layout.Column {
                                Text(it.title, color = androidx.compose.ui.graphics.Color.White, fontSize = androidx.compose.ui.unit.TextUnit(13f, androidx.compose.ui.unit.TextUnitType.Sp))
                                Text(it.url, color = androidx.compose.ui.graphics.Color.Gray, fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp))
                            }
                        }
                    }
                }
            },
            confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭") } }
        )
    }
}

private data class SwfItem(val url: String, val title: String)

@Composable
private fun FlashEngineDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val engines = arrayOf("Ruffle (推荐)", "WAFlash", "关闭 Flash")
    val values = arrayOf("ruffle", "waflash", "off")
    val current = if (PrefsManager.isFlashEnabled) PrefsManager.flashEngine else "off"
    val checked = values.indexOf(current).coerceAtLeast(0)
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("Flash 引擎")
        .setSingleChoiceItems(engines, checked) { dlg, which ->
            if (values[which] == "off") {
                PrefsManager.sp.edit().putBoolean("flash_enabled", false).apply()
                onPick("off")
            } else {
                PrefsManager.sp.edit().putString("flash_engine", values[which]).putBoolean("flash_enabled", true).apply()
                onPick(values[which])
            }
            dlg.dismiss()
        }
        .setNegativeButton("取消", null)
        .setOnDismissListener { onDismiss() }
        .show()
}

@Composable
private fun PageZoomDialog(
    onApply: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val mode = PrefsManager.pageZoomMode
    val manual = PrefsManager.pageZoomManual
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("页面缩放")
        .setMessage("当前：${if (mode == "auto") "自动" else "${manual}%"}\n\n点击确定切换缩放模式（自动 ↔ 75%）。")
        .setPositiveButton("自动") { _, _ -> onApply("auto", manual) }
        .setNegativeButton("75%") { _, _ -> onApply("manual", 75) }
        .setNeutralButton("取消", null)
        .setOnDismissListener { onDismiss() }
        .show()
}

@Composable
private fun UaModeDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val modes = arrayOf("desktop" to "桌面模式 (Chrome)", "ie_compat" to "兼容模式 (IE11)", "mobile" to "移动模式")
    val current = PrefsManager.uaMode
    val checked = modes.indexOfFirst { it.first == current }.coerceAtLeast(0)
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("浏览器兼容模式")
        .setSingleChoiceItems(modes.map { it.second }.toTypedArray(), checked) { dlg, which ->
            onPick(modes[which].first)
            dlg.dismiss()
        }
        .setNegativeButton("取消", null)
        .setOnDismissListener { onDismiss() }
        .show()
}

@Composable
private fun KeyMappingDialog(
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onPickKey: (Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val kmmItems = arrayOf("修改按键映射", "添加按键", "删除按键", "重置为默认")
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle("按键设置（共 ${PrefsManager.gamepadKeyCount} 个）")
        .setItems(kmmItems) { _, which ->
            when (which) {
                0 -> {
                    val keys = PrefsManager.gamepadKeys
                    val labels = keys.mapIndexed { i, k -> "按键 ${i + 1} ($k)" }.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(context)
                        .setTitle("选择要修改的按键")
                        .setItems(labels) { _, idx -> onPickKey(idx) }
                        .setNegativeButton("取消", null)
                        .show()
                }
                1 -> onAdd()
                2 -> onRemove()
                3 -> onReset()
            }
        }
        .setNegativeButton("关闭", null)
        .setOnDismissListener { onDismiss() }
        .show()
}

// ============== 内部脚本（与 3.3 SWF_SNIFFER_SCRIPT 1:1 移植） ==============

private const val SWF_SNIFFER_SCRIPT = """
(function(){
  var found = {};
  function addUrl(url, title){
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
    found[url] = {url:url, title:t, size:''};
  }
  function scanDOM(){
    var objs = document.querySelectorAll('object[data], embed[src]');
    objs.forEach(function(el){
      var u = el.getAttribute('data') || el.getAttribute('src') || '';
      var t = el.getAttribute('title') || el.getAttribute('name') || '';
      if(u) addUrl(u, t);
    });
    var params = document.querySelectorAll('param[name="movie"], param[name="src"]');
    params.forEach(function(p){
      var v = p.getAttribute('value') || '';
      if(v) addUrl(v, '');
    });
    var iframes = document.querySelectorAll('iframe[src]');
    iframes.forEach(function(f){
      var s = f.getAttribute('src') || '';
      if(/\.swf/i.test(s)) addUrl(s, '');
    });
    var all = document.querySelectorAll('[data*=".swf"], [src*=".swf"], [href*=".swf"]');
    all.forEach(function(el){
      ['data','src','href'].forEach(function(attr){
        var v = el.getAttribute(attr);
        if(v && /\.swf/i.test(v)) addUrl(v, el.getAttribute('title') || '');
      });
    });
  }
  function scanPerformance(){
    try {
      var entries = performance.getEntriesByType('resource');
      entries.forEach(function(e){
        if(/\.swf([?#]|$)/i.test(e.name)) addUrl(e.name, '');
      });
    } catch(e) {}
  }
  function scanScripts(){
    var scripts = document.querySelectorAll('script:not([src])');
    var re = /(?:https?:)?[^\s'"<>]+\.swf[^\s'"<>]*/gi;
    scripts.forEach(function(s){
      var text = s.textContent || '';
      var m;
      while((m = re.exec(text)) !== null){
        addUrl(m[0], '');
      }
    });
    var extScripts = document.querySelectorAll('script[src]');
    extScripts.forEach(function(s){
      var src = s.getAttribute('src') || '';
      if(/\.swf/i.test(src)) addUrl(src, '');
    });
  }
  if(!window.__swfSniffHooked){
    window.__swfSniffHooked = true;
    var origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function(method, url){
      if(url && /\.swf([?#]|$)/i.test(url)) addUrl(url, '');
      return origOpen.apply(this, arguments);
    };
    var origFetch = window.fetch;
    if(origFetch){
      window.fetch = function(input){
        var u = typeof input === 'string' ? input : (input && input.url ? input.url : '');
        if(u && /\.swf([?#]|$)/i.test(u)) addUrl(u, '');
        return origFetch.apply(this, arguments);
      };
    }
  }
  scanDOM();
  scanPerformance();
  scanScripts();
  if(window.MutationObserver){
    var mo = new MutationObserver(function(muts){
      muts.forEach(function(m){
        m.addedNodes.forEach(function(n){
          if(n.nodeType === 1){
            var u = n.getAttribute && (n.getAttribute('data') || n.getAttribute('src') || n.getAttribute('href') || '');
            if(u && /\.swf/i.test(u)) addUrl(u, n.getAttribute('title') || '');
            if(n.querySelectorAll){
              var inner = n.querySelectorAll('[data*=".swf"], [src*=".swf"], [href*=".swf"], param[name="movie"]');
              inner.forEach(function(el){
                var v = el.getAttribute('data') || el.getAttribute('src') || el.getAttribute('href') || el.getAttribute('value') || '';
                if(v && /\.swf/i.test(v)) addUrl(v, '');
              });
            }
          }
        });
      });
    });
    mo.observe(document.documentElement || document.body || document, {childList:true, subtree:true});
    setTimeout(function(){ mo.disconnect(); }, 5000);
  }
  setTimeout(function(){
    scanDOM();
    scanPerformance();
    scanScripts();
    var arr = [];
    for(var u in found) arr.push(found[u]);
    if(window.Android && window.Android.onSwfFound){
      window.Android.onSwfFound(JSON.stringify(arr));
    }
  }, 1500);
  setTimeout(function(){
    var arr = [];
    for(var u in found) arr.push(found[u]);
    if(arr.length > 0 && window.Android && window.Android.onSwfFound){
      window.Android.onSwfFound(JSON.stringify(arr));
    }
  }, 500);
})();
"""

private const val MOUSE_CURSOR_SCRIPT = """
(function(){
  if (window.__mouseEnabled) return; window.__mouseEnabled = true;
  var cursor = document.createElement('div');
  cursor.id = '__mouseCursor';
  cursor.style.cssText = 'position:fixed;width:20px;height:20px;pointer-events:none;z-index:999999;left:0;top:0;transform:translate(-4px,-4px);';
  cursor.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" xmlns="http://www.w3.org/2000/svg"><path d="M5.5,3.5L18,12L11.5,12.5L15,19L12.5,20L9,13.5L5.5,17L5.5,3.5Z" fill="white" stroke="black" stroke-width="1.5"/></svg>';
  document.body.appendChild(cursor);
  var lastX = 0, lastY = 0;
  document.addEventListener('touchstart', function(e){
    var t = e.touches[0];
    lastX = t.clientX; lastY = t.clientY;
    cursor.style.left = lastX + 'px';
    cursor.style.top = lastY + 'px';
  }, {passive: true});
  document.addEventListener('touchmove', function(e){
    var t = e.touches[0];
    lastX = t.clientX; lastY = t.clientY;
    cursor.style.left = lastX + 'px';
    cursor.style.top = lastY + 'px';
    var el = document.elementFromPoint(lastX, lastY);
    if (el) {
      var evt = new MouseEvent('mousemove', {bubbles:true, clientX:lastX, clientY:lastY});
      el.dispatchEvent(evt);
    }
  }, {passive: true});
})();
"""

package com.nesstation.app.ui.swf

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.os.Handler
import android.os.Looper
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
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
    val showAspectRatioDialog = remember { mutableStateOf(false) }

    val webViewRef = remember { mutableStateOf<GameWebView?>(null) }
    val dpadRef = remember { mutableStateOf<DPadView?>(null) }
    val actionRef = remember { mutableStateOf<ActionButtonView?>(null) }
    val mouseRef = remember { mutableStateOf<MouseControlView?>(null) }
    val floatingMenuRef = remember { mutableStateOf<FloatingMenuView?>(null) }
    val webAppInterfaceRef = remember { mutableStateOf<WebAppInterface?>(null) }
    val mainBoxRef = remember { mutableStateOf<ViewGroup?>(null) }

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
        PrefsManager.sp.edit().putBoolean("mouse_enabled", isMouseEnabled).apply()
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

            val chromeCallback = object : GameWebChromeClient.Callback {
                override fun onProgress(progress: Int) {}
                override fun onTitle(title: String?) {}
                override fun onConsole(level: String, msg: String, sourceId: String?, line: Int) {}
                override fun onShowFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {}
                override fun onHideFullscreen() {}
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
            }

            wv.apply {
                addJavascriptInterface(webAppInterface, "Android")
                webChromeClient = object : GameWebChromeClient(chromeCallback) {}
                webViewClient = GameWebViewClient(viewClientCallback)
                useDesktopMode(true)
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

            val swfProxy = "https://flash.local/local.swf?t=${System.currentTimeMillis()}"
            val playerUrl = NavHelper.playerUrl(swfProxy, base = null, title = null)
            wv.loadUrl(playerUrl)
        }
    }

    LaunchedEffect(mainBoxRef.value, gamepadVisible, isMouseEnabled) {
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
        if (PrefsManager.isMouseButtonsVisible) container.addView(mouse, mouseLp)

        val menu = FloatingMenuView(container.context)
        menu.setCallbacks(object : FloatingMenuView.Callbacks {
            override fun onToggleFullscreen() = toggleFullscreen()
            override fun onToggleOrientation() = toggleOrientation()
            override fun onToggleGamepad() = toggleGamepad()
            override fun onToggleMouse() = toggleMouse()
            override fun onOpenKeyMapping() { showKeyDialog.value = true }
            override fun onOpenFlashSettings() { showFlashDialog.value = true }
            override fun onOpenPageZoom() {}
            override fun onOpenUaMode() {}
            override fun onRefresh() = reload()
            override fun onBack() {
                if (webViewRef.value?.canGoBack() == true) webViewRef.value?.goBack() else onExit()
            }
            override fun onClose() = onExit()
            override fun onExtractSwf() {}
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

    if (showFlashDialog.value) {
        val engines = arrayOf("Ruffle", "WAFlash", "关闭 Flash")
        val values = arrayOf("ruffle", "waflash", "off")
        val current = if (PrefsManager.isFlashEnabled) PrefsManager.flashEngine else "off"
        val checked = values.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(context)
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
            .show()
    }

    if (showKeyDialog.value) {
        val items = arrayOf("修改按键映射", "添加按键", "删除按键", "重置为默认")
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("按键设置（共 ${PrefsManager.gamepadKeyCount} 个）")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val keys = PrefsManager.gamepadKeys
                        val labels = keys.mapIndexed { i, k -> "按键 ${i + 1} ($k)" }.toTypedArray()
                        androidx.appcompat.app.AlertDialog.Builder(context)
                            .setTitle("选择要修改的按键")
                            .setItems(labels) { _, idx ->
                                val allKeys = arrayOf("J","K","L","U","I","O","A","B","C","D","E","F","G","H","M","N","P","Q","R","S","T","W","X","Y","Z","SPACE","ENTER","TAB","ESC","0","1","2","3","4","5","6","7","8","9")
                                val cur = PrefsManager.gamepadKeys.getOrElse(idx) { "J" }
                                androidx.appcompat.app.AlertDialog.Builder(context)
                                    .setTitle("按键 ${idx + 1} 映射")
                                    .setSingleChoiceItems(allKeys, allKeys.indexOf(cur).coerceAtLeast(0)) { dlg, w ->
                                        PrefsManager.sp.edit().putString("gamepad_key_${idx + 1}", allKeys[w]).apply()
                                        dlg.dismiss()
                                    }
                                    .setNegativeButton("取消", null)
                                    .show()
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                    1 -> {
                        val c = PrefsManager.gamepadKeyCount
                        if (c < 18) PrefsManager.sp.edit().putInt("gamepad_key_count", c + 1).apply()
                    }
                    2 -> {
                        val c = PrefsManager.gamepadKeyCount
                        if (c > 2) PrefsManager.sp.edit().putInt("gamepad_key_count", c - 1).apply()
                    }
                    3 -> {
                        PrefsManager.sp.edit()
                            .putInt("gamepad_key_count", 6)
                            .putString("gamepad_key_1", "J")
                            .putString("gamepad_key_2", "K")
                            .putString("gamepad_key_3", "L")
                            .putString("gamepad_key_4", "U")
                            .putString("gamepad_key_5", "I")
                            .putString("gamepad_key_6", "O")
                            .apply()
                    }
                }
            }
            .setNegativeButton("关闭", null)
            .setOnDismissListener { showKeyDialog.value = false }
            .show()
    }

    // ========== 画面比例弹窗 ==========
    if (showAspectRatioDialog.value) {
        val ratios = arrayOf("全屏自适应 (auto)", "4:3", "16:9", "16:10", "5:4")
        val values = arrayOf("auto", "4:3", "16:9", "16:10", "5:4")
        val current = PrefsManager.gameAspectRatio
        val checked = values.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("画面比例")
            .setSingleChoiceItems(ratios, checked) { dlg, which ->
                PrefsManager.sp.edit().putString("game_aspect_ratio", values[which]).apply()
                val wv = webViewRef.value
                if (wv != null) {
                    wv.evaluateJavascript(GameWebViewClient.buildAspectRatioScript(values[which]), null)
                }
                dlg.dismiss()
                showAspectRatioDialog.value = false
            }
            .setNegativeButton("取消", null)
            .setOnDismissListener { showAspectRatioDialog.value = false }
            .show()
    }
}

@SuppressLint("ViewConstructor")
private class FrameLayoutSwfContainer(context: Context) : android.widget.FrameLayout(context)

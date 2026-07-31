package com.nesstation.app.ui.swf

import com.nesstation.app.ui.swf.FlashPrefs.Engine

/**
 * Ruffle / WAFlash 引擎注入器（参考 3.3-fix2 RuffleInjector）。
 *
 * 核心职责：
 * 1. 生成 Ruffle config（publicPath / fontSources / defaultFonts / 画质 / 自动播放 等）
 * 2. 决定 Ruffle JS / publicPath / font 的 URL（本地内置 flash.local 虚拟域名）
 * 3. 决定 Ruffle 模式 vs WAFlash 模式 的注入脚本
 *
 * 路径约定（本地模式）：
 * - flash.local/ruffle/ruffle.js         → assets/ruffle/ruffle.js
 * - flash.local/ruffle/core.ruffle.*.js  → assets/ruffle/core.ruffle.*.js
 * - flash.local/ruffle/*.wasm            → assets/ruffle/*.wasm
 * - flash.local/ruffle/simhei.ttf        → assets/ruffle/simhei.ttf （中文字体）
 * - flash.local/waflash/waflash.min.js   → assets/waflash/waflash.min.js
 * - flash.local/waflash/waflash.wasm     → assets/waflash/waflash.wasm
 *
 * 这些 URL 由 FlashWebViewClient.shouldInterceptRequest 拦截并从 assets 返回。
 *
 * 引擎选择（由 [engine] 参数决定，无需读 SharedPreferences）：
 * - [Engine.RUFFLE]   ：Ruffle（Rust + WebAssembly，本地带 simhei.ttf 字体）
 * - [Engine.WAFLASH]  ：WAFlash（AS2/AS3 完整支持，Canvas 渲染）
 */
object RuffleInjector {

    /** 虚拟本地资源前缀：shouldInterceptRequest 会拦截此域名并从 assets 返回 */
    private const val LOCAL_BASE = "https://flash.local/"

    /** Ruffle 引擎 JS 入口 URL（由 shouldInterceptRequest 从 assets 提供） */
    fun scriptUrl(engine: Engine): String = when (engine) {
        Engine.WAFLASH -> "${LOCAL_BASE}waflash/waflash.min.js"
        Engine.RUFFLE  -> "${LOCAL_BASE}ruffle/ruffle.js"
    }

    /** Ruffle publicPath：Ruffle 用此路径加载 core.ruffle.*.js 和 .wasm */
    fun publicPath(engine: Engine): String = when (engine) {
        Engine.WAFLASH -> "${LOCAL_BASE}waflash/"
        Engine.RUFFLE  -> "${LOCAL_BASE}ruffle/"
    }

    /**
     * Ruffle 字体配置：fontSources + defaultFonts。
     * 仅 Ruffle 模式生效。解决 4399 中文 Flash 游戏的方块字问题。
     */
    fun fontConfigScript(): String = """
        ,"fontSources": ["${LOCAL_BASE}ruffle/simhei.ttf"]
        ,"defaultFonts": {
            "sans": ["SimHei"],
            "serif": ["SimHei"],
            "typewriter": ["SimHei"],
            "japaneseGothic": ["SimHei"],
            "japaneseGothicMono": ["SimHei"],
            "japaneseMincho": ["SimHei"],
            "chineseSimplified": ["SimHei"]
        }""".trimIndent()

    /**
     * 画质字符串 → Ruffle quality 选项
     */
    private fun qualityString(q: String): String = when (q) {
        "low"    -> "low"
        "medium" -> "medium"
        "best"   -> "best"
        else     -> "high"
    }

    /**
     * 生成 Ruffle config 脚本（必须在 Ruffle JS 之前执行）。
     * WAFlash 模式不需要 polyfill（使用独立的 waflash.html）。
     */
    fun configScript(quality: String, autoplay: Boolean, engine: Engine = Engine.RUFFLE): String =
        if (engine == Engine.WAFLASH) ""
        else """
            (function(){
              window.RufflePlayer = window.RufflePlayer || {};
              window.RufflePlayer.config = {
                "publicPath": "${publicPath(engine)}",
                "polyfills": true,
                "autoplay": "${if (autoplay) "on" else "off"}",
                "unmuteOverlay": "visible",
                "letterbox": "fullscreen",
                "upgradeToHttps": true,
                "allowScriptAccess": true,
                "scale": "showAll",
                "quality": "${qualityString(quality)}",
                "allowFullscreen": false,
                "splashScreen": true,
                "preloader": true,
                "logLevel": "warn",
                "maxExecutionDuration": {"secs": 15, "nanos": 0}${fontConfigScript()}
              };
            })();
        """.trimIndent()
}

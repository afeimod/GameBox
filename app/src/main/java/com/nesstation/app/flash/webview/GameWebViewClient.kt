package com.nesstation.app.flash.webview

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nesstation.app.flash.data.PrefsManager
import java.io.ByteArrayInputStream

/**
 * 游戏页面 WebView 客户端：
 * 1. onPageFinished 注入 Flash 引擎（Ruffle / swf2js / WAFlash）
 * 2. 拦截 .swf 链接 → 跳转内置播放器页
 * 3. shouldInterceptRequest：拦截 flash.local 虚拟域名，从 assets 返回引擎文件
 * 4. 错误回调（仅主框架错误才显示错误页）
 */
open class GameWebViewClient(
    private val callback: Callback
) : WebViewClient() {

    interface Callback {
        fun onPageStarted(url: String?)
        fun onPageFinished(url: String?)
        fun onProgress(progress: Int)
        fun onError(url: String?, errorCode: Int, description: String?)
        fun onSwfIntercepted(swfUrl: String, pageUrl: String)
        fun shouldInjectRuffle(url: String?): Boolean
        /** 获取 WAFlash 预下载的 SWF 缓存文件路径 */
        fun getCachedSwfPath(): String?
        /** 获取本地 SWF 文件的真实 URI */
        fun getLocalSwfUri(): String?
    }

    /** 常见广告域名 */
    private val adHosts = setOf(
        "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
        "ad.4399.com", "stat.4399.com", "analytics.4399.com"
    )

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url?.toString() ?: return false
        if (url.endsWith(".swf", ignoreCase = true)) {
            callback.onSwfIntercepted(url, view.url ?: url)
            return true
        }
        return false
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url?.toString() ?: return null

        // 1. 广告拦截
        if (PrefsManager.isBlockAds && adHosts.any { url.contains(it) }) {
            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }

        // 2. 拦截 flash.local 虚拟域名
        if (url.contains("flash.local")) {
            val path = url.substringAfter("flash.local/").substringBefore("?")
            // 本地 SWF 代理：flash.local/local.swf → 读取真实文件
            if (path == "local.swf") {
                return interceptLocalSwfProxy(view)
            }
            return interceptAsset(view, path)
        }

        // 3. 拦截 file:///android_asset/waflash/ 请求
        if (url.startsWith("file:///android_asset/waflash/")) {
            val assetPath = url.removePrefix("file:///android_asset/").substringBefore("?")
            return interceptAsset(view, assetPath)
        }

        // 4. 拦截本地 SWF 文件（content:// 或 file://）
        if (url.startsWith("content://") || url.startsWith("file://")) {
            return interceptLocalFile(view, url)
        }

        // 5. 拦截远程 SWF 文件请求
        //    Ruffle/WAFlash 加载 SWF 时需要原生下载（添加 CORS 头 + Cookie 转发）
        //    只拦截真正的 SWF 资源请求，不影响普通网页浏览
        val isSwfRequest = url.endsWith(".swf", ignoreCase = true) ||
            url.contains(".swf?", ignoreCase = true) ||
            (url.contains("4399.com") && (url.contains("/dw-") || url.contains("flash_tm3") || url.contains("flash20")))
        if (isSwfRequest) {
            return interceptSwf(url, request)
        }

        // 不拦截 HTML 页面！
        // View Transitions polyfill 通过 addDocumentStartJavaScript（Document Start 注入）
        // 和 onPageStarted 兜底注入实现，无需 HTML 拦截。
        // HTML 拦截会导致：编码问题、cookie/session 丢失、缓存/重定向破坏。

        return super.shouldInterceptRequest(view, request)
    }

    /** 构建 Flash 支持伪造 + Ruffle/WAFlash 注入脚本（公开供 GameActivity 在 onPageFinished 兜底使用） */
    fun buildFlashInjectScript(pageUrl: String): String {
        val isWaflash = PrefsManager.flashEngine == "waflash"
        return """
        <script>
        (function(){
          if(window.__flashPolyfilled)return;window.__flashPolyfilled=true;
          // === 1. 伪造 Flash 插件支持（必须在页面 JS 之前） ===
          try {
            var fp = {name:'Shockwave Flash',filename:'libflashplayer.so',description:'Shockwave Flash 32.0 r0',length:1,
              0:{type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash'}};
            fp.namedItem = function(n){ return (n === 'Shockwave Flash') ? fp : null; };
            fp.item = function(i){ return i === 0 ? fp : null; };
            fp.refresh = function(){};
            var _plugins = navigator.plugins || {};
            // 保持原有方法
            if (_plugins.namedItem) { fp.namedItem = function(n){ return (n === 'Shockwave Flash') ? fp : _plugins.namedItem.call(_plugins, n); }; }
            if (_plugins.item) { fp.item = function(i){ return i === 0 ? fp : _plugins.item.call(_plugins, i); }; }
            Object.defineProperty(navigator,'plugins',{
              get:function(){
                try {
                  if (!_plugins['Shockwave Flash']) {
                    _plugins['Shockwave Flash'] = fp;
                    _plugins[0] = fp;
                  }
                  _plugins.length = Math.max(_plugins.length || 0, 1);
                } catch(e) {}
                return _plugins;
              },
              configurable: true
            });
            var fm = {type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash',enabledPlugin:fp};
            var _mimes = navigator.mimeTypes || {};
            Object.defineProperty(navigator,'mimeTypes',{
              get:function(){
                try { if (!_mimes['application/x-shockwave-flash']) _mimes['application/x-shockwave-flash'] = fm; } catch(e) {}
                return _mimes;
              },
              configurable: true
            });
            window.ActiveXObject = function(n){if(n&&/ShockwaveFlash/i.test(n))return {SetVariable:function(){},Variable:function(){return ''}};throw new Error('x');};
          } catch(e) {}

          // === 2. 伪造 document.referrer ===
          try {
            Object.defineProperty(document,'referrer',{get:function(){return '$pageUrl';},configurable:true});
          } catch(e) {}

          // === 3. Ruffle polyfill（Ruffle 模式） ===
          ${if (!isWaflash) """
          window.RufflePlayer = window.RufflePlayer || {};
          window.RufflePlayer.config = {
            autoplay: 'on',
            unmuteOverlay: 'visible',
            backgroundColor: '#000000',
            letterbox: 'on',
            polyfills: true,
            maxExecutionDuration: 30,
            logLevel: 'warn'
          };
          var ruffleScript = document.createElement('script');
          ruffleScript.src = 'https://flash.local/ruffle/ruffle.js';
          ruffleScript.onload = function() {
            console.log('[Ruffle] 引擎加载完成');
          };
          document.head.appendChild(ruffleScript);
          """ else """
          // === WAFlash 模式：hook Flash 创建，跳转到 WAFlash 播放器 ===
          var __wafRedirected = false;
          function __wafRedirect(swfUrl, baseUrl) {
            if (__wafRedirected || !swfUrl) return;
            var isSwf = /\.swf/i.test(swfUrl) || /\/dw-\d+/i.test(swfUrl) ||
                        /flash\d*\//i.test(swfUrl) || /mm\.4399\.com/i.test(swfUrl);
            if (!isSwf) return;
            __wafRedirected = true;
            try { swfUrl = new URL(swfUrl, baseUrl || window.location.href).href; } catch(e) {}
            console.log('[WAFlash] 检测到 SWF: ' + swfUrl);
            if (window.Android && window.Android.openSwf) {
              window.Android.openSwf(swfUrl, baseUrl || window.location.href);
            } else {
              window.location.href = 'https://flash.local/waflash.html?swf=' + encodeURIComponent(swfUrl);
            }
          }
          // hook swfobject.embedSWF
          if (window.swfobject && window.swfobject.embedSWF) {
            var oe = window.swfobject.embedSWF;
            window.swfobject.embedSWF = function(){__wafRedirect(arguments[0],window.location.href);return oe.apply(this,arguments);};
          } else {
            var _swo; Object.defineProperty(window,'swfobject',{configurable:true,get:function(){return _swo;},set:function(v){_swo=v;if(v&&v.embedSWF){var o=v.embedSWF;v.embedSWF=function(){__wafRedirect(arguments[0],window.location.href);return o.apply(this,arguments);};}}});
          }
          // hook createFlash (mflash-player)
          function __hookCF(obj){if(!obj||!obj.createFlash||obj.__wafH)return;obj.__wafH=true;var o=obj.createFlash;obj.createFlash=function(){var u=null;if(typeof arguments[0]==='string')u=arguments[0];else if(arguments[0]&&typeof arguments[0]==='object')u=arguments[0].url||arguments[0].src||arguments[0].swf||arguments[0].movie;if(u)__wafRedirect(u,window.location.href);return o.apply(this,arguments);};}
          if(window['mflash-player'])__hookCF(window['mflash-player']);
          var _mp;Object.defineProperty(window,'mflash-player',{configurable:true,get:function(){return _mp;},set:function(v){_mp=v;__hookCF(v);}});
          // hook AC_FL_RunContent
          if(window.AC_FL_RunContent){var oAC=window.AC_FL_RunContent;window.AC_FL_RunContent=function(){var a=Array.prototype.slice.call(arguments);for(var i=0;i<a.length-1;i++){if((a[i]==='src'||a[i]==='movie')&&a[i+1])__wafRedirect(a[i+1],window.location.href);}return oAC.apply(this,arguments);};}
          // DOM 检测
          function __checkFlash(){
            if(__wafRedirected)return;
            var sel='object[type="application/x-shockwave-flash"],embed[type="application/x-shockwave-flash"],object[data$=".swf" i],embed[src$=".swf" i],object[classid*="D27CDB6E" i]';
            var els=document.querySelectorAll(sel);
            for(var i=0;i<els.length;i++){var s=els[i].getAttribute('data')||els[i].getAttribute('src')||'';if(!s){var ps=els[i].querySelectorAll('param[name="movie"],param[name="src"]');for(var j=0;j<ps.length;j++){var v=ps[j].getAttribute('value')||'';if(v){s=v;break;}}}if(s)__wafRedirect(s,window.location.href);}
          }
          __checkFlash();
          if(window.MutationObserver){var mo=new MutationObserver(function(){if(__wafRedirected){mo.disconnect();return;}__checkFlash();});try{mo.observe(document.documentElement||document.body||document,{childList:true,subtree:true});}catch(e){}setTimeout(function(){mo.disconnect();},15000);}
          """}
        })();
        </script>
        """.trimIndent()
    }

    /** 读取本地 SWF 文件代理（flash.local/local.swf → 真实 content:// URI） */
    private fun interceptLocalSwfProxy(view: WebView): WebResourceResponse? {
        val uri = callback.getLocalSwfUri()
        if (uri == null) {
            android.util.Log.w("GameWebViewClient", "local.swf: 无本地文件 URI")
            return WebResourceResponse("application/x-shockwave-flash", null, 404, "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"), java.io.ByteArrayInputStream(ByteArray(0)))
        }
        return try {
            android.util.Log.d("GameWebViewClient", "local.swf 代理: 读取 $uri")
            val parsed = android.net.Uri.parse(uri)
            val data = view.context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: throw java.io.IOException("无法打开文件流")
            android.util.Log.d("GameWebViewClient", "local.swf 读取完成: ${data.size} bytes")
            WebResourceResponse(
                "application/x-shockwave-flash", null, 200, "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Content-Type" to "application/x-shockwave-flash",
                    "Cache-Control" to "no-cache"
                ),
                java.io.ByteArrayInputStream(data)
            )
        } catch (e: Exception) {
            android.util.Log.e("GameWebViewClient", "local.swf 读取失败: ${e.message}")
            WebResourceResponse("application/x-shockwave-flash", null, 404, "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"), java.io.ByteArrayInputStream(ByteArray(0)))
        }
    }

    /** 读取本地 SWF 文件（content:// 或 file://），返回带 CORS 头的响应 */
    private fun interceptLocalFile(view: WebView, url: String): WebResourceResponse? {
        return try {
            android.util.Log.d("GameWebViewClient", "读取本地文件: $url")
            val uri = android.net.Uri.parse(url)
            val data = view.context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw java.io.IOException("无法打开文件流")
            android.util.Log.d("GameWebViewClient", "本地文件读取完成: ${data.size} bytes")
            WebResourceResponse(
                "application/x-shockwave-flash", null,
                200, "OK",
                mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Content-Type" to "application/x-shockwave-flash",
                    "Cache-Control" to "no-cache"
                ),
                java.io.ByteArrayInputStream(data)
            )
        } catch (e: Exception) {
            android.util.Log.e("GameWebViewClient", "本地文件读取失败: ${e.message}")
            WebResourceResponse(
                "application/x-shockwave-flash", null, 404, "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"),
                java.io.ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    /** SWF 下载缓存：避免同一 SWF 被多个并发请求重复下载（Ruffle 会同时发起多个请求） */
    private val swfCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** 统一 CORS 响应头：所有 SWF 拦截响应（成功/失败/预检）都带这些头 */
    private val swfCorsHeaders = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Access-Control-Allow-Methods" to "GET, POST, OPTIONS, HEAD",
        "Access-Control-Allow-Headers" to "*",
        "Content-Type" to "application/x-shockwave-flash",
        "Cache-Control" to "no-cache"
    )

    /** 原生下载 SWF 文件，返回带 CORS 头的响应（含缓存 + 重试 + SSL 兼容 + Cookie/请求头转发） */
    private fun interceptSwf(url: String, request: WebResourceRequest? = null): WebResourceResponse? {
        // 0. 处理 CORS 预检请求（OPTIONS）：直接返回 200 + CORS 头，不下载文件
        //    WebResourceRequest.getMethod() 需要 API 24+，低于 24 无法判断方法
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N &&
            request?.method == "OPTIONS") {
            android.util.Log.d("GameWebViewClient", "SWF OPTIONS 预检通过: $url")
            return WebResourceResponse("text/plain", "UTF-8", 200, "OK",
                swfCorsHeaders, java.io.ByteArrayInputStream(ByteArray(0)))
        }

        // 1. 构建尝试 URL 列表
        //    不再强制 HTTP→HTTPS 升级！
        //    原因：部分 CDN（如 cdn.comment.4399pk.com）不支持 HTTPS，强制升级导致下载失败。
        //    shouldInterceptRequest 返回的响应直接给 WebView，不受 Mixed Content 限制。
        //    策略：HTTP URL 先试原始 HTTP，失败再试 HTTPS；HTTPS URL 直接用
        val tryUrls = when {
            url.startsWith("https://") -> listOf(url)
            url.startsWith("http://") -> listOf(url, "https://" + url.substring(7))
            else -> listOf(url)
        }

        // 2. 检查缓存：Ruffle/WAFlash 可能同时发起多个相同 SWF 请求，缓存避免重复下载
        for (u in tryUrls) {
            swfCache[u]?.let { cached ->
                android.util.Log.d("GameWebViewClient", "SWF 缓存命中: ${cached.size} bytes, URL=$u")
                return WebResourceResponse("application/x-shockwave-flash", null, 200, "OK",
                    swfCorsHeaders, java.io.ByteArrayInputStream(cached))
            }
        }

        // 3. 逐个 URL 尝试下载（每个 URL 最多重试 3 次）
        var lastError: Exception? = null
        for (swfUrl in tryUrls) {
            for (attempt in 1..3) {
                try {
                    android.util.Log.d("GameWebViewClient", "拦截 SWF 请求 (尝试 $attempt): $swfUrl")
                    val conn = java.net.URL(swfUrl).openConnection() as java.net.HttpURLConnection
                    // HTTPS: 信任所有证书，防止 SSL 握手失败导致 SWF 下载不了
                    if (conn is javax.net.ssl.HttpsURLConnection) {
                        conn.sslSocketFactory = trustAllSslSocketFactory()
                        conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                    }
                    conn.connectTimeout = 10000
                    conn.readTimeout = 20000
                    conn.requestMethod = "GET"
                    conn.instanceFollowRedirects = true

                    // 转发原始请求头（User-Agent, Accept 等），模拟浏览器行为
                    // 排除需要自行设置的、条件请求的、和 CORS 相关的 header
                    request?.requestHeaders?.forEach { (key, value) ->
                        val lk = key.lowercase()
                        if (lk !in setOf(
                                "cookie", "referer", "range", "if-modified-since", "if-none-match",
                                "accept-encoding", "origin", "access-control-request-method",
                                "access-control-request-headers", "host", "content-length"
                            )) {
                            conn.setRequestProperty(key, value)
                        }
                    }
                    // 确保 User-Agent 存在（完整浏览器 UA，避免被服务器拒绝）
                    if (request?.requestHeaders?.none { it.key.equals("User-Agent", true) } != false) {
                        conn.setRequestProperty("User-Agent",
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    }
                    conn.setRequestProperty("Accept", "*/*")
                    // 不请求 gzip，避免手动解压
                    conn.setRequestProperty("Accept-Encoding", "identity")

                    // 转发 Cookie（从 CookieManager 获取，防止防盗链/登录态丢失）
                    try {
                        val cookies = android.webkit.CookieManager.getInstance().getCookie(swfUrl)
                        if (cookies != null && cookies.isNotEmpty()) {
                            conn.setRequestProperty("Cookie", cookies)
                            android.util.Log.d("GameWebViewClient", "转发 Cookie: ${cookies.length} chars")
                        }
                    } catch(e: Exception) {}

                    // 添加 Referer（防盗链），从 SWF URL 推导同源 origin
                    if (swfUrl.contains("4399.com")) {
                        conn.setRequestProperty("Referer", "https://www.4399.com/")
                    } else {
                        try {
                            val uri = android.net.Uri.parse(swfUrl)
                            conn.setRequestProperty("Referer", "${uri.scheme}://${uri.host}/")
                        } catch(e: Exception) {
                            conn.setRequestProperty("Referer", swfUrl)
                        }
                    }

                    conn.connect()
                    val responseCode = conn.responseCode
                    if (responseCode in 200..299) {
                        val data = conn.inputStream.readBytes()
                        android.util.Log.d("GameWebViewClient", "SWF 下载完成: ${data.size} bytes, URL=$swfUrl")
                        // 存入缓存（限制缓存大小）
                        if (swfCache.size >= 10) swfCache.clear()
                        swfCache[swfUrl] = data
                        return WebResourceResponse(
                            "application/x-shockwave-flash", null,
                            200, "OK", swfCorsHeaders,
                            java.io.ByteArrayInputStream(data)
                        )
                    } else if (responseCode in 500..599 && attempt < 3) {
                        android.util.Log.w("GameWebViewClient", "SWF 服务器错误 $responseCode, 重试...")
                        Thread.sleep(500L * attempt)
                        continue
                    } else {
                        android.util.Log.w("GameWebViewClient", "SWF 下载失败: HTTP $responseCode, URL=$swfUrl")
                        lastError = RuntimeException("HTTP $responseCode")
                        break // 换下一个 URL 尝试
                    }
                } catch (e: Exception) {
                    lastError = e
                    android.util.Log.w("GameWebViewClient", "SWF 下载异常 (尝试 $attempt): ${e.message}")
                    if (attempt < 3) Thread.sleep(500L * attempt)
                }
            }
            // 再次检查缓存：可能在重试期间其他线程已成功下载
            swfCache[swfUrl]?.let { cached ->
                android.util.Log.d("GameWebViewClient", "SWF 重试期间缓存命中: ${cached.size} bytes")
                return WebResourceResponse("application/x-shockwave-flash", null, 200, "OK",
                    swfCorsHeaders, java.io.ByteArrayInputStream(cached))
            }
        }

        // 4. 所有 URL 都失败了
        android.util.Log.e("GameWebViewClient", "SWF 下载最终失败: ${lastError?.message}, URL=$url")
        // 返回带 CORS 头的空响应（而非 null）
        // 原因：返回 null 会让 WebView 自行请求原始 URL，
        // 但 WAFlash/Ruffle 页面运行在 https://flash.local（HTTPS），
        // 浏览器会因 Mixed Content / CORS 阻止 HTTP 资源加载，导致引擎报错。
        // 返回 502 + CORS 头让引擎知道请求失败，优雅处理（如跳过广告 SWF）。
        return WebResourceResponse("application/x-shockwave-flash", null, 502, "Bad Gateway",
            swfCorsHeaders, java.io.ByteArrayInputStream(ByteArray(0)))
    }

    /** 信任所有 SSL 证书的 SSLSocketFactory（用于 SWF 下载兼容） */
    @Volatile private var _sslFactory: javax.net.ssl.SSLSocketFactory? = null
    private fun trustAllSslSocketFactory(): javax.net.ssl.SSLSocketFactory {
        return _sslFactory ?: synchronized(this) {
            _sslFactory ?: try {
                val tm = object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
                val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
                ctx.init(null, arrayOf(tm), java.security.SecureRandom())
                ctx.socketFactory
            } catch (e: Exception) {
                javax.net.ssl.HttpsURLConnection.getDefaultSSLSocketFactory()
            }.also { _sslFactory = it }
        }
    }

    /** 从 assets 读取文件并返回带 CORS 头的 WebResourceResponse */
    private fun interceptAsset(view: WebView, assetPath: String): WebResourceResponse? {
        return try {
            val input = view.context.assets.open(assetPath)
            val (mime, charset) = when {
                assetPath.endsWith(".wasm") -> "application/wasm" to null
                assetPath.endsWith(".js") -> "application/javascript" to "UTF-8"
                assetPath.endsWith(".html") -> "text/html" to "UTF-8"
                assetPath.endsWith(".css") -> "text/css" to "UTF-8"
                assetPath.endsWith(".ttf") -> "font/ttf" to null
                assetPath.endsWith(".woff") -> "font/woff" to null
                assetPath.endsWith(".woff2") -> "font/woff2" to null
                assetPath.endsWith(".otf") -> "font/otf" to null
                assetPath.endsWith(".data") -> "application/octet-stream" to null
                else -> "application/octet-stream" to null
            }
            val headers = mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Cache-Control" to "no-cache"
            )
            WebResourceResponse(mime, charset, 200, "OK", headers, input)
        } catch (e: Exception) {
            android.util.Log.w("GameWebViewClient", "asset not found: $assetPath", e)
            null
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        callback.onPageStarted(url)

        // 注入按键安全钩子：页面失焦时自动释放所有按键（所有页面通用）
        view?.evaluateJavascript(KEY_SAFETY_SCRIPT, null)

        // View Transitions API polyfill 兜底：addDocumentStartJavaScript 已在页面 JS 之前
        // 覆盖 Document.prototype.startViewTransition。此处作为旧 WebView 的兜底，
        // 同样覆盖 Document.prototype + 捕获 unhandledrejection。
        view?.evaluateJavascript(VIEW_TRANSITION_PATCH_SCRIPT, null)

        // 4399 页面：伪造 document.referrer 绕过防盗链检测 + IE 兼容模式伪造
        if (url != null && url.contains("4399.com")) {
            view?.evaluateJavascript(REFERER_SPOOF_SCRIPT, null)
            if (PrefsManager.uaMode == "ie_compat") {
                view?.evaluateJavascript(IE_COMPAT_SCRIPT, null)
            }
        }

        val isFlashPage = PrefsManager.isFlashEnabled && callback.shouldInjectRuffle(url)

        // Flash 页面：最先注入 Flash 支持伪造（在页面 JS 执行前）
        // 让 4399 检测到浏览器"有 Flash 插件"，从而创建 <object> 元素
        // 之后 Ruffle polyfill 会替换 <object> 为 Canvas 播放器
        if (isFlashPage) {
            view?.evaluateJavascript(FLASH_FAKE_SUPPORT_SCRIPT, null)
            view?.evaluateJavascript(RuffleInjector.configScript(), null)
            view?.evaluateJavascript(RuffleInjector.loaderScript(), null)
            view?.evaluateJavascript(FLASH_HIDE_SCRIPT, null)
            if (PrefsManager.flashEngine == "waflash") {
                view?.evaluateJavascript(WAFLASH_DETECT_SCRIPT, null)
            }
            // 注入 iframe 监控：游戏可能加载在 iframe 中
            view?.evaluateJavascript(IFRAME_INJECT_SCRIPT, null)
        }

        // 所有网页注入 viewport 缩放支持（不限 4399）
        if (url != null && !url.startsWith("file:///android_asset/") && !url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildViewportScript(), null)
        }
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        val isFlashPage = PrefsManager.isFlashEnabled && callback.shouldInjectRuffle(url)
        if (isFlashPage) {
            view?.evaluateJavascript(RuffleInjector.loaderScript(), null)
            view?.evaluateJavascript(FLASH_HIDE_SCRIPT, null)
            if (PrefsManager.flashEngine == "waflash") {
                view?.evaluateJavascript(WAFLASH_DETECT_SCRIPT, null)
            }
        }
        // 内置播放器页面：尽早注入画面比例
        if (url != null && url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildAspectRatioScript(PrefsManager.gameAspectRatio), null)
        }
        if (url != null && !url.startsWith("file:///android_asset/") && !url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildViewportScript(), null)
        }
        // 强制重绘兜底：页面首次可见时确保渲染帧已提交
        view?.invalidate()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val isFlashPage = PrefsManager.isFlashEnabled && callback.shouldInjectRuffle(url)
        if (isFlashPage) {
            view?.evaluateJavascript(RuffleInjector.fullInjection(), null)
            if (PrefsManager.flashEngine == "waflash") {
                view?.evaluateJavascript(WAFLASH_DETECT_SCRIPT, null)
            }
        }
        if (isFlashPage) {
            view?.evaluateJavascript(CSS_INJECTION, null)
        }
        // 内置播放器页面（player.html / waflash.html）：注入画面比例脚本
        if (url != null && url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildAspectRatioScript(PrefsManager.gameAspectRatio), null)
        }
        if (url != null && !url.startsWith("file:///android_asset/") && !url.startsWith("https://flash.local/")) {
            view?.evaluateJavascript(buildViewportScript(), null)
        }

        // 强制重绘：部分网页（如使用 View Transitions 的 SPA）加载完成后
        // 渲染管线未正确触发重绘，导致页面"卡住"（切后台再回来才显示）。
        // invalidate + requestLayout 强制 WebView 重新绘制当前帧。
        view?.invalidate()
        view?.requestLayout()
        // 延迟二次重绘：等待 evaluateJavascript 注入的脚本执行完毕后再触发一次
        view?.postDelayed({
            view?.invalidate()
            view?.evaluateJavascript(
                "try{void document.body&&document.body.offsetHeight;}catch(e){}", null
            )
        }, 300)

        callback.onPageFinished(url)
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        handler?.proceed()
    }

    /**
     * 新版错误回调（API 23+）：仅主框架错误才通知 callback。
     */
    override fun onReceivedError(
        view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
    ) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            callback.onError(
                request.url?.toString(),
                error?.errorCode ?: -1,
                error?.description?.toString()
            )
        }
    }

    /**
     * 废弃版错误回调：不触发 callback.onError，避免子资源错误误显示错误页。
     */
    @Deprecated("Deprecated in Java")
    override fun onReceivedError(
        view: WebView?, errorCode: Int, description: String?, failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
    }

    /** 根据用户缩放设置构建 viewport 脚本 */
    private fun buildViewportScript(): String {
        val scale = if (PrefsManager.pageZoomMode == "manual") {
            PrefsManager.pageZoomManual / 100.0
        } else {
            -1.0
        }
        return if (scale > 0) {
            """
            (function(){
              var meta = document.querySelector('meta[name="viewport"]');
              if (!meta) { meta = document.createElement('meta'); meta.name='viewport'; document.head.appendChild(meta); }
              var s = $scale;
              // minimum-scale=0.01 允许无限缩小，maximum-scale=10.0 是 WebView 允许的最大值
              meta.content = 'width=device-width, initial-scale=' + s + ', minimum-scale=0.01, maximum-scale=10.0, user-scalable=yes';
            })();
            """.trimIndent()
        } else {
            """
            (function(){
              var meta = document.querySelector('meta[name="viewport"]');
              if (!meta) { meta = document.createElement('meta'); meta.name='viewport'; document.head.appendChild(meta); }
              var sw = window.screen.width || 360;
              var scale = Math.min(1, sw / 1200);
              scale = Math.max(0.25, scale);
              // minimum-scale=0.01 允许无限缩小，maximum-scale=10.0 是 WebView 允许的最大值
              meta.content = 'width=device-width, initial-scale=' + scale + ', minimum-scale=0.01, maximum-scale=10.0, user-scalable=yes';
            })();
            """.trimIndent()
        }
    }

    companion object {

        /**
         * 按键安全钩子：在页面失焦（blur/visibilitychange）时自动释放所有按键。
         * 这是 Android 端 releaseAllKeys() 的补充保险——
         * 即使 Android 侧漏调，网页侧也能自行清理，防止 Ruffle 角色持续移动。
         */
        private const val KEY_SAFETY_SCRIPT = """
            (function(){
              if (window.__keySafetyHook) return;
              window.__keySafetyHook = true;
              // 追踪所有 keydown 的 keyCode
              var pressed = {};
              window.addEventListener('keydown', function(e){
                pressed[e.keyCode] = true;
              }, true);
              window.addEventListener('keyup', function(e){
                delete pressed[e.keyCode];
              }, true);
              // 释放所有已记录的按键
              function releaseAll() {
                Object.keys(pressed).forEach(function(kc) {
                  var code = parseInt(kc);
                  var keyMap = {
                    37:'ArrowLeft',38:'ArrowUp',39:'ArrowRight',40:'ArrowDown',
                    32:' ',13:'Enter',9:'Tab',27:'Escape',17:'Control',16:'Shift',18:'Alt',
                    65:'a',66:'b',67:'c',68:'d',69:'e',70:'f',71:'g',72:'h',73:'i',74:'j',
                    75:'k',76:'l',77:'m',78:'n',79:'o',80:'p',81:'q',82:'r',83:'s',84:'t',
                    85:'u',86:'v',87:'w',88:'x',89:'y',90:'z',
                    48:'0',49:'1',50:'2',51:'3',52:'4',53:'5',54:'6',55:'7',56:'8',57:'9'
                  };
                  var keyVal = keyMap[code] || '';
                  var codeMap = {
                    37:'ArrowLeft',38:'ArrowUp',39:'ArrowRight',40:'ArrowDown',
                    32:'Space',13:'Enter',9:'Tab',27:'Escape',
                    65:'KeyA',68:'KeyD',69:'KeyE',70:'KeyF',81:'KeyQ',82:'KeyR',
                    83:'KeyS',87:'KeyW',88:'KeyX',90:'KeyZ'
                  };
                  var codeVal = codeMap[code] || '';
                  try {
                    var evt = new KeyboardEvent('keyup', {
                      key: keyVal, code: codeVal, bubbles: true, cancelable: true
                    });
                    Object.defineProperty(evt, 'keyCode', {get: function(){return code;}});
                    Object.defineProperty(evt, 'which', {get: function(){return code;}});
                    document.dispatchEvent(evt);
                  } catch(e) {}
                  delete pressed[kc];
                });
              }
              window.addEventListener('blur', releaseAll);
              document.addEventListener('visibilitychange', function() {
                if (document.hidden) releaseAll();
              });
            })();
        """

        /**
         * View Transitions API 兼容性补丁（所有页面通用）。
         *
         * 问题：部分网站（如 gamefunbar.com）使用 document.startViewTransition() 实现
         *       SPA 风格的页面跳转。Android WebView 对该 API 的支持不完善，
         *       可能直接跳过过渡（Transition was skipped），导致：
         *       1. 回调函数未执行 → 页面无法跳转（核心 Bug）
         *       2. finished Promise 抛出未捕获的 AbortError → 控制台报错
         *
         * 方案：用 polyfill 替换 document.startViewTransition，确保回调始终同步执行，
         *       返回的 Promise 始终正常 resolve（不抛 AbortError）。
         *       代价是丢失过渡动画，但不影响功能与导航。
         */
        private const val VIEW_TRANSITION_PATCH_SCRIPT = """
            (function(){
              if (window.__vtPatch) return;
              window.__vtPatch = true;
              var vtPolyfill = function(callback) {
                var result;
                try {
                  result = callback ? callback() : undefined;
                } catch(e) {
                  result = Promise.reject(e);
                }
                var p = (result && typeof result.then === 'function') ? result : Promise.resolve();
                // 强制触发重绘：解决 polyfill 执行回调后 WebView 不重绘导致页面"卡住"
                requestAnimationFrame(function() {
                  try { void document.body && document.body.offsetHeight; } catch(e) {}
                });
                var finished = p.then(undefined, function(err) {
                  if (err && err.name === 'AbortError') return;
                  throw err;
                });
                return {
                  finished: finished,
                  ready: Promise.resolve(),
                  updateCallbackDone: p,
                  skipTransition: function() {},
                  types: []
                };
              };
              // 覆盖 Document.prototype（阻止原生 getter 返回原始实现）
              try {
                Object.defineProperty(Document.prototype, 'startViewTransition', {
                  value: vtPolyfill,
                  writable: true,
                  configurable: true
                });
              } catch(e) {}
              // 同时覆盖 document 实例（双重保险）
              try { document.startViewTransition = vtPolyfill; } catch(e) {}
              // 捕获 unhandledrejection：抑制 View Transitions 的 AbortError
              if (!window.__vtRejectionHook) {
                window.__vtRejectionHook = true;
                window.addEventListener('unhandledrejection', function(event) {
                  if (event.reason && event.reason.name === 'AbortError') {
                    var msg = event.reason.message || String(event.reason);
                    if (msg.indexOf('Transition') >= 0 || msg.indexOf('skipped') >= 0 || msg === 'AbortError') {
                      event.preventDefault();
                    }
                  }
                });
              }
            })();
        """

        /**
         * 伪造 Flash 插件支持：让 4399 等页面检测到浏览器"有 Flash 插件"。
         * 必须在页面 JS 执行前注入（onPageStarted）。
         * 伪造 navigator.plugins["Shockwave Flash"] 和 navigator.mimeTypes。
         */
        private const val FLASH_FAKE_SUPPORT_SCRIPT = """
            (function(){
              if (window.__flashFaked) return;
              window.__flashFaked = true;
              try {
                // 伪造 navigator.plugins（含 namedItem/item 方法，Ruffle 需要调用）
                var fakePlugin = {
                  name: 'Shockwave Flash',
                  filename: 'libflashplayer.so',
                  description: 'Shockwave Flash 32.0 r0',
                  length: 1,
                  0: { type: 'application/x-shockwave-flash', suffixes: 'swf', description: 'Shockwave Flash' }
                };
                fakePlugin.namedItem = function(n) { return (n === 'Shockwave Flash') ? fakePlugin : null; };
                fakePlugin.item = function(i) { return i === 0 ? fakePlugin : null; };
                fakePlugin.refresh = function() {};
                var plugins = navigator.plugins || {};
                if (plugins.namedItem) { fakePlugin.namedItem = function(n) { return (n === 'Shockwave Flash') ? fakePlugin : plugins.namedItem.call(plugins, n); }; }
                if (plugins.item) { fakePlugin.item = function(i) { return i === 0 ? fakePlugin : plugins.item.call(plugins, i); }; }
                Object.defineProperty(navigator, 'plugins', {
                  get: function() {
                    var p = plugins;
                    if (!p['Shockwave Flash']) {
                      try { p['Shockwave Flash'] = fakePlugin; p[0] = fakePlugin; } catch(e) {}
                    }
                    p.length = Math.max(p.length || 0, 1);
                    return p;
                  },
                  configurable: true
                });
                // 伪造 navigator.mimeTypes
                var fakeMime = { type: 'application/x-shockwave-flash', suffixes: 'swf', description: 'Shockwave Flash', enabledPlugin: fakePlugin };
                var mimes = navigator.mimeTypes || {};
                Object.defineProperty(navigator, 'mimeTypes', {
                  get: function() {
                    if (!mimes['application/x-shockwave-flash']) {
                      try { mimes['application/x-shockwave-flash'] = fakeMime; } catch(e) {}
                    }
                    return mimes;
                  },
                  configurable: true
                });
                // 伪造 ActiveXObject（IE 方式检测 Flash）
                window.ActiveXObject = function(name) {
                  if (name && /ShockwaveFlash/i.test(name)) return { SetVariable: function(){} };
                  throw new Error('Not supported');
                };
                console.log('[Flash] 已伪造 Flash 插件支持');
              } catch(e) { console.warn('[Flash] 伪造失败:', e); }
            })();
        """

        /**
         * iframe 注入监控：4399 游戏常加载在 iframe 中。
         * 监控 iframe 创建，将 Flash 引擎注入到 iframe 内部。
         */
        private const val IFRAME_INJECT_SCRIPT = """
            (function(){
              if (window.__iframeMonitor) return;
              window.__iframeMonitor = true;
              function injectIntoIframe(iframe) {
                try {
                  var doc = iframe.contentDocument || iframe.contentWindow.document;
                  if (!doc || doc.readyState === 'loading') {
                    setTimeout(function(){ injectIntoIframe(iframe); }, 200);
                    return;
                  }
                  // 检查是否已注入
                  if (doc.__flashFaked) return;
                  doc.__flashFaked = true;
                  // 注入 Flash 支持伪造
                  var s1 = doc.createElement('script');
                  s1.textContent = '(' + function(){
                    try {
                      var fp = {name:'Shockwave Flash',filename:'libflashplayer.so',description:'Shockwave Flash 32.0 r0',length:1,
                        0:{type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash'}};
                      fp.namedItem = function(n){ return (n === 'Shockwave Flash') ? fp : null; };
                      fp.item = function(i){ return i === 0 ? fp : null; };
                      fp.refresh = function(){};
                      Object.defineProperty(navigator,'plugins',{get:function(){return fp;},configurable:true});
                      Object.defineProperty(navigator,'mimeTypes',{get:function(){return {'application/x-shockwave-flash':{type:'application/x-shockwave-flash',suffixes:'swf',description:'Shockwave Flash',enabledPlugin:fp}};},configurable:true});
                      window.ActiveXObject = function(n){if(/ShockwaveFlash/i.test(n))return {SetVariable:function(){}};throw new Error('x');};
                    } catch(e){}
                  } + ')();';
                  doc.head.appendChild(s1);
                  // 注入 Ruffle 引擎
                  var engine = window.__ruffleLoaded ? 'ruffle' : (window.__swf2jsLoaded ? 'swf2js' : null);
                  if (engine === 'ruffle') {
                    var s2 = doc.createElement('script');
                    s2.src = 'https://flash.local/ruffle/ruffle.js';
                    s2.onload = function(){
                      try {
                        var r = doc.defaultView.RufflePlayer;
                        if (r && r.newest) { var inst = r.newest(); if (inst && inst.init) inst.init(); }
                      } catch(e){}
                    };
                    doc.head.appendChild(s2);
                  }
                  console.log('[Flash] 已注入 iframe');
                } catch(e) {
                  // 跨域 iframe 无法注入
                }
              }
              // 监控新创建的 iframe
              if (window.MutationObserver) {
                var observer = new MutationObserver(function(mutations){
                  mutations.forEach(function(m){
                    m.addedNodes.forEach(function(node){
                      if (node.tagName === 'IFRAME') injectIntoIframe(node);
                      if (node.querySelectorAll) {
                        var iframes = node.querySelectorAll('iframe');
                        for (var i = 0; i < iframes.length; i++) injectIntoIframe(iframes[i]);
                      }
                    });
                  });
                });
                observer.observe(document.documentElement, {childList: true, subtree: true});
              }
              // 检查已有的 iframe
              var existing = document.querySelectorAll('iframe');
              for (var i = 0; i < existing.length; i++) injectIntoIframe(existing[i]);
            })();
        """

        private const val REFERER_SPOOF_SCRIPT = """
            (function(){
              try {
                Object.defineProperty(document, 'referrer', {
                  get: function() { return 'https://www.4399.com/'; },
                  configurable: true
                });
              } catch(e) {}
            })();
        """

        /** IE 兼容模式伪造：4399 检测 IE 特有属性来判断兼容模式 */
        private const val IE_COMPAT_SCRIPT = """
            (function(){
              try {
                // 伪造 IE 的 documentMode（IE 独有属性）
                Object.defineProperty(document, 'documentMode', {
                  get: function() { return 11; }, configurable: true
                });
                // 伪造 IE 的 uniqueID
                Object.defineProperty(document, 'uniqueID', {
                  get: function() { return '_ie_id_'; }, configurable: true
                });
                // 伪造 IE 的 all 集合
                if (!document.all) {
                  document.all = document.getElementsByTagName('*');
                }
                // 伪装 navigator.userAgent 中含 Trident
                var origUA = navigator.userAgent;
                if (!/Trident/.test(origUA)) {
                  try {
                    Object.defineProperty(navigator, 'userAgent', {
                      get: function() {
                        return origUA + ' Trident/7.0; rv:11.0';
                      }, configurable: true
                    });
                  } catch(e) {}
                }
              } catch(e) {}
            })();
        """

        /**
         * 构建画面比例调整脚本。
         * - "auto": 恢复默认（铺满屏幕，contain）
         * - "4:3"/"16:9"/"3:2"/"1:1": 按指定比例居中显示，两侧/上下留黑边
         * - "stretch": 强制拉伸铺满
         *
         * 关键：Ruffle player 元素是 ruffle.js 异步加载后才创建的，
         * onPageFinished 时 #stage 里还没有 ruffle-player。
         * 因此用 MutationObserver 监听 #stage 子元素变化，
         * 一旦 ruffle-player 被添加就立即应用比例。
         * 同时用 CSS !important 规则确保 player.html 的内联样式不会覆盖。
         */
        fun buildAspectRatioScript(ratio: String): String {
            if (ratio == "auto" || ratio == "stretch") {
                val fill = ratio == "stretch"
                return """
                (function(){
                  var old = document.getElementById('__aspectRatioStyle');
                  if (old) old.remove();
                  var oldObs = window.__aspectRatioObserver;
                  if (oldObs) { oldObs.disconnect(); window.__aspectRatioObserver = null; }
                  var s = document.createElement('style');
                  s.id = '__aspectRatioStyle';
                  s.textContent = [
                    '#stage { display:flex !important; align-items:center !important; justify-content:center !important; width:100vw !important; height:100vh !important; overflow:hidden !important; }',
                    '#stage ruffle-player { width:100% !important; height:100% !important; max-width:100vw !important; max-height:100vh !important; object-fit:${if (fill) "fill" else "contain"} !important; }',
                    '#waflashContainer { width:100vw !important; height:100vh !important; display:flex !important; align-items:center !important; justify-content:center !important; }',
                    'canvas.waflashCanvas { max-width:100vw !important; max-height:100vh !important; object-fit:${if (fill) "fill" else "contain"} !important; }'
                  ].join('\n');
                  document.head.appendChild(s);
                  var els = document.querySelectorAll('#stage ruffle-player, canvas.waflashCanvas');
                  els.forEach(function(el){
                    el.style.width=''; el.style.height=''; el.style.maxWidth=''; el.style.maxHeight='';
                    el.style.margin=''; el.style.position=''; el.style.left=''; el.style.top=''; el.style.transform='';
                  });
                })();
                """.trimIndent()
            }
            // 解析比例
            val parts = ratio.split(":")
            if (parts.size != 2) return ""
            val w = parts[0].toFloatOrNull() ?: return ""
            val h = parts[1].toFloatOrNull() ?: return ""
            val ratioVal = w / h
            return """
            (function(){
              var ratio = $ratioVal;
              var old = document.getElementById('__aspectRatioStyle');
              if (old) old.remove();
              var oldObs = window.__aspectRatioObserver;
              if (oldObs) { oldObs.disconnect(); window.__aspectRatioObserver = null; }

              function applyRatio() {
                var sw = window.innerWidth, sh = window.innerHeight;
                var screenRatio = sw / sh;
                var targetW, targetH;
                if (screenRatio > ratio) {
                  targetH = sh; targetW = sh * ratio;
                } else {
                  targetW = sw; targetH = sw / ratio;
                }
                var els = document.querySelectorAll('#stage ruffle-player, canvas.waflashCanvas');
                els.forEach(function(el){
                  el.style.setProperty('width', targetW + 'px', 'important');
                  el.style.setProperty('height', targetH + 'px', 'important');
                  el.style.setProperty('max-width', targetW + 'px', 'important');
                  el.style.setProperty('max-height', targetH + 'px', 'important');
                  el.style.setProperty('margin', 'auto', 'important');
                });
              }

              var s = document.createElement('style');
              s.id = '__aspectRatioStyle';
              s.textContent = [
                '#stage { display:flex !important; align-items:center !important; justify-content:center !important; width:100vw !important; height:100vh !important; overflow:hidden !important; }',
                '#stage ruffle-player { object-fit:contain !important; }',
                '#waflashContainer { width:100vw !important; height:100vh !important; display:flex !important; align-items:center !important; justify-content:center !important; }',
                'canvas.waflashCanvas { object-fit:contain !important; }'
              ].join('\n');
              document.head.appendChild(s);

              applyRatio();

              // MutationObserver: 监听 #stage 子元素变化
              // Ruffle player 是异步创建的，onPageFinished 时还不存在
              var stage = document.getElementById('stage');
              if (stage && window.MutationObserver) {
                var obs = new MutationObserver(function(mutations){
                  applyRatio();
                });
                obs.observe(stage, { childList: true, subtree: true, attributes: true, attributeFilter: ['style','width','height'] });
                window.__aspectRatioObserver = obs;
              }

              window.addEventListener('resize', applyRatio);
              window.addEventListener('orientationchange', function(){ setTimeout(applyRatio, 200); });

              // 定时重试：ruffle.js 加载可能需要几秒，期间持续尝试应用比例
              var retries = 0;
              var timer = setInterval(function(){
                applyRatio();
                retries++;
                if (retries > 30) clearInterval(timer);
              }, 500);
            })();
            """.trimIndent()
        }

        private const val CSS_INJECTION = """
            (function(){
              if (window.__cssInjected) return; window.__cssInjected = true;
              var s = document.createElement('style');
              s.textContent = [
                '.advertise,.ad_box,[id^="ad_"],[class*="advert"]{display:none!important;}',
                'object,embed{max-width:100%!important;}'
              ].join('\n');
              document.head.appendChild(s);
            })();
        """

        private const val FLASH_HIDE_SCRIPT = """
            (function(){
              if (window.__flashHideInjected) return; window.__flashHideInjected = true;
              try {
                Object.defineProperty(navigator, 'plugins', {
                  get: function() {
                    var arr = [];
                    arr.namedItem = function(name) { return name === 'Shockwave Flash' ? { name: name } : null; };
                    arr.refresh = function() {};
                    arr.item = function(i) { return arr[i] || null; };
                    return arr;
                  },
                  configurable: true
                });
              } catch(e) {}
              function hideFlashTips() {
                var selectors = [
                  '[class*="noflash"]','[id*="noflash"]',
                  '[class*="no-flash"]','[id*="no-flash"]',
                  '.flash_tip','.flash-tip',
                  '#flash_tip','#flash-tip',
                  '.prompt-flash','.prompt_flash'
                ];
                document.querySelectorAll(selectors.join(',')).forEach(function(el){
                  el.style.display = 'none';
                });
              }
              hideFlashTips();
              if (window.MutationObserver) {
                var mo = new MutationObserver(function(){ hideFlashTips(); });
                try { mo.observe(document.documentElement, {childList:true, subtree:true}); } catch(e) {}
              }
            })();
        """

        /**
         * WAFlash SWF 检测脚本：
         * 1. Hook swfobject.embedSWF() — 标准 Flash 嵌入
         * 2. Hook AC_FL_RunContent() — 老式 Flash 嵌入
         * 3. Hook createFlash() — 4399 的 mflash-player API
         * 4. Hook fetch/XMLHttpRequest — 拦截非 .swf 扩展名的 SWF 加载
         * 5. MutationObserver 检测动态创建的 Flash DOM 元素
         */
        private const val WAFLASH_DETECT_SCRIPT = """
            (function(){
              if (window.__waflashDetect) return;
              window.__waflashDetect = true;
              var redirected = false;

              function redirectToPlayer(swfUrl, baseUrl) {
                if (redirected || !swfUrl) return;
                // 检测 SWF URL：支持 .swf 扩展名、4399 的 dw-XX 格式、flash 路径
                var isSwf = /\.swf/i.test(swfUrl) ||
                            /\/dw-\d+/i.test(swfUrl) ||
                            /flash\d*\//i.test(swfUrl) ||
                            /mm\.4399\.com/i.test(swfUrl);
                if (!isSwf) return;
                redirected = true;
                try { swfUrl = new URL(swfUrl, baseUrl || window.location.href).href; } catch(e) {}
                console.log('[WAFlash] 检测到 SWF: ' + swfUrl);
                if (window.Android && window.Android.openSwf) {
                  window.Android.openSwf(swfUrl, baseUrl || window.location.href);
                } else {
                  window.location.href = 'https://flash.local/waflash.html?swf=' + encodeURIComponent(swfUrl);
                }
              }

              // 1. Hook swfobject.embedSWF
              if (window.swfobject && window.swfobject.embedSWF) {
                var origEmbed = window.swfobject.embedSWF;
                window.swfobject.embedSWF = function() {
                  redirectToPlayer(arguments[0], window.location.href);
                  return origEmbed.apply(this, arguments);
                };
              } else {
                var _swfobject;
                Object.defineProperty(window, 'swfobject', {
                  configurable: true,
                  get: function() { return _swfobject; },
                  set: function(val) {
                    _swfobject = val;
                    if (val && val.embedSWF) {
                      var orig = val.embedSWF;
                      val.embedSWF = function() {
                        redirectToPlayer(arguments[0], window.location.href);
                        return orig.apply(this, arguments);
                      };
                    }
                  }
                });
              }

              // 2. Hook AC_FL_RunContent
              if (window.AC_FL_RunContent) {
                var origAC = window.AC_FL_RunContent;
                window.AC_FL_RunContent = function() {
                  var args = Array.prototype.slice.call(arguments);
                  for (var i = 0; i < args.length - 1; i++) {
                    if ((args[i] === 'src' || args[i] === 'movie') && args[i+1]) {
                      redirectToPlayer(args[i+1], window.location.href);
                    }
                  }
                  return origAC.apply(this, arguments);
                };
              }

              // 3. Hook createFlash — 4399 的 mflash-player API
              //    createFlash(swfUrl, options) 或 createFlash({url: swfUrl, ...})
              function hookCreateFlash(obj) {
                if (!obj || !obj.createFlash || obj.__waflashHooked) return;
                obj.__waflashHooked = true;
                var orig = obj.createFlash;
                obj.createFlash = function() {
                  var swfUrl = null;
                  if (typeof arguments[0] === 'string') {
                    swfUrl = arguments[0];
                  } else if (arguments[0] && typeof arguments[0] === 'object') {
                    swfUrl = arguments[0].url || arguments[0].src || arguments[0].swf ||
                             arguments[0].movie || arguments[0].data;
                  }
                  if (swfUrl) redirectToPlayer(swfUrl, window.location.href);
                  return orig.apply(this, arguments);
                };
                console.log('[WAFlash] 已 hook createFlash');
              }

              // 检查 mflash-player 是否已存在（注意：属性名含连字符，必须用方括号）
              if (window['mflash-player']) hookCreateFlash(window['mflash-player']);
              if (window.mflashplayer) hookCreateFlash(window.mflashplayer);
              if (window.MFlash) hookCreateFlash(window.MFlash);

              // 延迟 hook：4399 可能动态加载 mflash-player
              var _mflash;
              Object.defineProperty(window, 'mflash-player', {
                configurable: true,
                get: function() { return _mflash; },
                set: function(val) { _mflash = val; hookCreateFlash(val); }
              });
              var _mflash2;
              Object.defineProperty(window, 'mflashplayer', {
                configurable: true,
                get: function() { return _mflash2; },
                set: function(val) { _mflash2 = val; hookCreateFlash(val); }
              });

              // 4. 检测页面中已有的 Flash 元素
              function extractSwfUrl(el) {
                if (!el) return null;
                var src = el.getAttribute('data') || el.getAttribute('src') ||
                          el.getAttribute('movie') || el.getAttribute('url') || '';
                if (src) return src;
                var params = el.querySelectorAll('param[name="movie"], param[name="src"]');
                for (var i = 0; i < params.length; i++) {
                  var v = params[i].getAttribute('value') || '';
                  if (v) return v;
                }
                return null;
              }

              function checkExistingFlash() {
                if (redirected) return;
                var selectors = 'object[type="application/x-shockwave-flash"], ' +
                  'embed[type="application/x-shockwave-flash"], ' +
                  'object[data$=".swf" i], embed[src$=".swf" i], ' +
                  'object[classid*="D27CDB6E" i]';
                var elements = document.querySelectorAll(selectors);
                for (var i = 0; i < elements.length; i++) {
                  var swfUrl = extractSwfUrl(elements[i]);
                  if (swfUrl) { redirectToPlayer(swfUrl, window.location.href); return; }
                }
              }

              checkExistingFlash();

              // 5. MutationObserver 持续监控
              if (window.MutationObserver) {
                var observer = new MutationObserver(function() {
                  if (redirected) { observer.disconnect(); return; }
                  checkExistingFlash();
                });
                try {
                  observer.observe(document.documentElement || document.body || document, {
                    childList: true, subtree: true
                  });
                } catch(e) {}
                setTimeout(function() { observer.disconnect(); }, 15000);
              }
            })();
        """
    }
}

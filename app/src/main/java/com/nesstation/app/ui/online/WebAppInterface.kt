package com.nesstation.app.ui.online

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.net.Uri

/**
 * JavaScript interface injected into WebView as `window.Android`.
 *
 * Provides native capabilities to web pages:
 * - [openSwf] — redirect detected SWF to the built-in Flash player
 * - [readLocalSwf] — read local SWF file as Base64 (bypasses content:// CORS)
 * - [toast], [log], [vibrate], [finish] — utility methods
 *
 * @param context Activity or application context
 */
class WebAppInterface(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())

    /** Callback invoked when a SWF is detected on the page */
    @Volatile
    var onSwfDetected: ((String, String) -> Unit)? = null

    @JavascriptInterface
    fun toast(msg: String?) {
        handler.post { Toast.makeText(context, msg ?: "", Toast.LENGTH_SHORT).show() }
    }

    @JavascriptInterface
    fun vibrate(durationMs: Int) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMs.coerceIn(1, 500).toLong())
    }

    @JavascriptInterface
    fun log(tag: String?, msg: String?) {
        Log.d("WebApp:${tag ?: "JS"}", msg ?: "")
    }

    /**
     * Called by the WAFlash injection script when a SWF is detected on the page.
     * Redirects to the built-in Flash player (waflash.html or player.html).
     *
     * @param swfUrl  The SWF URL detected on the page
     * @param pageUrl The page URL where the SWF was found
     */
    @JavascriptInterface
    fun openSwf(swfUrl: String?, pageUrl: String?) {
        if (swfUrl.isNullOrEmpty()) return
        Log.d("WebApp:Flash", "openSwf: $swfUrl (from: $pageUrl)")
        handler.post {
            onSwfDetected?.let { it(swfUrl, pageUrl ?: "") }
        }
    }

    /**
     * Read a local SWF file (content:// or file:// URI) as Base64.
     * Used by JS to create a Blob URL, bypassing WebView's cross-origin restrictions.
     *
     * @param uri The content:// or file:// URI of the SWF file
     * @return Base64-encoded SWF data, or null on failure
     */
    @JavascriptInterface
    fun readLocalSwf(uri: String?): String? {
        if (uri.isNullOrEmpty()) return null
        return try {
            Log.d("WebApp:LocalSwf", "Reading: $uri")
            val parsed = Uri.parse(uri)
            val data = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
                ?: throw java.io.IOException("Cannot open file stream")
            Log.d("WebApp:LocalSwf", "Read complete: ${data.size} bytes")
            Base64.encodeToString(data, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("WebApp:LocalSwf", "Read failed: ${e.message}")
            null
        }
    }

    @JavascriptInterface
    fun finish() {
        if (context is Activity) handler.post { context.finish() }
    }
}

package com.nesstation.app.ui.swf

import android.content.ContentResolver
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * [WebViewClient] that intercepts requests targeting the virtual domain
 * `https://flash.local/` and serves them from the app's bundled assets or the
 * on-device SWF file.
 *
 * Routing:
 *  - `flash.local/local.swf`   -> the SWF file referenced by [swfFilePath]
 *  - `flash.local/player.html`  -> assets `player.html`
 *  - `flash.local/waflash.html` -> assets `waflash.html`
 *  - `flash.local/ruffle/...`   -> assets `ruffle/...`
 *  - `flash.local/waflash/...`  -> assets `waflash/...`
 *
 * Every served response carries `Access-Control-Allow-Origin: *` and
 * `Cache-Control: no-cache`. When an asset or file cannot be opened `null` is
 * returned so that the WebView falls back to its default handling.
 *
 * @param swfFilePath Path of the local SWF file to play. May be a plain
 *                    filesystem path, a `file://` URI or a `content://` URI.
 */
class FlashWebViewClient(private val swfFilePath: String) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        val url = request.url?.toString() ?: return null

        // Only handle our virtual flash.local domain; defer everything else.
        if (!url.contains("flash.local")) {
            return super.shouldInterceptRequest(view, request)
        }

        // Drop the host and any query string, leaving the asset-relative path
        val path = url.substringAfter("flash.local/").substringBefore("?")
        if (path.isEmpty()) {
            return null
        }

        return when {
            path == "local.swf" -> interceptLocalSwf(view)
            else -> interceptAsset(view, path)
        }
    }

    // -------------------------------------------------------------------------
    // Local SWF file
    // -------------------------------------------------------------------------

    private fun interceptLocalSwf(view: WebView): WebResourceResponse? {
        return try {
            val input = openSwfStream(view)
            WebResourceResponse(
                "application/x-shockwave-flash",
                null,
                200,
                "OK",
                corsHeaders(),
                input
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun openSwfStream(view: WebView): InputStream {
        return when {
            swfFilePath.startsWith("content://") -> {
                val resolver: ContentResolver = view.context.contentResolver
                resolver.openInputStream(Uri.parse(swfFilePath))
                    ?: throw FileNotFoundException(swfFilePath)
            }
            swfFilePath.startsWith("file://") -> {
                val resolved = Uri.parse(swfFilePath).path
                    ?: swfFilePath.removePrefix("file://")
                FileInputStream(File(resolved))
            }
            else -> FileInputStream(File(swfFilePath))
        }
    }

    // -------------------------------------------------------------------------
    // Assets
    // -------------------------------------------------------------------------

    private fun interceptAsset(view: WebView, assetPath: String): WebResourceResponse? {
        return try {
            val input = view.context.assets.open(assetPath)
            val (mime, charset) = mimeFor(assetPath)
            WebResourceResponse(
                mime,
                charset,
                200,
                "OK",
                corsHeaders(),
                input
            )
        } catch (_: Exception) {
            null
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun mimeFor(assetPath: String): Pair<String, String?> {
        return when {
            assetPath.endsWith(".wasm", ignoreCase = true) -> "application/wasm" to null
            assetPath.endsWith(".js", ignoreCase = true) -> "application/javascript" to "UTF-8"
            assetPath.endsWith(".html", ignoreCase = true) -> "text/html" to "UTF-8"
            assetPath.endsWith(".css", ignoreCase = true) -> "text/css" to "UTF-8"
            assetPath.endsWith(".ttf", ignoreCase = true) -> "font/ttf" to null
            assetPath.endsWith(".woff", ignoreCase = true) -> "font/woff" to null
            assetPath.endsWith(".woff2", ignoreCase = true) -> "font/woff2" to null
            assetPath.endsWith(".otf", ignoreCase = true) -> "font/otf" to null
            assetPath.endsWith(".data", ignoreCase = true) -> "application/octet-stream" to null
            assetPath.endsWith(".swf", ignoreCase = true) -> "application/x-shockwave-flash" to null
            else -> "application/octet-stream" to null
        }
    }

    private fun corsHeaders(): Map<String, String> = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "no-cache"
    )
}

package com.nesstation.app.flash.download

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

data class SwfDownloadItem(
    val url: String,
    val title: String,
    var progress: Int = 0,
    var status: DownloadStatus = DownloadStatus.PENDING,
    var filePath: String? = null,
    var retryCount: Int = 0
)

enum class DownloadStatus {
    PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELLED
}

class SwfDownloadManager(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val downloads = mutableListOf<SwfDownloadItem>()
    private var isDownloading = false
    private var progressListener: ((List<SwfDownloadItem>) -> Unit)? = null
    private var completeListener: ((List<SwfDownloadItem>) -> Unit)? = null
    private var currentThread: Thread? = null
    @Volatile private var isCancelled = false

    fun setProgressListener(l: (List<SwfDownloadItem>) -> Unit) { progressListener = l }
    fun setCompleteListener(l: (List<SwfDownloadItem>) -> Unit) { completeListener = l }

    fun startDownload(urls: List<Pair<String, String>>, pageUrl: String) {
        if (isDownloading) return
        isDownloading = true
        isCancelled = false
        downloads.clear()
        urls.forEach { (url, title) ->
            downloads.add(SwfDownloadItem(url = url, title = title))
        }
        notifyProgress()
        
        currentThread = Thread {
            for (item in downloads) {
                if (isCancelled) break
                downloadSingleFile(item, pageUrl)
            }
            isDownloading = false
            handler.post { completeListener?.invoke(downloads.toList()) }
        }.also { it.start() }
    }

    private fun downloadSingleFile(item: SwfDownloadItem, pageUrl: String) {
        val maxRetries = 3
        while (item.retryCount < maxRetries && !isCancelled) {
            try {
                item.status = DownloadStatus.DOWNLOADING
                notifyProgress()
                
                val swfUrlHttps = if (item.url.startsWith("http://")) "https://" + item.url.substring(7) else item.url
                val conn = URL(swfUrlHttps).openConnection() as HttpURLConnection
                
                if (conn is HttpsURLConnection) {
                    val tm = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                    }
                    val ctx = SSLContext.getInstance("TLS")
                    ctx.init(null, arrayOf(tm), java.security.SecureRandom())
                    conn.sslSocketFactory = ctx.socketFactory
                    conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                }
                
                conn.connectTimeout = 15000
                conn.readTimeout = 30000
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                conn.setRequestProperty("Accept", "*/*")
                conn.setRequestProperty("Accept-Encoding", "identity")
                
                try {
                    val cookies = CookieManager.getInstance().getCookie(swfUrlHttps)
                    if (!cookies.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookies)
                } catch(e: Exception) {}
                
                val referer = if (pageUrl.isNotEmpty() && pageUrl != item.url) pageUrl else {
                    try { Uri.parse(swfUrlHttps).let { "${it.scheme}://${it.host}/" } } catch(e: Exception) { item.url }
                }
                conn.setRequestProperty("Referer", referer)
                conn.connect()
                
                if (conn.responseCode !in 200..299) {
                    throw java.io.IOException("HTTP ${conn.responseCode}")
                }
                
                val totalBytes = conn.contentLength
                val filename = item.url.substringAfterLast("/").substringBefore("?").let {
                    if (it.endsWith(".swf", ignoreCase = true)) it else "$it.swf"
                }.ifBlank { "game.swf" }
                
                val dir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                val swfDir = File(dir, "GameBox")
                if (!swfDir.exists()) swfDir.mkdirs()
                val tmpFile = File(swfDir, "$filename.tmp")
                val finalFile = File(swfDir, filename)
                
                var totalRead = 0
                FileOutputStream(tmpFile).use { fos ->
                    conn.inputStream.use { input ->
                        val chunk = ByteArray(8192)
                        var bytesRead: Int
                        var lastPercent = -1
                        while (true) {
                            if (isCancelled || Thread.currentThread().isInterrupted) {
                                tmpFile.delete()
                                item.status = DownloadStatus.CANCELLED
                                notifyProgress()
                                conn.disconnect()
                                return
                            }
                            bytesRead = input.read(chunk)
                            if (bytesRead == -1) break
                            fos.write(chunk, 0, bytesRead)
                            totalRead += bytesRead
                            if (totalBytes > 0) {
                                val percent = (totalRead * 100 / totalBytes)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    item.progress = percent
                                    notifyProgress()
                                }
                            } else {
                                item.progress = -1  // indeterminate
                                if (totalRead % 81920 == 0) notifyProgress()
                            }
                        }
                    }
                }
                
                if (totalRead == 0) throw java.io.IOException("文件为空")
                
                tmpFile.renameTo(finalFile)
                item.filePath = finalFile.absolutePath
                item.status = DownloadStatus.COMPLETED
                item.progress = 100
                notifyProgress()
                conn.disconnect()
                return  // Success, exit retry loop
            } catch (e: Exception) {
                Log.w("SwfDownload", "下载失败(${item.retryCount + 1}/$maxRetries): ${item.url} - ${e.message}")
                item.retryCount++
                if (item.retryCount >= maxRetries) {
                    item.status = DownloadStatus.FAILED
                    notifyProgress()
                    return
                }
                // Wait before retry
                Thread.sleep(2000L * item.retryCount)
            }
        }
    }

    fun cancel() {
        isCancelled = true
        currentThread?.interrupt()
        downloads.forEach { if (it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.PENDING) it.status = DownloadStatus.CANCELLED }
        notifyProgress()
    }

    val isCurrentlyDownloading: Boolean get() = isDownloading
    val currentDownloads: List<SwfDownloadItem> get() = downloads.toList()

    private fun notifyProgress() {
        val snapshot = downloads.toList()
        handler.post { progressListener?.invoke(snapshot) }
    }
}

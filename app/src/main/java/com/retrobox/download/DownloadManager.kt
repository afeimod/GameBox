package com.retrobox.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** 下载任务状态 */
enum class DownloadStatus {
    /** 等待中 */
    PENDING,
    /** 下载中 */
    RUNNING,
    /** 已暂停 */
    PAUSED,
    /** 已完成 */
    COMPLETED,
    /** 失败 */
    FAILED,
    /** 已取消 */
    CANCELED
}

/**
 * 下载任务信息
 */
data class DownloadTask(
    val id: String,
    val url: String,
    val destPath: String,
    val fileName: String,
    var totalSize: Long = 0L,
    var downloadedSize: Long = 0L,
    var status: DownloadStatus = DownloadStatus.PENDING
) {
    /** 进度百分比（0-100），未知总大小时返回 -1 */
    val progress: Int
        get() = if (totalSize > 0) ((downloadedSize * 100) / totalSize).toInt().coerceIn(0, 100) else -1
}

/** 下载回调 */
interface DownloadCallback {
    /** 进度更新 */
    fun onProgress(task: DownloadTask) {}
    /** 下载完成 */
    fun onComplete(task: DownloadTask) {}
    /** 下载出错 */
    fun onError(task: DownloadTask, errorMsg: String) {}
    /** 暂停 */
    fun onPause(task: DownloadTask) {}
    /** 恢复 */
    fun onResume(task: DownloadTask) {}
}

/**
 * 下载管理器
 *
 * 功能：
 * - 支持多任务下载（通过信号量限制最大并发数）
 * - 下载进度回调
 * - 断点续传（基于 HTTP Range，使用 [RandomAccessFile] 追加写入）
 * - 下载队列管理
 * - 通知栏进度通知
 *
 * @param context        上下文
 * @param maxConcurrent  最大并发下载数
 * @param client         可自定义 OkHttp 客户端
 */
class DownloadManager(
    private val context: Context,
    private val maxConcurrent: Int = 3,
    client: OkHttpClient? = null
) {

    private val okHttpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    // 任务表：id -> 任务
    private val tasks = ConcurrentHashMap<String, DownloadTask>()
    // 任务协程：id -> Job
    private val jobs = ConcurrentHashMap<String, Job>()
    // 回调列表
    private val callbacks = mutableListOf<DownloadCallback>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val semaphore = Semaphore(maxConcurrent)

    // 待执行队列（PENDING 状态的任务 id）
    private val pendingQueue = ArrayDeque<String>()

    // 通知栏管理器（必须在 init 块之前初始化，否则 NPE）
    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    /** 注册下载回调 */
    fun addCallback(callback: DownloadCallback) {
        synchronized(callbacks) { callbacks.add(callback) }
    }

    /** 移除下载回调 */
    fun removeCallback(callback: DownloadCallback) {
        synchronized(callbacks) { callbacks.remove(callback) }
    }

    private fun notifyCallback(block: (DownloadCallback) -> Unit) {
        val list = synchronized(callbacks) { callbacks.toList() }
        list.forEach(block)
    }

    /**
     * 新建并加入下载队列
     *
     * @param id        任务 ID（唯一），为空时自动生成
     * @param url       下载地址
     * @param destPath  本地保存路径
     * @param fileName  显示用文件名
     * @return 任务对象
     */
    @Synchronized
    fun enqueue(id: String = System.currentTimeMillis().toString(), url: String, destPath: String, fileName: String): DownloadTask {
        val task = DownloadTask(id = id, url = url, destPath = destPath, fileName = fileName)
        tasks[id] = task
        pendingQueue.addLast(id)
        scheduleNext()
        return task
    }

    /** 调度下一个待执行任务 */
    private fun scheduleNext() {
        val nextId = synchronized(pendingQueue) {
            // 跳过已不在 PENDING 的任务
            while (pendingQueue.isNotEmpty()) {
                val candidate = pendingQueue.removeFirst()
                val t = tasks[candidate]
                if (t != null && t.status == DownloadStatus.PENDING) {
                    return@synchronized candidate
                }
            }
            null
        } ?: return

        val task = tasks[nextId] ?: return
        val job = scope.launch {
            semaphore.withPermit {
                executeDownload(task)
            }
        }
        jobs[nextId] = job
    }

    /** 执行单个下载任务（支持断点续传） */
    private suspend fun executeDownload(task: DownloadTask) {
        try {
            task.status = DownloadStatus.RUNNING
            notifyCallback { it.onResume(task) }

            val destFile = File(task.destPath)
            destFile.parentFile?.mkdirs()

            // 断点续传：已存在部分文件时从断点继续
            val existing = if (destFile.exists()) destFile.length() else 0L
            task.downloadedSize = existing

            val request = Request.Builder().url(task.url).apply {
                if (existing > 0) {
                    addHeader("Range", "bytes=$existing-")
                }
            }.build()

            val response = okHttpClient.newCall(request).execute()
            // 206 = 支持断点续传；200 = 服务器忽略 Range，需从头下载
            val supportResume = response.code == 206
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}")
            }
            val body = response.body ?: throw RuntimeException("响应体为空")
            val contentLength = body.contentLength()
            task.totalSize = when {
                supportResume && contentLength > 0 -> contentLength + existing
                contentLength > 0 -> contentLength
                else -> task.totalSize
            }

            // 若服务器忽略 Range，则从头写入
            val startOffset = if (supportResume) existing else 0L
            if (!supportResume) {
                task.downloadedSize = 0L
            }

            val raf = RandomAccessFile(destFile, "rw")
            try {
                raf.seek(startOffset)
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    while (task.status == DownloadStatus.RUNNING) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        raf.write(buffer, 0, read)
                        task.downloadedSize += read
                        notifyCallback { it.onProgress(task) }
                        updateNotification(task)
                    }
                }
            } finally {
                raf.close()
            }

            when (task.status) {
                DownloadStatus.RUNNING -> {
                    task.status = DownloadStatus.COMPLETED
                    notifyCallback { it.onComplete(task) }
                    cancelNotification(task)
                }
                DownloadStatus.PAUSED -> {
                    notifyCallback { it.onPause(task) }
                }
                DownloadStatus.CANCELED -> {
                    // 删除已下载的部分文件
                    destFile.delete()
                    cancelNotification(task)
                }
                else -> {
                    // 其它状态不处理
                }
            }
        } catch (e: Exception) {
            if (task.status != DownloadStatus.CANCELED && task.status != DownloadStatus.PAUSED) {
                task.status = DownloadStatus.FAILED
                notifyCallback { it.onError(task, e.message ?: "下载失败") }
            }
        } finally {
            jobs.remove(task.id)
            // 队列中还有任务则继续调度
            scheduleNext()
        }
    }

    /** 暂停任务 */
    fun pause(id: String) {
        val task = tasks[id] ?: return
        if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.PENDING) {
            task.status = DownloadStatus.PAUSED
            // 取消协程（已写入部分文件保留，便于续传）
            jobs[id]?.cancel()
            jobs.remove(id)
            notifyCallback { it.onPause(task) }
        }
    }

    /** 恢复任务 */
    fun resume(id: String) {
        val task = tasks[id] ?: return
        if (task.status == DownloadStatus.PAUSED || task.status == DownloadStatus.FAILED) {
            task.status = DownloadStatus.PENDING
            synchronized(pendingQueue) { pendingQueue.addLast(id) }
            scheduleNext()
        }
    }

    /** 取消并删除任务 */
    fun cancel(id: String) {
        val task = tasks[id] ?: return
        task.status = DownloadStatus.CANCELED
        jobs[id]?.cancel()
        jobs.remove(id)
        synchronized(pendingQueue) { pendingQueue.remove(id) }
        cancelNotification(task)
    }

    /** 移除任务记录（不删除文件） */
    fun remove(id: String) {
        cancel(id)
        tasks.remove(id)
    }

    /** 获取任务 */
    fun getTask(id: String): DownloadTask? = tasks[id]

    /** 获取全部任务 */
    fun getAllTasks(): List<DownloadTask> = tasks.values.toList()

    /** 关闭管理器，取消所有任务 */
    fun shutdown() {
        for (id in tasks.keys.toList()) {
            cancel(id)
        }
        scope.coroutineContext[Job]?.cancel()
    }

    // ===== 通知栏 =====

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "游戏下载",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ROM 下载进度通知"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(task: DownloadTask) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("正在下载: ${task.fileName}")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (task.progress >= 0) {
            builder.setProgress(100, task.progress, false)
                .setContentText("${task.progress}%")
        } else {
            builder.setProgress(0, 0, true)
                .setContentText("下载中…")
        }
        notificationManager.notify(task.id.hashCode(), builder.build())
    }

    private fun cancelNotification(task: DownloadTask) {
        try {
            notificationManager.cancel(task.id.hashCode())
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val CHANNEL_ID = "retrobox_download_channel"
    }
}

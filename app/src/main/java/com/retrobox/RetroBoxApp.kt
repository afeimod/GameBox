package com.retrobox

import android.app.Application
import android.util.Log
import com.retrobox.data.GameRepository
import com.retrobox.data.PreferenceManager
import com.retrobox.download.DownloadManager
import com.retrobox.emulator.CoreManager

/**
 * RetroBox 全局 Application
 *
 * 持有全局单例：偏好设置、游戏仓库、核心管理器、下载管理器。
 */
class RetroBoxApp : Application() {

    val preferenceManager: PreferenceManager by lazy { PreferenceManager(this) }
    val gameRepository: GameRepository by lazy { GameRepository(this) }
    val coreManager: CoreManager by lazy { CoreManager() }
    val downloadManager: DownloadManager by lazy { DownloadManager(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 全局未捕获异常处理器：记录崩溃日志
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "RetroBoxApp"

        @Volatile
        lateinit var instance: RetroBoxApp
            private set
    }
}

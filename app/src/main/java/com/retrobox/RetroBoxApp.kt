package com.retrobox

import android.app.Application
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
    }

    companion object {
        @Volatile
        lateinit var instance: RetroBoxApp
            private set
    }
}

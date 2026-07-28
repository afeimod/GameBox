package com.retrobox.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.retrobox.RetroBoxApp
import com.retrobox.data.GameInfo
import com.retrobox.data.GameRepository
import com.retrobox.data.Platform
import com.retrobox.data.PreferenceManager
import com.retrobox.download.DownloadManager
import com.retrobox.download.DownloadTask
import com.retrobox.download.GiteeClient
import com.retrobox.download.GameDownloadInfo
import com.retrobox.download.GameListParser
import com.retrobox.download.GamePlatform
import com.retrobox.emulator.CoreManager
import com.retrobox.emulator.EmulatorCore
import com.retrobox.emulator.EmulatorThread
import com.retrobox.input.ButtonLayout
import com.retrobox.input.GamepadConfig
import com.retrobox.ui.components.GamepadPreset
import com.retrobox.ui.components.GamepadTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 主 ViewModel，管理游戏库、下载、模拟器运行和手柄配置等全局状态。
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs: PreferenceManager = try {
        (app as RetroBoxApp).preferenceManager
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get PreferenceManager", e)
        PreferenceManager(app)
    }

    private val repo: GameRepository = try {
        (app as RetroBoxApp).gameRepository
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get GameRepository", e)
        GameRepository(app)
    }

    private val coreManager: CoreManager = try {
        (app as RetroBoxApp).coreManager
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get CoreManager", e)
        CoreManager()
    }

    private val downloadManager: DownloadManager = try {
        (app as RetroBoxApp).downloadManager
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get DownloadManager", e)
        DownloadManager(app)
    }

    // ===== 游戏库 =====
    private val _games = MutableStateFlow<List<GameInfo>>(emptyList())
    val games: StateFlow<List<GameInfo>> = _games.asStateFlow()

    private val _selectedPlatform = MutableStateFlow<Platform?>(null)
    val selectedPlatform: StateFlow<Platform?> = _selectedPlatform.asStateFlow()

    // ===== 下载列表 =====
    private val _downloadList = MutableStateFlow<List<GameDownloadInfo>>(emptyList())
    val downloadList: StateFlow<List<GameDownloadInfo>> = _downloadList.asStateFlow()

    private val _downloadTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val downloadTasks: StateFlow<List<DownloadTask>> = _downloadTasks.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadMessage = MutableStateFlow<String?>(null)
    val downloadMessage: StateFlow<String?> = _downloadMessage.asStateFlow()

    // ===== 模拟器 =====
    private val _emulatorStatus = MutableStateFlow("空闲")
    val emulatorStatus: StateFlow<String> = _emulatorStatus.asStateFlow()

    private var emulatorThread: EmulatorThread? = null
    private var currentGame: GameInfo? = null

    // ===== 手柄配置 =====
    private val _gamepadConfig = MutableStateFlow(loadGamepadConfig())
    val gamepadConfig: StateFlow<GamepadConfig> = _gamepadConfig.asStateFlow()

    private val _gamepadPreset = MutableStateFlow(loadGamepadPreset())
    val gamepadPreset: StateFlow<GamepadPreset> = _gamepadPreset.asStateFlow()

    val gamepadTheme: GamepadTheme
        get() = GamepadTheme.fromPreset(_gamepadPreset.value)

    // ===== 初始化 =====

    init {
        refreshGames()
    }

    /** 刷新本地游戏库 */
    fun refreshGames() {
        viewModelScope.launch(Dispatchers.IO) {
            // 扫描下载目录
            val dir = File(prefs.downloadPath)
            if (dir.exists()) {
                repo.scanRoms(dir)
            }
            _games.value = if (_selectedPlatform.value != null) {
                repo.getGamesByPlatform(_selectedPlatform.value!!)
            } else {
                repo.getAllGames()
            }
        }
    }

    /** 按平台筛选 */
    fun filterByPlatform(platform: Platform?) {
        _selectedPlatform.value = platform
        refreshGames()
    }

    /** 搜索游戏 */
    fun searchGames(keyword: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _games.value = if (keyword.isBlank()) {
                repo.getAllGames()
            } else {
                repo.searchByName(keyword)
            }
        }
    }

    // ===== 导入本地 ROM =====

    /**
     * 导入本地 ROM 文件（通过系统文件选择器选取的 Uri）
     *
     * 将选中的文件复制到 ROM 目录下对应平台子目录，并入库。
     *
     * @param uri       文件 Uri（由 SAF 返回）
     * @param fileName  原始文件名（用于保留扩展名）
     * @param onResult  回调：成功返回 GameInfo，失败返回 null + 错误信息
     */
    fun importLocalRom(uri: android.net.Uri, fileName: String, onResult: (GameInfo?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                val platform = Platform.fromExtension(ext)
                if (platform == null) {
                    _downloadMessage.value = "不支持的文件格式: .$ext"
                    withContext(Dispatchers.Main) { onResult(null) }
                    return@launch
                }

                // 目标目录: ROM根目录/平台名/
                val destDir = File(prefs.downloadPath, platform.displayName.lowercase())
                destDir.mkdirs()
                val destFile = File(destDir, fileName)

                // 复制文件
                val resolver = getApplication<RetroBoxApp>().contentResolver
                resolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: run {
                    _downloadMessage.value = "无法读取文件"
                    withContext(Dispatchers.Main) { onResult(null) }
                    return@launch
                }

                // 扫描入库
                val added = repo.scanRoms(destDir)
                _games.value = if (_selectedPlatform.value != null) {
                    repo.getGamesByPlatform(_selectedPlatform.value!!)
                } else {
                    repo.getAllGames()
                }

                val imported = added.firstOrNull { it.romPath == destFile.absolutePath }
                _downloadMessage.value = if (imported != null) {
                    "导入成功: ${imported.name}"
                } else {
                    "文件已添加: ${destFile.name}"
                }
                withContext(Dispatchers.Main) { onResult(imported) }
            } catch (e: Exception) {
                Log.e(TAG, "importLocalRom failed", e)
                _downloadMessage.value = "导入失败: ${e.message}"
                withContext(Dispatchers.Main) { onResult(null) }
            }
        }
    }

    /**
     * 扫描自定义目录（用户通过 SAF 选择的目录 Uri）
     *
     * @param treeUri  目录 Uri（由 SAF 返回的 DocumentsContract tree Uri）
     * @param onResult 回调：新增游戏数量
     */
    fun scanCustomDirectory(treeUri: android.net.Uri, onResult: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resolver = getApplication<RetroBoxApp>().contentResolver
                val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)

                val addedCount = mutableListOf<GameInfo>()
                resolver.query(childrenUri, arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
                ), null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0)
                        val name = cursor.getString(1)
                        val ext = name.substringAfterLast('.', "").lowercase()
                        val platform = Platform.fromExtension(ext) ?: continue

                        // 复制到 ROM 目录
                        val destDir = File(prefs.downloadPath, platform.displayName.lowercase())
                        destDir.mkdirs()
                        val destFile = File(destDir, name)

                        val fileUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        resolver.openInputStream(fileUri)?.use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                // 统一扫描入库
                val romRoot = File(prefs.downloadPath)
                if (romRoot.exists()) {
                    repo.scanRoms(romRoot)
                }
                _games.value = if (_selectedPlatform.value != null) {
                    repo.getGamesByPlatform(_selectedPlatform.value!!)
                } else {
                    repo.getAllGames()
                }

                val count = _games.value.size
                _downloadMessage.value = "扫描完成，共 ${count} 个游戏"
                withContext(Dispatchers.Main) { onResult(count) }
            } catch (e: Exception) {
                Log.e(TAG, "scanCustomDirectory failed", e)
                _downloadMessage.value = "扫描失败: ${e.message}"
                withContext(Dispatchers.Main) { onResult(0) }
            }
        }
    }

    // ===== 模拟器运行 =====

    /** 启动游戏 */
    fun startGame(game: GameInfo, onReady: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            currentGame = game
            _emulatorStatus.value = "加载中…"

            val core = coreManager.loadCore(game.romPath)
            if (core == null) {
                _emulatorStatus.value = "加载失败：不支持该格式"
                return@launch
            }

            repo.incrementPlayCount(game.id)
            _emulatorStatus.value = "运行中"
            withContext(Dispatchers.Main) { onReady() }
        }
    }

    /** 获取当前活跃核心 */
    fun getActiveCore(): EmulatorCore? = coreManager.getActiveCore()

    /** 设置模拟器线程 */
    fun setEmulatorThread(thread: EmulatorThread?) {
        emulatorThread = thread
    }

    /** 暂停 */
    fun pauseEmulator() {
        emulatorThread?.pauseEmulator()
        _emulatorStatus.value = "已暂停"
    }

    /** 恢复 */
    fun resumeEmulator() {
        emulatorThread?.resumeEmulator()
        _emulatorStatus.value = "运行中"
    }

    /** 停止 */
    fun stopEmulator() {
        emulatorThread?.stopEmulator()
        emulatorThread = null
        coreManager.releaseActiveCore()
        _emulatorStatus.value = "已停止"
    }

    /** 保存存档 */
    fun saveState(slot: Int): Boolean {
        val core = coreManager.getActiveCore() ?: return false
        return core.saveState(slot)
    }

    /** 读取存档 */
    fun loadState(slot: Int): Boolean {
        val core = coreManager.getActiveCore() ?: return false
        return core.loadState(slot)
    }

    // ===== 在线下载 =====

    /** 创建 Gitee 客户端 */
    private fun createGiteeClient(): GiteeClient? {
        val owner = prefs.giteeOwner
        val repoName = prefs.giteeRepo
        if (owner.isBlank() || repoName.isBlank()) return null
        return GiteeClient(
            owner = owner,
            repo = repoName,
            branch = prefs.giteeBranch,
            token = prefs.giteeToken.ifBlank { null }
        )
    }

    /** 从 Gitee 拉取游戏列表 */
    fun fetchGameList(platform: GamePlatform? = null) {
        val client = createGiteeClient()
        if (client == null) {
            _downloadMessage.value = "请先在设置中配置 Gitee 仓库信息"
            return
        }

        _isDownloading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 尝试获取游戏列表 JSON 文件
                val listPath = if (platform != null) {
                    "${platform.display.lowercase()}/gamelist.json"
                } else {
                    "gamelist.json"
                }

                // 先尝试获取 gamelist.json
                val contents = client.listAllFiles("")
                val jsonFile = contents.find { it.name == "gamelist.json" }

                if (jsonFile != null) {
                    // 下载并解析 JSON
                    val tempFile = File.createTempFile("gamelist", ".json")
                    client.downloadFile(jsonFile.path, tempFile.absolutePath)
                    val json = tempFile.readText()
                    tempFile.delete()

                    var list = GameListParser.parseList(json)
                    if (platform != null) {
                        list = list.filter { it.platform == platform }
                    }
                    _downloadList.value = list
                    _downloadMessage.value = "获取到 ${list.size} 个游戏"
                } else {
                    // 没有 JSON 列表文件，直接扫描目录结构
                    val romDirs = contents.filter { it.type == "dir" }
                    val gameList = mutableListOf<GameDownloadInfo>()

                    for (dir in romDirs) {
                        val plat = GamePlatform.fromString(dir.name)
                        if (platform != null && plat != platform) continue

                        val files = client.listAllFiles(dir.path)
                        for (file in files) {
                            if (file.type != "file") continue
                            val ext = file.name.substringAfterLast('.', "").lowercase()
                            if (ext !in listOf("nes", "fds", "smc", "sfc", "md", "gen", "smd", "bin", "zip", "7z")) continue

                            gameList.add(
                                GameDownloadInfo(
                                    name = file.name.substringBeforeLast('.'),
                                    platform = plat,
                                    fileSize = file.size,
                                    downloadUrl = client.buildRawUrl(file.path),
                                    coverUrl = "",
                                    description = "",
                                    romUrl = file.path
                                )
                            )
                        }
                    }
                    _downloadList.value = gameList
                    _downloadMessage.value = "扫描到 ${gameList.size} 个游戏"
                }
            } catch (e: Exception) {
                _downloadMessage.value = "获取失败: ${e.message}"
            } finally {
                _isDownloading.value = false
            }
        }
    }

    /** 下载游戏 */
    fun downloadGame(game: GameDownloadInfo) {
        val destDir = File(prefs.downloadPath, game.platform.display.lowercase())
        destDir.mkdirs()
        val fileName = game.name + "." + (game.romUrl.substringAfterLast('.', "nes"))
        val destPath = File(destDir, fileName).absolutePath

        val task = downloadManager.enqueue(
            url = game.downloadUrl,
            destPath = destPath,
            fileName = fileName
        )
        _downloadTasks.value = downloadManager.getAllTasks()
    }

    /** 刷新下载任务列表 */
    fun refreshDownloadTasks() {
        _downloadTasks.value = downloadManager.getAllTasks()
    }

    /** 暂停下载 */
    fun pauseDownload(taskId: String) {
        downloadManager.pause(taskId)
        refreshDownloadTasks()
    }

    /** 恢复下载 */
    fun resumeDownload(taskId: String) {
        downloadManager.resume(taskId)
        refreshDownloadTasks()
    }

    /** 取消下载 */
    fun cancelDownload(taskId: String) {
        downloadManager.cancel(taskId)
        refreshDownloadTasks()
    }

    /** 搜索在线游戏 */
    fun searchOnlineGames(keyword: String) {
        val current = _downloadList.value
        if (keyword.isBlank()) {
            fetchGameList()
        } else {
            _downloadList.value = GameListParser.search(current, keyword)
        }
    }

    // ===== Gitee 配置 =====

    fun saveGiteeConfig(owner: String, repo: String, branch: String, token: String) {
        prefs.giteeOwner = owner
        prefs.giteeRepo = repo
        prefs.giteeBranch = branch
        prefs.giteeToken = token
    }

    fun getGiteeConfig(): GiteeConfig = GiteeConfig(
        owner = prefs.giteeOwner,
        repo = prefs.giteeRepo,
        branch = prefs.giteeBranch,
        token = prefs.giteeToken
    )

    data class GiteeConfig(
        val owner: String,
        val repo: String,
        val branch: String,
        val token: String
    )

    // ===== 手柄配置 =====

    fun updateGamepadConfig(config: GamepadConfig) {
        _gamepadConfig.value = config
        prefs.putString(KEY_GAMEPAD_CONFIG, config.toJsonString())
    }

    fun updateGamepadPreset(preset: GamepadPreset) {
        _gamepadPreset.value = preset
        prefs.putString(KEY_GAMEPAD_PRESET, preset.name)
    }

    private fun loadGamepadConfig(): GamepadConfig {
        val json = prefs.getString(KEY_GAMEPAD_CONFIG, "")
        return if (json.isNotBlank()) {
            try { GamepadConfig.fromJsonString(json) } catch (_: Exception) { GamepadConfig.default() }
        } else {
            GamepadConfig.default()
        }
    }

    private fun loadGamepadPreset(): GamepadPreset {
        val name = prefs.getString(KEY_GAMEPAD_PRESET, GamepadPreset.NEON_CYBER.name)
        return runCatching { GamepadPreset.valueOf(name) }.getOrDefault(GamepadPreset.NEON_CYBER)
    }

    // ===== 运行时设置 =====

    var volume: Int
        get() = prefs.volume
        set(value) { prefs.volume = value }

    var targetFps: Int
        get() = prefs.targetFps
        set(value) { prefs.targetFps = value }

    var keepScreenOn: Boolean
        get() = prefs.keepScreenOn
        set(value) { prefs.keepScreenOn = value }

    var showFps: Boolean
        get() = prefs.showFps
        set(value) { prefs.showFps = value }

    var downloadPath: String
        get() = prefs.downloadPath
        set(value) { prefs.downloadPath = value }

    // ===== 常量 =====

    companion object {
        private const val TAG = "GameViewModel"
        private const val KEY_GAMEPAD_CONFIG = "gamepad_config_json"
        private const val KEY_GAMEPAD_PRESET = "gamepad_preset"
    }
}

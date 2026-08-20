package com.nesstation.app.ui.battle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nesstation.app.battle.BattleSession
import com.nesstation.app.battle.NetplayController
import com.nesstation.app.core.model.GameEntry
import com.nesstation.app.ui.emulator.EmulatorScreen
import java.io.File

private val PrimaryText = Color(0xFF1E2A3A)
private val SecondaryText = Color(0xFF4A5568)
private val DeleteColor = Color(0xFFE74C3C)

/**
 * 解析 "host:port" 格式的中继 TCP 地址。
 */
private fun parseTcpAddr(addr: String): Pair<String, Int> {
    val trimmed = addr.trim()
    if (trimmed.isEmpty()) return "127.0.0.1" to 1909
    val idx = trimmed.lastIndexOf(':')
    if (idx <= 0 || idx == trimmed.length - 1) return trimmed to 1909
    val host = trimmed.substring(0, idx)
    val port = trimmed.substring(idx + 1).toIntOrNull() ?: 1909
    return host to port
}

/**
 * 对战界面 —— 和本地游戏走完全相同的启动路径。
 *
 * ## 改造前（问题）
 *
 * 旧的 BattleMatchScreen 自己重新绘制了一份独立的 SurfaceView + OnScreenController
 * + NetplayEngine + 加载流程，导致：
 *  - 启动慢：ROM 加载、BIOS 检查、视频滤镜应用、core option 设置、surface attach
 *    全部走的是独立路径，没有复用本地游戏的代码。
 *  - 位置不对：独立 GameSurface 用的是单独的 BoxWithConstraints + aspectRatio
 *    逻辑，没有复用 EmulatorScreen 的 GameSurfaceView（含 custom 布局 / 横竖屏 /
 *    边距 / 状态栏 / 焦点处理），位置和大小都和本地游戏不一致。
 *
 * ## 改造后（修复）
 *
 * 这个 Composable 现在是一个**薄壳**：
 *   1. 下载 ROM（如果 BattleScreen 还没下完 —— 通常已经下好了）。
 *   2. 连接中继服务器。
 *   3. 构造一个指向 battle_rom 的 [GameEntry]，调起 [EmulatorScreen] ——
 *      所有 ROM 加载、surface、按键、菜单、存档、布局编辑器……全部走本地游戏
 *      的既有逻辑。**唯一区别**是给 [EmulatorScreen] 多传了一个 [NetplayController]，
 *      它会作为 [com.nesstation.app.core.engine.EmulatorEngine.frameHook] 挂到
 *      引擎的模拟循环里，做输入采样 / 网络发送 / 远端输入等待。
 *
 * 这样 1P / 2P 走的就是「本地游戏的启动逻辑 + 服务器同步输入」，而不是
 * 「重新绘制一份对战专用 UI」。
 *
 * ## 服务器协议（保持不变）
 *
 * 客户端连接后立即开始游戏，服务器只转发输入：
 *   - 不等待 ready/start 信号（避免握手卡顿）。
 *   - inputDelay 由服务器在 hello 响应里通过 pad 字段下发，本端每帧从
 *     [com.nesstation.app.battle.BattleNetplay.inputDelay] 同步读取。
 *   - 双方帧计数从 0 起步，按 60fps 推进；执行帧 = 采样帧 - inputDelay。
 *
 * 参见 [NetplayController] 的设计说明。
 */
@Composable
fun BattleMatchScreen(
    args: BattleMatchArgs,
    onExit: () -> Unit
) {
    val context = LocalContext.current

    // 启动阶段的状态：未就绪 → 显示 loading；就绪 → 直接调起 EmulatorScreen
    var romFile by remember { mutableStateOf<File?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var roleText by remember { mutableStateOf(if (args.isHost) "房主 · 等待对手加入" else "挑战者 · 正在进入") }

    // 解析后的真实平台（服务端 args.platform + ROM 文件兜底检测）。
    var resolvedPlatform by remember(args.roomId) {
        mutableStateOf(com.nesstation.app.core.model.GamePlatform.fromString(args.platform.name))
    }

    // === 对战开始标志 —— 这是修复"2P 不同步 1P"的关键 ===
    //
    // 之前 1P 在收到 peer_joined 就启动引擎，但 2P 此时可能还没收到 hello(guest)，
    // 更没启动引擎。双方引擎启动时间差几百毫秒（ROM 加载），frame 计数错位，
    // 2P 查 remoteHistory[execFrame] 一直查不到 → desync 累积 → 单机体验。
    //
    // 修复后的启动时序（参考 GGPO 的 ready 握手）：
    //  - 2P (guest): onReady（收到 hello）→ 立即发 sendReady() 通知 1P →
    //                然后启动自己的引擎
    //  - 1P (host): onReady → 显示"等待对手就绪" → 等 onPeerReady
    //                （2P 的 ready 经服务器转发到了）→ 启动引擎
    //
    // 这样 1P 的引擎启动时刻 ≤ 2P 引擎启动时刻 + RTT/2。inputDelay=6 (100ms)
    // 足够吸收这个差 + ROM 加载时间差。
    var netReady by remember { mutableStateOf(false) }
    var matchStarted by remember { mutableStateOf(false) }

    val controller = remember(args.roomId) {
        NetplayController(platform = resolvedPlatform)
    }

    // === 先注册 UI listener，再连接服务器 ===
    // 之前 DisposableEffect 在 LaunchedEffect 之前执行，导致 controller.connect()
    // 触发的 onReady 回调可能比 setUiListener 还早到达 → dispatch 看到
    // primaryListener==null 直接丢弃 → matchStarted 永远不设 true。
    //
    // 现在在 remember controller 的同时就把 listener 设上，保证 connect()
    // 之前 listener 已经 ready。listener 内部用 setState 触发重组，安全。
    DisposableEffect(controller) {
        controller.setUiListener(object : NetplayController.UiListener {
            override fun onReady(role: String, inputDelay: Int) {
                netReady = true
                roleText = if (args.isHost) {
                    "房主 · 等待对手加入…（inputDelay=${inputDelay}f）"
                } else {
                    "挑战者 · 已与房主连线（inputDelay=${inputDelay}f）"
                }
                // 2P (guest): onReady 到了就说明 1P 在房间里。立即启动引擎。
                // 同时发 ready 通知 1P（作为备用信号，但 1P 主要靠 onPeerJoined）。
                if (!args.isHost) {
                    controller.sendReady()
                    matchStarted = true
                }
            }
            override fun onPeerReady(username: String) {
                // 1P (host): 2P 发的 ready 信号到了。这是备用启动信号 ——
                // 如果 onPeerJoined 已经启动了，这里只是更新状态文字。
                roleText = "房主 · 对手 $username 已就绪，开战！"
                if (args.isHost) {
                    matchStarted = true
                }
            }
            override fun onFrameInfo(frame: Long, inputDelay: Int, desyncCount: Int) {}
            override fun onPeerJoined(username: String) {
                roleText = if (args.isHost) "房主 · 对手 $username 已加入，开战！" else "挑战者 · 已与 $username 对战"
                // 1P (host): 2P 加入后立即启动引擎。不再等 ready 信号 ——
                // 之前等 onPeerReady 导致 1P 卡在"等待就绪"，但 2P 已经在跑了。
                // 双方启动时间差由 inputDelay 吸收（默认 6 帧 = 100ms）。
                if (args.isHost) {
                    matchStarted = true
                }
            }
            override fun onPeerLeft(username: String) { errorMsg = "对手 $username 已离开，对战结束" }
            override fun onError(message: String) { errorMsg = message }
            override fun onDisconnected() { if (errorMsg == null) errorMsg = "与对战服务器断开连接" }
            override fun onNetplayLost(reason: String) { roleText = reason }
        })
        onDispose { controller.setUiListener(null) }
    }

    // 启动流程：连接中继 + 检查 ROM。
    DisposableEffect(args.roomId, args.tcpAddr) {
        val (host, port) = parseTcpAddr(args.tcpAddr)
        val token = BattleSession.getToken(context) ?: ""

        if (token.isBlank()) {
            errorMsg = "未登录，请先返回大厅登录"
            return@DisposableEffect onDispose { }
        }

        // 1. 连接中继（异步，onReady 回调里会设 netReady=true）
        try {
            controller.connect(host, port, token, args.roomId)
        } catch (e: Exception) {
            errorMsg = "无法连接对战服务器: ${e.message}"
            return@DisposableEffect onDispose { }
        }

        // 2. 检查 ROM 是否已下载
        val rom = com.nesstation.app.battle.BattleRomStore.romFile(
            context,
            args.fileName.ifBlank { "${args.gameId}.zip" }
        )
        if (!rom.exists() || rom.length() <= 0) {
            errorMsg = "ROM 尚未下载，请先返回大厅下载"
            return@DisposableEffect onDispose { }
        }

        // 3. 根据服务端 platform + ROM 文件兜底，确定真实平台
        val detected = com.nesstation.app.core.storage.PlatformDetector.resolve(
            declared = args.platform.name,
            romFile = rom
        )
        if (detected != resolvedPlatform) {
            resolvedPlatform = detected
        }
        romFile = rom

        onDispose {
            try { controller.stop() } catch (_: Throwable) {}
        }
    }

    val rom = romFile
    val err = errorMsg

    when {
        // 错误态：显示错误 + 退出按钮
        err != null -> {
            LoadingScreen(
                text = err,
                iconTint = DeleteColor,
                onExit = { showExitConfirm = true }
            )
        }

        // 对战已开始：调起 EmulatorScreen，走和本地游戏完全一致的启动路径。
        // matchStarted 由上面的 onReady / onPeerReady 触发：
        //  - 2P: onReady 即触发（发 ready 通知 1P，然后启动自己）
        //  - 1P: onPeerReady 才触发（2P 的 ready 信号到了，双方同时启动）
        // 双方几乎同时进入这里 → 几乎同时从 frame 0 启动 → inputDelay 能正确同步。
        rom != null && matchStarted -> {
            val battleGameId = "battle_${args.gameId}_${args.roomId}".lowercase()
                .replace(Regex("[^a-z0-9._-]"), "_")
            val platform = resolvedPlatform
            val game = remember(rom, args.gameId, args.roomId, platform) {
                GameEntry(
                    id = battleGameId,
                    title = "对战 · ${args.gameId}",
                    romPath = rom.absolutePath,
                    platform = platform
                )
            }
            EmulatorScreen(
                game = game,
                onExit = { onExit() },
                netplayController = controller
            )
        }

        // 等待态：ROM 已就绪但还没开战
        //  - 1P: netReady=true 但 matchStarted=false → "等待对手加入…"
        //  - 2P: netReady=false → "正在连接对战服务器…"
        //  - 通用：ROM 还没下好 → "正在准备…"
        else -> {
            val text = when {
                rom == null -> "正在准备 ROM…"
                netReady && args.isHost -> "等待对手加入…"
                netReady && !args.isHost -> "正在进入对战…"
                else -> "正在连接对战服务器…"
            }
            LoadingScreen(
                text = text,
                subtext = roleText,
                onExit = { showExitConfirm = true }
            )
        }
    }

    // 退出确认弹窗（在 Loading / Error 态下使用；EmulatorScreen 内部有自己的退出菜单）
    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("退出对战？", color = PrimaryText) },
            text = { Text("退出后本局将结束，房间会被关闭。", color = SecondaryText) },
            containerColor = Color.White,
            titleContentColor = PrimaryText,
            textContentColor = SecondaryText,
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirm = false
                    try { controller.stop() } catch (_: Throwable) {}
                    onExit()
                }) { Text("退出", color = DeleteColor) }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text("继续对战", color = SecondaryText)
                }
            }
        )
    }
}

/**
 * Loading / 错误态用的简单全屏占位 —— 和旧版相比极简化，只显示一行文字 + 一个退出按钮。
 * 一旦 EmulatorScreen 调起，就会被它完全替换。
 */
@Composable
private fun LoadingScreen(
    text: String,
    subtext: String = "",
    iconTint: Color = Color.White,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Icon(
                Icons.Rounded.SportsEsports,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            Text(
                text,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            if (subtext.isNotBlank()) {
                Text(
                    subtext,
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // 退出按钮（左上角）
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xAA0E1626))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            androidx.compose.material3.TextButton(onClick = onExit) {
                Text("退出", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

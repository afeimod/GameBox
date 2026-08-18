package com.nesstation.app.ui.battle

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nesstation.app.battle.BattleNetplay
import com.nesstation.app.battle.BattleRomStore
import com.nesstation.app.battle.BattleSession
import com.nesstation.app.battle.NetplayEngine
import com.nesstation.app.core.model.GamePlatform
import com.nesstation.app.core.storage.PadLayout
import com.nesstation.app.core.storage.PadLayoutStore
import com.nesstation.app.ui.emulator.OnScreenController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val PrimaryText = Color(0xFF1E2A3A)
private val SecondaryText = Color(0xFF4A5568)
private val Accent = Color(0xFF8A7BFF)
private val Success = Color(0xFF27AE60)
private val DeleteColor = Color(0xFFE74C3C)

/**
 * 解析 "host:port" 格式的中继 TCP 地址。使用最后一个冒号分割以兼容 IPv6。
 * 空地址回退到默认端口。
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
 * 对战界面：连接中继 -> 加载 ROM -> 帧同步对战。
 * 触屏控件采用街机六键布局（方向 + A/B/C/D + Start/投币）。
 */
@Composable
fun BattleMatchScreen(
    args: BattleMatchArgs,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 连接状态：connecting / waiting(等对手) / playing / error
    var phase by remember { mutableStateOf("connecting") }
    var statusText by remember { mutableStateOf("正在连接对战服务器…") }
    var roleText by remember { mutableStateOf("") }
    var frameInfo by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showExitConfirm by remember { mutableStateOf(false) }

    // 加载用户按键布局和核心设置（复用 PadLayoutStore 中的街机配置）
    val padLayout = remember { PadLayoutStore.load(context) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    // 引擎 / 网络（remember 于组合中，DisposableEffect 释放）
    val engine = remember {
        // 服务器公布的中继地址优先；为空时回退到会话配置
        val (host, port) = parseTcpAddr(args.tcpAddr)
        lateinit var e: NetplayEngine
        e = NetplayEngine(
            romFile = BattleRomStore.romFile(context, args.gameId, "${args.gameId}.zip"),
            systemDir = BattleRomStore.biosDir(context).absolutePath,
            saveDir = context.filesDir.absolutePath + "/battle_saves/",
            net = BattleNetplay(
                host = host,
                port = port,
                token = BattleSession.getToken(context) ?: "",
                roomId = args.roomId,
                listener = object : BattleNetplay.Listener {
                    override fun onStart(role: String, inputDelay: Int) {
                        roleText = if (role == "host") "房主" else "挑战者"
                    }

                    override fun onRemoteInput(frame: Long, pad: Int) {
                        e.onRemoteInput(frame, pad)
                    }

                    override fun onPeerReady() {
                        e.onPeerReady()
                    }

                    override fun onPeerJoined(username: String) {
                        // 2P 加入：房主（1P）从单机模式切换为联机对战
                        e.enableNetplay()
                        statusText = "对手 $username 已加入，正在同步…"
                    }

                    override fun onPeerLeft(username: String) {
                        errorMsg = "对手已离开，对战结束"
                        phase = "error"
                        e.stop()
                    }

                    override fun onError(message: String) {
                        errorMsg = message
                        phase = "error"
                    }

                    override fun onDisconnected() {
                        if (phase != "error") {
                            errorMsg = "与对战服务器断开连接"
                            phase = "error"
                        }
                    }
                }
            )
        )
        // 应用 FBNeo 核心选项（复用用户已有的街机设置）
        e.setCoreOption("fbneo-aspect", padLayout.arcadeAspect)
        e.setCoreOption("fbneo-rotate-mode", padLayout.arcadeRotate)
        e.setCoreOption("fbneo-vertical-mode", padLayout.arcadeVerticalMode)
        e.setCoreOption("fbneo-crop-overscan", padLayout.arcadeCropOverscan)
        e.setCoreOption("fbneo-cpu-speed", padLayout.arcadeCpuSpeed)
        e.setCoreOption("fbneo-cpu-frameskip", padLayout.arcadeFrameskip)
        e.setCoreOption("fbneo-force-60hz", padLayout.arcadeForce60hz)
        e.setCoreOption("fbneo-samplerate", padLayout.arcadeSampleRate)
        e.setCoreOption("fbneo-audio-interpolation", padLayout.arcadeAudioInterp)
        e.setCoreOption("fbneo-lowpass", padLayout.arcadeLowpass)
        e.setCoreOption("fbneo-neogeo-mode", padLayout.arcadeNeogeomode)
        e.setCoreOption("fbneo-memcard-mode", padLayout.arcadeMemcard)
        // 视频滤镜和缩放
        e.setVideoFilter(
            when (padLayout.videoFilter) {
                "scanline" -> 1
                "crt" -> 2
                "dot" -> 3
                "xbr" -> 5      // native XBR(4) → HQ2X(5) to avoid color bleeding
                "hq2x" -> 5
                "hq4x" -> 6
                "xbr_dot" -> 5  // native XBR+dot(7) → HQ2X(5), dot added by FilterOverlay
                "4xbr" -> 6     // native 4XBR(8) → HQ4X(6) to avoid color bleeding
                "4xbr_dot" -> 6 // native 4XBR+dot(9) → HQ4X(6), dot added by FilterOverlay
                "hq4x_dot" -> 10
                else -> 0
            }
        )
        e.setHighQualityScaling(padLayout.highQualityScaling)
        e
    }

    engine.setListener(object : NetplayEngine.Listener {
        override fun onFrame(frame: Long, inputDelay: Int, desyncCount: Int) {
            frameInfo = "帧 #$frame · 延迟 ${inputDelay}f · desync $desyncCount"
        }

        override fun onExit() {}

        override fun onNetplayLost() {
            errorMsg = "对端输入中断（网络异常），已降级为单机演示"
        }
    })

    // 启动流程
    DisposableEffect(Unit) {
        val job = scope.launch(Dispatchers.IO) {
            // 1. 检查 ROM 是否已下载
            val romFile = BattleRomStore.romFile(context, args.gameId, "${args.gameId}.zip")
            if (!romFile.exists() || romFile.length() <= 0) {
                withContext(Dispatchers.Main) {
                    errorMsg = "ROM 尚未下载，请先返回大厅下载"
                    phase = "error"
                }
                return@launch
            }

            // 2. 连接中继（保持房间存在，供 2P 加入）
            val net = engine.net
            net.connect()

            if (args.isHost) {
                // 房主（1P）：立即开始游戏（单机模式），无需等待 2P 加入。
                // 2P 加入后由 onPeerJoined 切换为联机对战。
                withContext(Dispatchers.Main) {
                    phase = "waiting"
                    statusText = "单机进行中，等待 2P 加入可切换为联机对战…"
                }
                val hostOk = engine.start()
                if (!hostOk) {
                    withContext(Dispatchers.Main) {
                        errorMsg = "ROM 加载失败：${engine.net.inputDelay}"
                        phase = "error"
                    }
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    phase = "playing"
                    statusText = "对战进行中"
                }
                return@launch
            }

            // 3. 2P（挑战者）：等待服务器下发 start（双方到齐、输入延迟确定）
            var attempts = 0
            while (attempts < 600) {
                if (!net.isConnected) break
                if (net.started) break
                Thread.sleep(50)
                attempts++
            }
            if (!net.started) {
                withContext(Dispatchers.Main) {
                    errorMsg = "连接对战服务器超时"
                    phase = "error"
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                phase = "waiting"
                statusText = "已加入房间，等待对手就绪…"
            }

            // 4. 加载 ROM + 启动帧同步引擎（2P 默认即为联机模式）
            engine.enableNetplay()
            val ok = engine.start()
            if (!ok) {
                withContext(Dispatchers.Main) {
                    errorMsg = "ROM 加载失败：${engine.net.inputDelay}"
                    phase = "error"
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                phase = "playing"
                statusText = "对战进行中"
            }
        }

        onDispose {
            job.cancel()
            engine.stop()
            engine.net.close()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Color(0xFF0E1626))) {
        // ---- 顶部信息条 ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            IconButton(onClick = { showExitConfirm = true }, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "退出对战",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    when (phase) {
                        "connecting" -> statusText
                        "waiting" -> statusText
                        "playing" -> statusText
                        else -> "对战异常"
                    },
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (roleText.isNotBlank() && frameInfo.isNotBlank()) {
                    Text(
                        "$roleText · $frameInfo",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 10.sp,
                        maxLines = 1
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        when (phase) {
                            "playing" -> Success
                            "error" -> DeleteColor
                            else -> Color(0xFFF1C40F)
                        }
                    )
            )
        }

        // ---- 游戏画面 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.setFormat(android.graphics.PixelFormat.RGBX_8888)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                engine.setSurface(holder.surface)
                                surfaceSize = IntSize(holder.surfaceFrame.width(), holder.surfaceFrame.height())
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                surfaceSize = IntSize(width, height)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                engine.setSurface(null)
                            }
                        })
                    }
                },
                update = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
            )

            // 遮罩层
            if (phase == "error" || phase == "waiting") {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xAA0E1626)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        if (phase == "waiting") {
                            LinearProgressIndicator(
                                modifier = Modifier.width(160.dp),
                                color = Accent,
                                trackColor = Color.White.copy(alpha = 0.15f)
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "等待对手就绪…\n双方 ROM 都加载完成后自动开始",
                                color = Color.White,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(10.dp))
                            if (args.isHost) {
                                Text(
                                    "房主可退出房间",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.sp
                                )
                            }
                        } else {
                            Icon(
                                Icons.Rounded.SportsEsports,
                                contentDescription = null,
                                tint = DeleteColor,
                                modifier = Modifier.size(44.dp)
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                errorMsg ?: "对战结束",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))
                            Button(
                                onClick = onExit,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Accent,
                                    contentColor = Color.White
                                )
                            ) {
                                Text("返回对战大厅", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // ---- 触屏按键（复用完整的 OnScreenController） ----
        if (phase == "playing") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(12.dp)
            ) {
                OnScreenController(
                    padLayout = padLayout,
                    surfaceSize = surfaceSize,
                    onPadBits = { bits -> engine.setLocalPad(bits) },
                    platform = GamePlatform.ARCADE
                )
            }
        }
    }

    // ---- 退出确认 ----
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
                    engine.stop()
                    engine.net.close()
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
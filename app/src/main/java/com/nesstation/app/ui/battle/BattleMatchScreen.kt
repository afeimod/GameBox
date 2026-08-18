package com.nesstation.app.ui.battle

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalConfiguration
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
 * 对战界面：连接中继 -> 加载 ROM -> 帧同步对战。
 * 和游戏库单击启动一样：进房间直接开游戏，不等待对手。
 * 2P 加入后自动同步 1P 的输入，从 frame 0 追赶。
 */
@Composable
fun BattleMatchScreen(
    args: BattleMatchArgs,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isPortrait = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT

    var frameInfo by remember { mutableStateOf("") }
    var roleText by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    var surfaceSize by remember { mutableStateOf(IntSize.Zero) }

    val padLayout = remember { PadLayoutStore.load(context) }

    // 引擎 / 网络
    val engine = remember {
        val (host, port) = parseTcpAddr(args.tcpAddr)
        lateinit var e: NetplayEngine
        e = NetplayEngine(
            romFile = BattleRomStore.romFile(context, args.gameId, args.fileName.ifBlank { "${args.gameId}.zip" }),
            systemDir = BattleRomStore.biosDir(context).absolutePath,
            saveDir = context.filesDir.absolutePath + "/battle_saves/",
            net = BattleNetplay(
                host = host,
                port = port,
                token = BattleSession.getToken(context) ?: "",
                roomId = args.roomId,
                listener = object : BattleNetplay.Listener {
                    override fun onRemoteInput(frame: Long, pad: Int) {
                        e.onRemoteInput(frame, pad)
                    }

                    override fun onPeerJoined(username: String) {
                        scope.launch(Dispatchers.Main) {
                            roleText = if (args.isHost) "房主 · 对手 $username 已加入" else "挑战者"
                        }
                    }

                    override fun onPeerLeft(username: String) {
                        scope.launch(Dispatchers.Main) {
                            errorMsg = "对手 $username 已离开，对战结束"
                            e.stop()
                        }
                    }

                    override fun onError(message: String) {
                        scope.launch(Dispatchers.Main) {
                            errorMsg = message
                        }
                    }

                    override fun onDisconnected() {
                        scope.launch(Dispatchers.Main) {
                            if (errorMsg == null) {
                                errorMsg = "与对战服务器断开连接"
                            }
                        }
                    }
                }
            ),
            platform = args.platform
        )
        // 视频滤镜和缩放（和游戏库启动一致）
        e.setVideoFilter(
            when (padLayout.videoFilter) {
                "scanline" -> 1
                "crt" -> 2
                "dot" -> 3
                "xbr" -> 5
                "hq2x" -> 5
                "hq4x" -> 6
                "xbr_dot" -> 5
                "4xbr" -> 6
                "4xbr_dot" -> 6
                "hq4x_dot" -> 10
                else -> 0
            }
        )
        e.setHighQualityScaling(padLayout.highQualityScaling)
        if (args.platform == com.nesstation.app.core.model.GamePlatform.ARCADE) {
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
        }
        e
    }

    engine.setListener(object : NetplayEngine.Listener {
        override fun onFrame(frame: Long, inputDelay: Int, desyncCount: Int) {
            scope.launch(Dispatchers.Main) {
                frameInfo = "帧 #$frame · 延迟 ${inputDelay}f · desync $desyncCount"
            }
        }

        override fun onExit() {}

        override fun onNetplayLost() {
            scope.launch(Dispatchers.Main) {
                if (errorMsg == null) {
                    errorMsg = "对端输入中断（网络异常），已降级为单机演示"
                }
            }
        }
    })

    // 启动流程：连接 -> 加载 ROM -> 立即开始帧同步循环
    DisposableEffect(Unit) {
        val job = scope.launch(Dispatchers.IO) {
            val romFile = BattleRomStore.romFile(context, args.gameId, args.fileName.ifBlank { "${args.gameId}.zip" })
            if (!romFile.exists() || romFile.length() <= 0) {
                withContext(Dispatchers.Main) {
                    errorMsg = "ROM 尚未下载，请先返回大厅下载"
                }
                return@launch
            }

            // 连接中继
            engine.net.connect()

            // 立即启动帧同步引擎（不等待对方）
            val ok = engine.start()
            if (!ok) {
                withContext(Dispatchers.Main) {
                    errorMsg = "ROM 加载失败"
                }
                return@launch
            }

            withContext(Dispatchers.Main) {
                loaded = true
                roleText = if (args.isHost) "房主" else "挑战者"
            }
        }

        onDispose {
            job.cancel()
            engine.stop()
            engine.net.close()
        }
    }

    Column(modifier = modifier.fillMaxSize().background(Color.Black)) {
        // ---- 顶部信息条 ----
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(8.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.6f))
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
                    "对战中",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                if (roleText.isNotBlank() || frameInfo.isNotBlank()) {
                    Text(
                        buildString {
                            if (roleText.isNotBlank()) append(roleText)
                            if (frameInfo.isNotBlank()) {
                                if (isNotBlank()) append(" · ")
                                append(frameInfo)
                            }
                        },
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
                    .background(Success)
            )
        }

        // ---- 游戏画面 ----
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center
        ) {
            if (loaded && errorMsg == null) {
                GameSurface(
                    engine = engine,
                    videoScale = padLayout.videoScale,
                    isPortrait = isPortrait,
                    onSizeChanged = { surfaceSize = it },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Rounded.SportsEsports,
                            contentDescription = null,
                            tint = DeleteColor,
                            modifier = Modifier.size(44.dp)
                        )
                        androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                        Text(
                            errorMsg ?: "正在连接…",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 错误遮罩
            errorMsg?.let { msg ->
                if (loaded) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xAA0E1626)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            msg,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
        }

        // ---- 触屏按键（始终显示，和游戏库启动一致） ----
        if (loaded && errorMsg == null) {
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
                    platform = args.platform,
                    isPortrait = isPortrait
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

/**
 * 游戏画面 SurfaceView，支持用户配置的屏幕比例。
 * 和游戏库启动的 GameSurfaceView 逻辑一致。
 */
@Composable
private fun GameSurface(
    engine: NetplayEngine,
    videoScale: String,
    isPortrait: Boolean,
    onSizeChanged: (IntSize) -> Unit,
    modifier: Modifier = Modifier
) {
    val contentAlignment = when {
        isPortrait -> Alignment.TopCenter
        else -> Alignment.Center
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = contentAlignment) {
        val surfaceModifier = when (videoScale) {
            "4:3" -> Modifier.aspectRatio(4f / 3f)
            "3:2" -> Modifier.aspectRatio(3f / 2f)
            "8:7" -> Modifier.aspectRatio(8f / 7f)
            "16:9" -> Modifier.aspectRatio(16f / 9f)
            "custom" -> Modifier.fillMaxSize()
            else -> Modifier.fillMaxSize()
        }

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.setFormat(android.graphics.PixelFormat.RGBX_8888)
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            engine.setSurface(holder.surface)
                            onSizeChanged(IntSize(holder.surfaceFrame.width(), holder.surfaceFrame.height()))
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            onSizeChanged(IntSize(width, height))
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            engine.setSurface(null)
                        }
                    })
                }
            },
            update = {},
            modifier = surfaceModifier
        )
    }
}
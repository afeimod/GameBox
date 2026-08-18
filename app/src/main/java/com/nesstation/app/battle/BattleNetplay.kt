package com.nesstation.app.battle

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/**
 * 帧同步中继 TCP 客户端。
 *
 * 协议（JSON 行，\n 分隔）：
 *   -> {"type":"hello","token":"...","roomId":"..."}   握手（第一行）
 *   <- {"type":"hello","msg":"host"|"guest"}            角色分配
 *   <- {"type":"start","msg":"4"}                       双方到齐，输入延迟帧数
 *   -> {"type":"ready"}                                 本地 ROM 加载完成
 *   -> {"type":"input","frame":N,"pad":0x...}           本端输入
 *   <- {"type":"input","frame":N,"pad":0x...}           对方输入
 *   <- {"type":"peer_joined"/"peer_ready"/"peer_left", "msg":...}
 *   <- {"type":"error","msg":"..."}
 */
class BattleNetplay(
    private val host: String,
    private val port: Int,
    private val token: String,
    private val roomId: String,
    private val listener: Listener
) {

    interface Listener {
        /** 角色分配（host/guest）与输入延迟帧数 */
        fun onStart(role: String, inputDelay: Int)
        /** 收到对方的一帧输入 */
        fun onRemoteInput(frame: Long, pad: Int)
        /** 对方加入 */
        fun onPeerJoined(username: String)
        /** 对方断开 */
        fun onPeerLeft(username: String)
        /** 连接被拒绝 / 协议错误 */
        fun onError(message: String)
        /** 连接关闭（非主动） */
        fun onDisconnected()
    }

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    @Volatile private var running = false
    private var thread: Thread? = null

    @Volatile var role: String = "host"
        private set
    @Volatile var inputDelay: Int = 4
        private set

    /** 服务器已发送 start（双方到齐，可开始加载 ROM）。 */
    @Volatile var started: Boolean = false
        private set

    val isConnected: Boolean get() = running

    fun connect() {
        if (running) return
        running = true
        thread = thread(name = "battle-netplay", isDaemon = true) {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 15_000)
                s.tcpNoDelay = true
                socket = s
                reader = BufferedReader(InputStreamReader(s.getInputStream()))
                writer = BufferedWriter(OutputStreamWriter(s.getOutputStream()))

                // 握手
                val hello = JSONObject()
                    .put("type", "hello")
                    .put("token", token)
                    .put("roomId", roomId)
                sendLine(hello.toString())

                readLoop()
            } catch (e: Exception) {
                if (running) {
                    listener.onError("无法连接对战服务器: ${e.message}")
                }
            } finally {
                running = false
                closeQuietly()
                if (thread === Thread.currentThread()) {
                    listener.onDisconnected()
                }
            }
        }
    }

    private fun readLoop() {
        val r = reader ?: return
        while (running) {
            val line = try {
                r.readLine()
            } catch (_: Exception) {
                break
            }
            if (line == null) break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            try {
                handleMessage(JSONObject(trimmed))
            } catch (_: Exception) {
            }
        }
    }

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("type")) {
            "hello" -> {
                role = msg.optString("msg", "host")
            }
            "start" -> {
                inputDelay = msg.optString("msg", "4").toIntOrNull() ?: 4
                started = true
                listener.onStart(role, inputDelay)
            }
            "input" -> {
                listener.onRemoteInput(msg.optLong("frame", 0), msg.optInt("pad", 0))
            }
            "peer_joined" -> listener.onPeerJoined(msg.optString("msg", ""))
            "peer_left" -> listener.onPeerLeft(msg.optString("msg", ""))
            "error" -> listener.onError(msg.optString("msg", "服务器错误"))
            "pong" -> {}
        }
    }

    fun sendInput(frame: Long, pad: Int) {
        val line = JSONObject()
            .put("type", "input")
            .put("frame", frame)
            .put("pad", pad)
            .toString()
        sendLine(line)
    }

    fun sendReady() {
        sendLine(JSONObject().put("type", "ready").toString())
    }

    fun sendPing() {
        sendLine(JSONObject().put("type", "ping").toString())
    }

    fun sendBye() {
        sendLine(JSONObject().put("type", "bye").toString())
    }

    @Synchronized
    private fun sendLine(line: String) {
        val w = writer ?: return
        try {
            w.write(line)
            w.write("\n")
            w.flush()
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly() {
        try { writer?.close() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        writer = null
        reader = null
        socket = null
    }

    fun close() {
        running = false
        sendBye()
        closeQuietly()
        thread?.interrupt()
    }
}

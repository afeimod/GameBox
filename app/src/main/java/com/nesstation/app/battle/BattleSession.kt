package com.nesstation.app.battle

import android.content.Context
import android.content.SharedPreferences

/**
 * 对战平台会话 + 服务器配置存储。
 *
 * 服务器地址后期由用户告知后填入（设置页/对战平台首页可改）。
 * 默认值是本地占位地址，实际使用前必须修改。
 */
object BattleSession {

    private const val PREFS = "battle_session"

    // 默认服务器地址（占位）。后期改为用户提供的服务器 IP。
    var defaultServerHost: String = "192.168.1.100"
    var defaultServerHttpPort: String = "8080"
    var defaultServerTcpPort: String = "9090"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // --- 服务器地址 ---
    fun getServerHost(ctx: Context): String =
        prefs(ctx).getString("server_host", defaultServerHost) ?: defaultServerHost

    fun getServerHttpPort(ctx: Context): String =
        prefs(ctx).getString("server_http_port", defaultServerHttpPort) ?: defaultServerHttpPort

    fun getServerTcpPort(ctx: Context): String =
        prefs(ctx).getString("server_tcp_port", defaultServerTcpPort) ?: defaultServerTcpPort

    fun saveServer(ctx: Context, host: String, httpPort: String, tcpPort: String) {
        prefs(ctx).edit()
            .putString("server_host", host.trim())
            .putString("server_http_port", httpPort.trim())
            .putString("server_tcp_port", tcpPort.trim())
            .apply()
    }

    /** HTTP 基础地址，如 http://192.168.1.100:8080 */
    fun httpBase(ctx: Context): String {
        val host = getServerHost(ctx)
        val port = getServerHttpPort(ctx)
        if (host.contains("://")) {
            return host.trimEnd('/')
        }
        return "http://$host:$port"
    }

    /** TCP 中继地址，如 192.168.1.100:9090 */
    fun tcpAddr(ctx: Context): String {
        val host = getServerHost(ctx)
        val port = getServerTcpPort(ctx)
        return "$host:$port"
    }

    // --- 账号会话 ---
    fun saveAuth(ctx: Context, token: String, username: String) {
        prefs(ctx).edit()
            .putString("token", token)
            .putString("username", username)
            .apply()
    }

    fun getToken(ctx: Context): String? =
        prefs(ctx).getString("token", null)

    fun getUsername(ctx: Context): String? =
        prefs(ctx).getString("username", null)

    fun isLoggedIn(ctx: Context): Boolean =
        !getToken(ctx).isNullOrBlank()

    fun logout(ctx: Context) {
        prefs(ctx).edit().remove("token").remove("username").apply()
    }

    /** 服务器连通性快速检查 */
    fun hasConfiguredServer(ctx: Context): Boolean {
        val host = getServerHost(ctx)
        return host.isNotBlank() && host != "192.168.1.100"
    }
}

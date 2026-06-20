package com.shai.commsystem

import android.content.Context
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

interface WsCallback {
    fun onConnected()
    fun onDisconnected()
    fun onMessageReceived(json: JSONObject)
}

/**
 * WebSocket 客户端，负责与服务端保持长连接、自动重连（指数退避）。
 * 注意：仅用于接收服务端推送，不通过 WS 发送消息（发消息走 REST，
 * 与服务端 ws.js 的设计保持一致，避免出现两条路径不一致的问题）。
 */
class WsClient(private val context: Context, private val callback: WsCallback) {

    private val client = OkHttpClient.Builder()
        .pingInterval(25, TimeUnit.SECONDS) // 与服务端30秒心跳呼应，提前续约避免被判定僵尸连接
        .readTimeout(0, TimeUnit.MILLISECONDS) // WS长连接不设读超时
        .build()

    private var webSocket: WebSocket? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var reconnectDelayMs = 1000L
    private val maxReconnectDelayMs = 15000L
    private var manuallyClosedFlag = false

    fun connect() {
        manuallyClosedFlag = false
        val serverUrl = TokenStore.getServerUrl(context).trim()
        val token = TokenStore.getToken(context)
        if (serverUrl.isEmpty() || token.isNullOrEmpty()) return

        // http(s):// -> ws(s)://
        val wsBase = serverUrl
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .let { if (it.endsWith("/")) it.dropLast(1) else it }

        val url = "$wsBase/ws?token=$token"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectDelayMs = 1000L
                mainHandler.post { callback.onConnected() }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    mainHandler.post { callback.onMessageReceived(json) }
                } catch (e: Exception) {
                    // 忽略非法 JSON
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                mainHandler.post { callback.onDisconnected() }
                scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                mainHandler.post { callback.onDisconnected() }
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (manuallyClosedFlag) return
        mainHandler.postDelayed({
            if (!manuallyClosedFlag) connect()
        }, reconnectDelayMs)
        reconnectDelayMs = (reconnectDelayMs * 1.6).toLong().coerceAtMost(maxReconnectDelayMs)
    }

    fun disconnect() {
        manuallyClosedFlag = true
        webSocket?.close(1000, "client closed")
        webSocket = null
    }
}

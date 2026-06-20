package com.shai.commsystem

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * 前台服务：维持 WebSocket 长连接，接收服务端推送的消息。
 * Android 系统会在 App 切到后台一段时间后回收进程，前台服务+常驻通知
 * 是目前最可靠的保活手段（无法做到完全后台不可见，这是系统限制）。
 *
 * 保活强度分两档，由登录时选择的设备类型决定（TokenStore.getDeviceRole）：
 *   - 手机模式：仅前台服务 + START_STICKY，尊重系统省电策略，不额外抢资源。
 *   - 平板/电脑模式：额外持有部分 WakeLock + AlarmManager 周期性看门狗，
 *     用于"值班屏"场景，尽力保证消息不漏接，代价是更耗电。
 */
class ConnectionService : Service(), WsCallback {

    companion object {
        const val CHANNEL_ID = "comm_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.shai.commsystem.action.STOP"
    }

    private lateinit var wsClient: WsClient
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        wsClient = WsClient(this, this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            releaseWakeLockIfHeld()
            WatchdogReceiver.stop(this)
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("正在连接服务器…"))
        wsClient.connect()

        if (TokenStore.isTabletPcMode(this)) {
            acquireWakeLockIfNeeded()
            WatchdogReceiver.start(this)
        }

        // START_STICKY: 系统杀掉服务后会尝试重建，有助于长期保活
        return START_STICKY
    }

    override fun onDestroy() {
        wsClient.disconnect()
        releaseWakeLockIfHeld()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- 平板/电脑强制保活：WakeLock ----

    private fun acquireWakeLockIfNeeded() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // PARTIAL_WAKE_LOCK: 只保证CPU不休眠（维持WS连接），不点亮屏幕，省电与保活的折中
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CommSystem:ConnectionWakeLock")
        wakeLock?.setReferenceCounted(false)
        // 10小时超时兜底，避免WakeLock因异常未释放导致长期耗电；
        // 看门狗每5分钟会重新拉起服务并续期
        wakeLock?.acquire(10 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLockIfHeld() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    // ---- WsCallback ----

    override fun onConnected() {
        updateNotification("已连接 - 实时接收消息中")
    }

    override fun onDisconnected() {
        updateNotification("连接已断开，正在重连…")
    }

    override fun onMessageReceived(json: JSONObject) {
        val type = json.optString("type")

        if (type == "connected") {
            val onlineArr = json.optJSONArray("onlineUserIds")
            val localIntent = Intent("com.shai.commsystem.ONLINE_STATUS_SNAPSHOT")
            localIntent.putExtra("onlineUserIds", onlineArr?.toString() ?: "[]")
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(this).sendBroadcast(localIntent)
            return
        }

        if (type == "online_status") {
            val localIntent = Intent("com.shai.commsystem.ONLINE_STATUS_CHANGED")
            localIntent.putExtra("userId", json.optInt("userId"))
            localIntent.putExtra("online", json.optBoolean("online"))
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(this).sendBroadcast(localIntent)
            return
        }

        if (type == "message_read") {
            // 已读回执：转发给前台界面，更新对应消息气泡的"已查收"状态
            val localIntent = Intent("com.shai.commsystem.MESSAGE_READ")
            localIntent.putExtra("messageId", json.optInt("messageId"))
            androidx.localbroadcastmanager.content.LocalBroadcastManager
                .getInstance(this).sendBroadcast(localIntent)
            return
        }

        if (type != "new_message" && type != "new_message_silent") return

        val msg = json.optJSONObject("message") ?: return
        val myUserId = TokenStore.getUserId(this)
        val receiverId = msg.optInt("receiverId", -1)
        if (receiverId != myUserId) return // 不是发给我的，忽略（理论上服务端已经按用户推送，这里是双重保险）

        val messageId = msg.optInt("id", -1)
        val senderId = msg.optInt("senderId", -1)
        val senderName = msg.optString("senderName", "未知用户")
        val content = msg.optString("content", "")
        val createdAt = msg.optString("createdAt")

        // 广播给当前若有打开的聊天界面，让其实时刷新
        val localIntent = Intent("com.shai.commsystem.NEW_MESSAGE")
        localIntent.putExtra("messageId", messageId)
        localIntent.putExtra("senderId", senderId)
        localIntent.putExtra("senderName", senderName)
        localIntent.putExtra("content", content)
        localIntent.putExtra("createdAt", createdAt)
        androidx.localbroadcastmanager.content.LocalBroadcastManager
            .getInstance(this).sendBroadcast(localIntent)

        // type == new_message 表示该用户开启了弹窗提醒，触发强提醒全屏弹窗
        if (type == "new_message") {
            PopupActivity.show(this, messageId, senderId, senderName, content)
        }

        updateNotification("最新消息来自 $senderName")
    }

    // ---- 通知 ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "消息服务", NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "保持与服务器的实时连接"
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_IMMUTABLE else 0
        val pendingIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        val roleLabel = if (TokenStore.isTabletPcMode(this)) " · 强保活模式" else ""

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("消息调度台$roleLabel")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }
}

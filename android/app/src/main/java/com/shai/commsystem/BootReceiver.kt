package com.shai.commsystem

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 开机自启动：若用户已登录，开机后自动拉起前台服务，
 * 保证设备重启后无需手动打开 App 也能继续接收消息/弹窗。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!TokenStore.isLoggedIn(context)) return

        val serviceIntent = Intent(context, ConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}

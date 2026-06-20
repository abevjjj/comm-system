package com.shai.commsystem

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * 保活看门狗：仅在用户选择"平板 / Android电脑"模式时启用。
 *
 * 普通手机场景下，Android 系统对后台服务的管控已经足够强（且我们尊重系统的
 * 省电策略），不做额外的强制保活，避免不必要的耗电和被系统标记为异常应用。
 *
 * 平板/电脑场景通常是常电源供电、长时间固定使用的"值班屏"，对它们更看重
 * "消息绝对不能漏接"而非省电，因此用 AlarmManager 周期性自唤醒，
 * 检查 ConnectionService 是否还在运行，不在则重新拉起。
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_WATCHDOG_TICK = "com.shai.commsystem.action.WATCHDOG_TICK"
        private const val INTERVAL_MS = 5 * 60 * 1000L // 每5分钟检查一次

        fun start(context: Context) {
            if (!TokenStore.isTabletPcMode(context)) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = buildPendingIntent(context)

            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    } else {
                        // 没有精确闹钟权限时退化为非精确闹钟，仍能起到兜底拉活作用
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
            } catch (e: SecurityException) {
                // 部分厂商ROM对精确闹钟有额外限制，静默降级，不影响核心功能
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        }

        fun stop(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(buildPendingIntent(context))
        }

        private fun buildPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WatchdogReceiver::class.java).apply {
                action = ACTION_WATCHDOG_TICK
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            return PendingIntent.getBroadcast(context, 0, intent, flags)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_WATCHDOG_TICK) return
        if (!TokenStore.isLoggedIn(context) || !TokenStore.isTabletPcMode(context)) return

        // 重新拉起前台服务（若已在运行，ConnectionService.onStartCommand 是幂等的，
        // 重复调用只会重连一次WS，不会产生重复实例）
        val serviceIntent = Intent(context, ConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(context, serviceIntent)
        } else {
            context.startService(serviceIntent)
        }

        // 安排下一次检查，形成持续循环
        start(context)
    }
}

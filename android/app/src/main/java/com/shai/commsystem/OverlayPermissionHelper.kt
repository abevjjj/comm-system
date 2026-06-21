package com.shai.commsystem

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * 悬浮窗权限（SYSTEM_ALERT_WINDOW）检测与引导。
 *
 * Android 6.0 (API 23) 起，悬浮窗权限不再是安装时自动授予的"普通权限"，
 * 必须引导用户到系统设置页手动开启"允许显示在其他应用上层"。
 * 这是实现"消息霸屏显示、不依赖App是否在运行"的唯一可靠方式
 * （全屏Activity方案做不到App进程被杀死后仍能弹出）。
 */
object OverlayPermissionHelper {

    /** 当前是否已拥有悬浮窗权限 */
    fun hasPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            // Android 6.0 以下该权限随安装自动授予，无需运行时检测
            true
        }
    }

    /** 跳转到系统设置中"显示在其他应用上层"的授权页面 */
    fun requestPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

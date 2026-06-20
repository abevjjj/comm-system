package com.shai.commsystem

import android.content.Context
import android.content.SharedPreferences

/**
 * 登录态持久化存储。
 * 满足需求 4(b)：登录后保持登录状态，避免每次重新打开都需要登录。
 * 使用普通 SharedPreferences 即可（EncryptedSharedPreferences 需要额外的
 * androidx.security 依赖，在纯局域网内部工具场景下不是必需，保持依赖最小化
 * 有助于降低 CI 编译失败风险）。
 */
object TokenStore {
    private const val PREFS_NAME = "comm_prefs"
    private const val KEY_TOKEN = "token"
    private const val KEY_USERNAME = "username"
    private const val KEY_ROLE = "role"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_DEVICE_ROLE = "device_role"

    const val DEVICE_ROLE_PHONE = "phone"
    const val DEVICE_ROLE_TABLET_PC = "tablet_pc"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveSession(context: Context, token: String, userId: Int, username: String, role: String) {
        prefs(context).edit()
            .putString(KEY_TOKEN, token)
            .putInt(KEY_USER_ID, userId)
            .putString(KEY_USERNAME, username)
            .putString(KEY_ROLE, role)
            .apply()
    }

    fun getToken(context: Context): String? = prefs(context).getString(KEY_TOKEN, null)
    fun getUsername(context: Context): String? = prefs(context).getString(KEY_USERNAME, null)
    fun getRole(context: Context): String? = prefs(context).getString(KEY_ROLE, null)
    fun getUserId(context: Context): Int = prefs(context).getInt(KEY_USER_ID, -1)

    fun clearSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USERNAME)
            .remove(KEY_ROLE)
            .remove(KEY_USER_ID)
            .apply()
    }

    fun isLoggedIn(context: Context): Boolean = !getToken(context).isNullOrEmpty()

    // 服务器地址需要用户在登录页配置（局域网IP，比如 http://192.168.1.100:3000）
    fun saveServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_SERVER_URL, url).apply()
    }

    fun getServerUrl(context: Context): String =
        prefs(context).getString(KEY_SERVER_URL, "") ?: ""

    // 设备类型：手机 -> 轻量保活；平板/Android电脑 -> 强制保活
    // 由登录页手动选择，不自动判断（不同设备的屏幕尺寸/DPI不足以可靠区分使用场景）
    fun saveDeviceRole(context: Context, role: String) {
        prefs(context).edit().putString(KEY_DEVICE_ROLE, role).apply()
    }

    fun getDeviceRole(context: Context): String =
        prefs(context).getString(KEY_DEVICE_ROLE, DEVICE_ROLE_PHONE) ?: DEVICE_ROLE_PHONE

    fun isTabletPcMode(context: Context): Boolean = getDeviceRole(context) == DEVICE_ROLE_TABLET_PC
}

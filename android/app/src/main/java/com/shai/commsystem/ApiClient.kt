package com.shai.commsystem

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 简单的 REST API 客户端，基于 OkHttp 同步调用（调用方需在后台线程执行）。
 */
class ApiClient(private val context: Context) {

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    class ApiException(message: String, val statusCode: Int = -1) : IOException(message)

    private fun baseUrl(): String {
        val url = TokenStore.getServerUrl(context).trim()
        return if (url.endsWith("/")) url.dropLast(1) else url
    }

    /** 同步执行请求，返回解析后的 JSONObject；调用方需自行切换到后台线程 */
    @Throws(ApiException::class)
    private fun execute(path: String, method: String, body: JSONObject?): JSONObject {
        val urlStr = baseUrl() + path
        if (baseUrl().isEmpty()) throw ApiException("未配置服务器地址")

        val reqBuilder = Request.Builder().url(urlStr)
        val token = TokenStore.getToken(context)
        if (!token.isNullOrEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer $token")
        }

        val reqBody = body?.toString()?.toRequestBody(JSON)
        when (method) {
            "GET" -> reqBuilder.get()
            "POST" -> reqBuilder.post(reqBody ?: "{}".toRequestBody(JSON))
            "PATCH" -> reqBuilder.patch(reqBody ?: "{}".toRequestBody(JSON))
            "DELETE" -> reqBuilder.delete()
        }

        try {
            client.newCall(reqBuilder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
                if (!resp.isSuccessful) {
                    val errMsg = json.optString("error", "请求失败 (${resp.code})")
                    throw ApiException(errMsg, resp.code)
                }
                return json
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException("网络连接失败: ${e.message}")
        }
    }

    fun ping(): Boolean {
        return try {
            val urlStr = baseUrl() + "/api/ping"
            if (baseUrl().isEmpty()) return false
            val req = Request.Builder().url(urlStr).get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    @Throws(ApiException::class)
    fun login(username: String, password: String, deviceRole: String): JSONObject {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("deviceType", "android")
            put("deviceRole", deviceRole)
        }
        return execute("/api/auth/login", "POST", body)
    }

    @Throws(ApiException::class)
    fun me(): JSONObject = execute("/api/auth/me", "GET", null)

    @Throws(ApiException::class)
    fun logout(): JSONObject = execute("/api/auth/logout", "POST", null)

    @Throws(ApiException::class)
    fun getContacts(): JSONObject = execute("/api/messages/contacts", "GET", null)

    @Throws(ApiException::class)
    fun getMessages(withUserId: Int, limit: Int = 100): JSONObject =
        execute("/api/messages?with=$withUserId&limit=$limit", "GET", null)

    @Throws(ApiException::class)
    fun sendMessage(receiverId: Int, content: String): JSONObject {
        val body = JSONObject().apply {
            put("receiverId", receiverId)
            put("content", content)
        }
        return execute("/api/messages", "POST", body)
    }

    @Throws(ApiException::class)
    fun markRead(messageId: Int): JSONObject =
        execute("/api/messages/$messageId/read", "POST", null)

    @Throws(ApiException::class)
    fun getOnlineUserIds(): JSONObject = execute("/api/messages/online", "GET", null)
}

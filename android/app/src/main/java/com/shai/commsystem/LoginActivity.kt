package com.shai.commsystem

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private lateinit var serverUrlInput: EditText
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginBtn: Button
    private lateinit var connStatusText: TextView
    private lateinit var loginErrorText: TextView
    private lateinit var deviceRoleGroup: RadioGroup
    private lateinit var deviceRoleHint: TextView
    private lateinit var apiClient: ApiClient

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        serverUrlInput = findViewById(R.id.serverUrlInput)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        loginBtn = findViewById(R.id.loginBtn)
        connStatusText = findViewById(R.id.connStatusText)
        loginErrorText = findViewById(R.id.loginErrorText)
        deviceRoleGroup = findViewById(R.id.deviceRoleGroup)
        deviceRoleHint = findViewById(R.id.deviceRoleHint)
        apiClient = ApiClient(this)

        val savedUrl = TokenStore.getServerUrl(this)
        if (savedUrl.isNotEmpty()) serverUrlInput.setText(savedUrl)

        if (TokenStore.getDeviceRole(this) == TokenStore.DEVICE_ROLE_TABLET_PC) {
            findViewById<android.widget.RadioButton>(R.id.roleRadioTabletPc).isChecked = true
        }
        updateDeviceRoleHint()
        deviceRoleGroup.setOnCheckedChangeListener { _, _ -> updateDeviceRoleHint() }

        loginBtn.setOnClickListener { onLoginClicked() }

        // 需求 4(a)：每次启动都验证与服务端的连通性
        checkConnectivityThenMaybeAutoLogin()
    }

    private fun updateDeviceRoleHint() {
        val isTabletPc = findViewById<android.widget.RadioButton>(R.id.roleRadioTabletPc).isChecked
        deviceRoleHint.text = if (isTabletPc)
            "平板/电脑模式：强制保活，尽力防止系统回收后台连接（更耗电）"
        else
            "手机模式：节省电量，系统可能在长时间后台后回收连接"
    }

    private fun selectedDeviceRole(): String =
        if (findViewById<android.widget.RadioButton>(R.id.roleRadioTabletPc).isChecked)
            TokenStore.DEVICE_ROLE_TABLET_PC else TokenStore.DEVICE_ROLE_PHONE

    /**
     * 启动流程：
     * 1. 若已保存 token（需求4b：保持登录状态），先静默校验 token 是否仍然有效；
     *    有效则直接进入主界面，无需重新登录。
     * 2. 同时／无论是否已登录，都做一次服务器连通性探测，连不上则明确提示用户。
     */
    private fun checkConnectivityThenMaybeAutoLogin() {
        val hasToken = TokenStore.isLoggedIn(this)
        connStatusText.text = if (hasToken) "正在验证登录状态…" else "正在检测服务器连接…"
        connStatusText.setTextColor(0xFFD29922.toInt())

        scope.launch {
            val reachable = withContext(Dispatchers.IO) { apiClient.ping() }
            if (!reachable) {
                connStatusText.text = "⚠ 无法连接服务器，请检查地址或网络后重试"
                connStatusText.setTextColor(0xFFF85149.toInt())
                return@launch
            }

            connStatusText.text = "✓ 服务器连接正常"
            connStatusText.setTextColor(0xFF3FB950.toInt())

            if (hasToken) {
                val valid = withContext(Dispatchers.IO) {
                    try { apiClient.me(); true } catch (e: Exception) { false }
                }
                if (valid) {
                    goToMain()
                } else {
                    TokenStore.clearSession(this@LoginActivity)
                }
            }
        }
    }

    private fun onLoginClicked() {
        val serverUrl = serverUrlInput.text.toString().trim()
        val username = usernameInput.text.toString().trim()
        val password = passwordInput.text.toString()

        loginErrorText.visibility = android.view.View.GONE

        if (serverUrl.isEmpty() || username.isEmpty() || password.isEmpty()) {
            showError("请填写完整的服务器地址、账号和密码")
            return
        }

        TokenStore.saveServerUrl(this, serverUrl)
        val deviceRole = selectedDeviceRole()
        loginBtn.isEnabled = false
        loginBtn.text = "登录中…"

        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { apiClient.login(username, password, deviceRole) }
                val token = resp.getString("token")
                val user: JSONObject = resp.getJSONObject("user")
                TokenStore.saveSession(
                    this@LoginActivity,
                    token,
                    user.getInt("id"),
                    user.getString("username"),
                    user.getString("role")
                )
                TokenStore.saveDeviceRole(this@LoginActivity, deviceRole)
                goToMain()
            } catch (e: ApiClient.ApiException) {
                showError(e.message ?: "登录失败")
            } catch (e: Exception) {
                showError("登录失败: ${e.message}")
            } finally {
                loginBtn.isEnabled = true
                loginBtn.text = "登录"
            }
        }
    }

    private fun showError(msg: String) {
        loginErrorText.text = msg
        loginErrorText.visibility = android.view.View.VISIBLE
    }

    private fun goToMain() {
        startActivity(android.content.Intent(this, MainActivity::class.java))
        finish()
    }
}

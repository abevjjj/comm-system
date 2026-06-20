package com.shai.commsystem

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 消息弹窗提醒（强提醒模式）。
 *
 * 实现方式说明：
 * 使用"全屏透明 Activity + singleTask + 锁屏可见标志"的方案，而不是
 * SYSTEM_ALERT_WINDOW 悬浮窗。原因：
 *   1) Android 6.0+ 上 SYSTEM_ALERT_WINDOW 需要用户在系统设置中手动授权，
 *      流程繁琐且容易被用户拒绝/遗忘，导致弹窗功能形同虚设。
 *   2) 全屏透明 Activity 配合 startActivity() 从前台服务发起，
 *      在权限上更宽松，且能在锁屏上显示（FLAG_SHOW_WHEN_LOCKED）。
 *
 * 强提醒行为：
 *   - 拦截返回键，不允许通过返回键关闭弹窗
 *   - 不提供"忽略/关闭"按钮，必须点击"已查收"才能离开
 *   - 已查收会调用服务端已读确认接口，发送方会实时收到已读回执
 */
class PopupActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_MESSAGE_ID = "message_id"
        private const val EXTRA_SENDER_ID = "sender_id"
        private const val EXTRA_SENDER = "sender"
        private const val EXTRA_CONTENT = "content"

        fun show(context: Context, messageId: Int, senderId: Int, senderName: String, content: String) {
            val intent = Intent(context, PopupActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_SENDER_ID, senderId)
                putExtra(EXTRA_SENDER, senderName)
                putExtra(EXTRA_CONTENT, content)
            }
            context.startActivity(intent)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main)
    private var messageId: Int = -1
    private var senderId: Int = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindowToShowOverLockScreenAndOthers()
        setContentView(R.layout.activity_popup)

        messageId = intent.getIntExtra(EXTRA_MESSAGE_ID, -1)
        senderId = intent.getIntExtra(EXTRA_SENDER_ID, -1)
        val senderName = intent.getStringExtra(EXTRA_SENDER) ?: "未知用户"
        val content = intent.getStringExtra(EXTRA_CONTENT) ?: ""

        findViewById<TextView>(R.id.popupSender).text = senderName
        findViewById<TextView>(R.id.popupContent).text = content

        val ackBtn = findViewById<Button>(R.id.popupAckBtn)
        ackBtn.setOnClickListener { onAckClicked(ackBtn) }

        findViewById<android.view.View>(R.id.popupOpenBtn).setOnClickListener {
            // "查看会话"仍然需要先确认已读，避免绕开已读确认机制
            onAckClicked(null, openChatAfter = true)
        }
    }

    private fun onAckClicked(ackBtn: Button?, openChatAfter: Boolean = false) {
        ackBtn?.isEnabled = false
        ackBtn?.text = "确认中…"

        if (messageId <= 0) {
            // 理论上不会发生（消息ID缺失），兜底直接关闭，避免用户被卡住无法操作
            finishOrOpenChat(openChatAfter)
            return
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient(this@PopupActivity).markRead(messageId) }
            } catch (e: Exception) {
                // 已读确认失败（例如网络抖动）不阻塞用户离开弹窗，避免把人卡死在强提醒页面；
                // 服务端仍保留该消息为未读状态，下次进入聊天界面用户仍可手动确认
            } finally {
                finishOrOpenChat(openChatAfter)
            }
        }
    }

    private fun finishOrOpenChat(openChat: Boolean) {
        if (openChat) {
            val mainIntent = Intent(this, MainActivity::class.java)
            mainIntent.putExtra("openChatWith", senderId)
            startActivity(mainIntent)
        }
        finish()
    }

    /** 强提醒：拦截返回键，不允许通过返回键关闭弹窗，必须点击"已查收" */
    @Suppress("OVERRIDE_DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // 不调用 super.onBackPressed()，即不执行默认的关闭行为
    }

    private fun setupWindowToShowOverLockScreenAndOthers() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

package com.shai.commsystem

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList

/**
 * 待展示的弹窗消息
 */
data class PopupMessage(
    val messageId: Int,
    val senderId: Int,
    val senderName: String,
    val content: String
)

/**
 * 悬浮窗弹窗管理器（单例）。
 *
 * 用 WindowManager 直接添加 TYPE_APPLICATION_OVERLAY 类型的 View，
 * 而非启动 Activity —— 这是唯一能做到"App 进程完全未运行/无前台界面时
 * 依然能弹出、覆盖在任意其他应用之上"的方式，需要用户预先在系统设置中
 * 授权"显示在其他应用上层"权限（见 OverlayPermissionHelper）。
 *
 * 消息队列设计：
 * 同一时刻只展示一个悬浮窗。若展示期间又收到新消息，新消息进入队列尾部，
 * 不会打断或覆盖当前正在展示的弹窗；当前弹窗被确认/处理后，自动展示队列
 * 中的下一条。队列上限做了限制，避免消息洪泛场景下无限堆积内存。
 * 弹窗顶部会显示"还有 N 条未读"提示，让用户知道队列堆积情况。
 */
object OverlayPopupManager {

    private const val MAX_QUEUE_SIZE = 50

    private val queue = LinkedList<PopupMessage>()
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var currentShowing: PopupMessage? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    /** 将一条新消息加入队列；若当前没有弹窗在展示，立即展示 */
    @Synchronized
    fun enqueue(context: Context, message: PopupMessage) {
        if (!OverlayPermissionHelper.hasPermission(context)) {
            // 没有权限时静默放弃霸屏展示（消息内容仍会通过通知栏/应用内列表正常送达，
            // 不影响核心通信功能，只是少了强提醒效果）
            return
        }

        if (queue.size >= MAX_QUEUE_SIZE) {
            // 极端情况下的保护：丢弃队列中最旧的一条，避免内存无限增长
            queue.poll()
        }
        queue.add(message)

        if (currentShowing == null) {
            showNext(context.applicationContext)
        } else {
            updateQueueBadge()
        }
    }

    @Synchronized
    private fun showNext(context: Context) {
        // 用循环代替递归：连续多条消息都渲染失败时（极端场景，例如权限被中途收回），
        // 不会产生深层调用栈，最多线性遍历一次队列后停止
        while (true) {
            val next = queue.poll() ?: run {
                currentShowing = null
                return
            }
            currentShowing = next
            val success = renderOverlay(context, next)
            if (success) return
            // 渲染失败，继续尝试队列中的下一条
        }
    }

    /** @return 是否成功添加悬浮窗 View */
    private fun renderOverlay(context: Context, message: PopupMessage): Boolean {
        removeOverlayViewIfAny()

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager = wm

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_popup, null)

        view.findViewById<TextView>(R.id.popupSender).text = message.senderName
        view.findViewById<TextView>(R.id.popupContent).text = message.content

        val queueBadge = view.findViewById<TextView>(R.id.popupQueueBadge)
        refreshQueueBadgeView(queueBadge)

        val ackBtn = view.findViewById<Button>(R.id.popupAckBtn)
        ackBtn.setOnClickListener { onAckClicked(context, message, ackBtn, openChatAfter = false) }

        view.findViewById<View>(R.id.popupOpenBtn).setOnClickListener {
            onAckClicked(context, message, null, openChatAfter = true)
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            // 不设置 FLAG_NOT_FOCUSABLE：需要能接收按钮点击事件；
            // 不设置 FLAG_NOT_TOUCHABLE：需要能拦截触摸，实现"霸屏"效果——
            // 用户必须与弹窗交互（点击已查收/查看会话）才能继续操作底层应用，
            // 这是"强提醒"语义的核心，与需求中"必须手动确认"是一致的
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER

        return try {
            wm.addView(view, params)
            overlayView = view
            true
        } catch (e: Exception) {
            // 极少数厂商ROM对悬浮窗添加有额外限制，添加失败时跳过本条，
            // 由 showNext() 的循环继续尝试队列下一条
            currentShowing = null
            false
        }
    }

    private fun refreshQueueBadgeView(badge: TextView) {
        if (queue.isNotEmpty()) {
            badge.visibility = View.VISIBLE
            badge.text = "还有 ${queue.size} 条未处理消息"
        } else {
            badge.visibility = View.GONE
        }
    }

    private fun updateQueueBadge() {
        val badge = overlayView?.findViewById<TextView>(R.id.popupQueueBadge) ?: return
        refreshQueueBadgeView(badge)
    }

    private fun onAckClicked(
        context: Context,
        message: PopupMessage,
        ackBtn: Button?,
        openChatAfter: Boolean
    ) {
        ackBtn?.isEnabled = false
        ackBtn?.text = "确认中…"

        scope.launch {
            try {
                withContext(Dispatchers.IO) { ApiClient(context).markRead(message.messageId) }
            } catch (e: Exception) {
                // 已读确认失败（网络抖动等）不阻塞用户处理弹窗，避免卡住整个消息队列；
                // 服务端仍保留未读状态，用户后续可在应用内手动确认
            } finally {
                if (openChatAfter) {
                    val mainIntent = android.content.Intent(context, MainActivity::class.java)
                    mainIntent.addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    mainIntent.putExtra("openChatWith", message.senderId)
                    context.startActivity(mainIntent)
                }
                dismissCurrentAndShowNext(context)
            }
        }
    }

    @Synchronized
    private fun dismissCurrentAndShowNext(context: Context) {
        removeOverlayViewIfAny()
        currentShowing = null
        if (queue.isNotEmpty()) {
            showNext(context)
        }
    }

    private fun removeOverlayViewIfAny() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (e: Exception) {
            // View 可能已经被移除（例如系统因配置变化主动回收），忽略
        }
        overlayView = null
    }

    /** App退出登录等场景下，清空队列并移除当前悬浮窗 */
    @Synchronized
    fun clearAll() {
        queue.clear()
        removeOverlayViewIfAny()
        currentShowing = null
    }
}

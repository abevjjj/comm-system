package com.shai.commsystem

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var apiClient: ApiClient
    private lateinit var viewFlipper: ViewFlipper
    private lateinit var contactListView: ListView
    private lateinit var contactErrorView: android.view.View
    private lateinit var contactErrorDetail: TextView
    private lateinit var messageListView: ListView
    private lateinit var messageInput: EditText
    private lateinit var chatPeerNameView: TextView
    private lateinit var connDot: android.view.View
    private lateinit var connLabel: TextView

    private lateinit var contactAdapter: ContactAdapter
    private lateinit var messageAdapter: MessageAdapter

    private var contacts: List<Contact> = emptyList()
    private var currentMessages: MutableList<ChatMessage> = mutableListOf()
    private var activePeer: Contact? = null
    private var onlineUserIds: MutableSet<Int> = mutableSetOf()
    private val myUserId by lazy { TokenStore.getUserId(this) }

    private val scope = CoroutineScope(Dispatchers.Main)

    private val newMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val messageId = intent.getIntExtra("messageId", -1)
            val senderId = intent.getIntExtra("senderId", -1)
            val senderName = intent.getStringExtra("senderName") ?: ""
            val content = intent.getStringExtra("content") ?: ""
            val createdAt = intent.getStringExtra("createdAt") ?: ""

            val peer = activePeer
            if (peer != null && senderId == peer.id) {
                // 接收方视角：打印状态由服务端异步处理，此处只是本地即时回显消息内容，
                // 不对打印结果做任何猜测，避免显示不准确的状态
                currentMessages.add(
                    ChatMessage(messageId, senderId, senderName, myUserId, content, createdAt, null, "unread")
                )
                messageAdapter.update(currentMessages)
            }
        }
    }

    private val onlineSnapshotReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val arrStr = intent.getStringExtra("onlineUserIds") ?: "[]"
            try {
                val arr = JSONArray(arrStr)
                onlineUserIds = mutableSetOf()
                for (i in 0 until arr.length()) onlineUserIds.add(arr.getInt(i))
                contactAdapter.updateOnlineStatus(onlineUserIds)
            } catch (e: Exception) { /* 忽略解析失败，保持原状态 */ }
        }
    }

    private val onlineChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val userId = intent.getIntExtra("userId", -1)
            val online = intent.getBooleanExtra("online", false)
            if (userId < 0) return
            if (online) onlineUserIds.add(userId) else onlineUserIds.remove(userId)
            contactAdapter.updateOnlineStatus(onlineUserIds)
        }
    }

    private val messageReadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val messageId = intent.getIntExtra("messageId", -1)
            if (messageId < 0) return
            val idx = currentMessages.indexOfFirst { it.id == messageId }
            if (idx >= 0) {
                currentMessages[idx] = currentMessages[idx].copy(readStatus = "read")
                messageAdapter.update(currentMessages)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!TokenStore.isLoggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        apiClient = ApiClient(this)
        viewFlipper = findViewById(R.id.viewFlipper)
        contactListView = findViewById(R.id.contactListView)
        contactErrorView = findViewById(R.id.contactErrorView)
        contactErrorDetail = findViewById(R.id.contactErrorDetail)
        messageListView = findViewById(R.id.messageListView)
        messageInput = findViewById(R.id.messageInput)
        chatPeerNameView = findViewById(R.id.chatPeerName)
        connDot = findViewById(R.id.connDot)
        connLabel = findViewById(R.id.connLabel)

        contactAdapter = ContactAdapter(this, mutableListOf(), onlineUserIds)
        contactListView.adapter = contactAdapter
        contactListView.setOnItemClickListener { _, _, position, _ ->
            openChat(contacts[position])
        }
        findViewById<android.widget.Button>(R.id.contactRetryBtn).setOnClickListener { loadContacts() }

        messageAdapter = MessageAdapter(this, mutableListOf(), myUserId) { msg -> onAckMessage(msg) }
        messageListView.adapter = messageAdapter

        findViewById<ImageButton>(R.id.backBtn).setOnClickListener {
            viewFlipper.displayedChild = 0
            activePeer = null
        }
        findViewById<ImageButton>(R.id.logoutBtn).setOnClickListener { onLogout() }
        findViewById<android.widget.Button>(R.id.sendBtn).setOnClickListener { onSendClicked() }

        requestNotificationPermissionIfNeeded()
        requestIgnoreBatteryOptimizationIfTabletMode()
        requestOverlayPermissionIfNeeded()
        startConnectionService()

        // 从悬浮窗"查看会话"按钮跳转过来时，需要在 loadContacts() 发起请求之前
        // 就设置好目标用户ID，否则异步请求先于这行代码完成时，会丢失自动跳转的意图
        // （这是真实的时序竞态问题，曾导致"点击查看会话仍卡在联系人列表"的现象）
        val openChatWith = intent.getIntExtra("openChatWith", -1)
        if (openChatWith > 0) {
            pendingOpenChatWith = openChatWith
        }

        loadContacts()
        loadOnlineUsersSnapshot()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // MainActivity 实例已存在时（比如App在后台运行，用户点击弹窗的"查看会话"），
        // 系统会复用现有实例并回调这里，而不是重新走 onCreate()。
        // 此时联系人列表通常已经加载完毕，可以直接尝试打开目标会话；
        // 若联系人列表还没加载出来（比如onCreate的loadContacts还没返回），
        // 则走pendingOpenChatWith这条路径，等loadContacts完成后自动跳转。
        val openChatWith = intent.getIntExtra("openChatWith", -1)
        if (openChatWith > 0) {
            val target = contacts.find { it.id == openChatWith }
            if (target != null) {
                openChat(target)
            } else {
                pendingOpenChatWith = openChatWith
                loadContacts()
            }
        }
    }

    private var pendingOpenChatWith: Int = -1

    override fun onResume() {
        super.onResume()
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.registerReceiver(newMessageReceiver, IntentFilter("com.shai.commsystem.NEW_MESSAGE"))
        lbm.registerReceiver(onlineSnapshotReceiver, IntentFilter("com.shai.commsystem.ONLINE_STATUS_SNAPSHOT"))
        lbm.registerReceiver(onlineChangedReceiver, IntentFilter("com.shai.commsystem.ONLINE_STATUS_CHANGED"))
        lbm.registerReceiver(messageReadReceiver, IntentFilter("com.shai.commsystem.MESSAGE_READ"))
        refreshConnIndicator()
    }

    override fun onPause() {
        super.onPause()
        val lbm = LocalBroadcastManager.getInstance(this)
        lbm.unregisterReceiver(newMessageReceiver)
        lbm.unregisterReceiver(onlineSnapshotReceiver)
        lbm.unregisterReceiver(onlineChangedReceiver)
        lbm.unregisterReceiver(messageReadReceiver)
    }

    private fun refreshConnIndicator() {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { apiClient.ping() }
            connDot.setBackgroundResource(if (ok) R.drawable.dot_online else R.drawable.dot_offline)
            connLabel.text = if (ok) "已连接" else "连接已断开"
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100
                )
            }
        }
    }

    /**
     * 平板/电脑强保活模式下，引导用户将本应用加入电池优化白名单。
     * 不强制（用户可在系统弹窗中拒绝），仅尽力提升后台存活率。
     * 手机模式下不弹此引导，尊重系统默认的省电策略。
     */
    private fun requestIgnoreBatteryOptimizationIfTabletMode() {
        if (!TokenStore.isTabletPcMode(this)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            // 部分厂商ROM不支持该Intent，静默忽略，不影响核心功能
        }
    }

    /**
     * 悬浮窗权限引导：这是"消息霸屏显示、覆盖所有应用"功能的硬性前提。
     * 未授权时该功能会静默失效（OverlayPopupManager内部会跳过展示），
     * 用户不容易察觉为什么"收不到弹窗提醒"，因此这里主动弹窗说明并引导前往设置。
     * 仅在权限缺失时弹一次（每次进入主界面检测，已授权则不会再次打扰）。
     */
    private fun requestOverlayPermissionIfNeeded() {
        if (OverlayPermissionHelper.hasPermission(this)) return

        android.app.AlertDialog.Builder(this)
            .setTitle("需要悬浮窗权限")
            .setMessage("为了让消息能在锁屏和其他应用上层弹出提醒，需要授权\"显示在其他应用上层\"权限。请在接下来的系统设置页面中开启此权限。")
            .setCancelable(true)
            .setPositiveButton("前往设置") { _, _ ->
                OverlayPermissionHelper.requestPermission(this)
            }
            .setNegativeButton("暂不开启", null)
            .show()
    }

    private fun startConnectionService() {
        // 保活强度（手机/平板·电脑）在 ConnectionService 内部根据 TokenStore.getDeviceRole 自行判断，
        // 这里只需正常拉起前台服务即可
        val intent = Intent(this, ConnectionService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, intent)
        } else {
            startService(intent)
        }
    }

    private fun loadOnlineUsersSnapshot() {
        // WS connected 消息到达前先用一次 REST 查询兜底，避免列表短暂全部显示离线
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { apiClient.getOnlineUserIds() }
                val arr = resp.getJSONArray("onlineUserIds")
                onlineUserIds = mutableSetOf()
                for (i in 0 until arr.length()) onlineUserIds.add(arr.getInt(i))
                contactAdapter.updateOnlineStatus(onlineUserIds)
            } catch (e: Exception) { /* 静默失败，等WS连接后会再次同步 */ }
        }
    }

    private fun loadContacts() {
        contactErrorView.visibility = android.view.View.GONE
        contactListView.visibility = android.view.View.VISIBLE

        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { apiClient.getContacts() }
                val arr: JSONArray = resp.getJSONArray("contacts")
                val list = mutableListOf<Contact>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Contact(
                            o.getInt("id"),
                            o.getString("username"),
                            o.optString("role", "member"),
                            if (o.isNull("printer_ip")) null else o.optString("printer_ip")
                        )
                    )
                }
                contacts = list
                contactAdapter.update(list)

                if (pendingOpenChatWith > 0) {
                    contacts.find { it.id == pendingOpenChatWith }?.let { openChat(it) }
                    pendingOpenChatWith = -1
                }
            } catch (e: Exception) {
                // 展示详细、可长按复制的错误信息（替代一闪而过的Toast），便于精确定位问题：
                // - ApiClient.ApiException 携带 statusCode，能区分是HTTP错误还是网络层异常
                // - 其他异常类型（如JSON解析失败）也完整打印类名+message，避免信息丢失
                val detail = when (e) {
                    is ApiClient.ApiException ->
                        "HTTP ${e.statusCode}: ${e.message}\n服务器地址: ${TokenStore.getServerUrl(this@MainActivity)}"
                    else ->
                        "${e.javaClass.simpleName}: ${e.message}\n服务器地址: ${TokenStore.getServerUrl(this@MainActivity)}"
                }
                contactErrorDetail.text = detail
                contactErrorView.visibility = android.view.View.VISIBLE
                contactListView.visibility = android.view.View.GONE
            }
        }
    }

    private fun openChat(contact: Contact) {
        activePeer = contact
        chatPeerNameView.text = contact.username
        viewFlipper.displayedChild = 1
        currentMessages = mutableListOf()
        messageAdapter.update(currentMessages)

        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { apiClient.getMessages(contact.id) }
                val arr: JSONArray = resp.getJSONArray("messages")
                val list = mutableListOf<ChatMessage>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        ChatMessage(
                            o.getInt("id"),
                            o.getInt("sender_id"),
                            o.getString("sender_name"),
                            o.getInt("receiver_id"),
                            o.getString("content"),
                            o.getString("created_at"),
                            if (o.isNull("print_status")) null else o.optString("print_status"),
                            if (o.isNull("read_status")) "unread" else o.optString("read_status")
                        )
                    )
                }
                currentMessages = list
                messageAdapter.update(currentMessages)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "加载消息失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun onSendClicked() {
        val peer = activePeer ?: return
        val content = messageInput.text.toString().trim()
        if (content.isEmpty()) return
        messageInput.setText("")

        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) { apiClient.sendMessage(peer.id, content) }
                val m = resp.getJSONObject("message")
                currentMessages.add(
                    ChatMessage(
                        m.optInt("id", 0),
                        m.getInt("senderId"),
                        m.optString("senderName", ""),
                        m.getInt("receiverId"),
                        m.getString("content"),
                        m.optString("createdAt", ""),
                        // 使用服务端返回的真实初始打印状态(skipped/failed/pending)，
                        // 不再硬编码"pending"，避免对方未开启打印时误显示"打印中"
                        if (m.isNull("printStatus")) null else m.optString("printStatus"),
                        "unread"
                    )
                )
                messageAdapter.update(currentMessages)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "发送失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 用户在聊天界面中点击某条收到的消息上的"已查收"按钮（明确点击才算已读，非自动） */
    private fun onAckMessage(msg: ChatMessage) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { apiClient.markRead(msg.id) }
                val idx = currentMessages.indexOfFirst { it.id == msg.id }
                if (idx >= 0) {
                    currentMessages[idx] = currentMessages[idx].copy(readStatus = "read")
                    messageAdapter.update(currentMessages)
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "确认失败: ${e.message}", Toast.LENGTH_SHORT).show()
                messageAdapter.update(currentMessages) // 恢复按钮可点击状态
            }
        }
    }

    private fun onLogout() {
        scope.launch {
            try { withContext(Dispatchers.IO) { apiClient.logout() } } catch (e: Exception) {}
            TokenStore.clearSession(this@MainActivity)
            OverlayPopupManager.clearAll()
            stopService(Intent(this@MainActivity, ConnectionService::class.java))
            startActivity(Intent(this@MainActivity, LoginActivity::class.java))
            finish()
        }
    }
}

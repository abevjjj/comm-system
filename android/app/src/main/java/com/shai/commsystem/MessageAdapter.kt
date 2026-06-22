package com.shai.commsystem

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 注意：android.widget.ArrayAdapter 的构造函数会直接持有传入 List 的引用
 * （不会做拷贝），后续 clear()/addAll() 会直接修改这个引用对象。
 * 若传入 Kotlin 不可变集合（如 emptyList()），调用 clear() 会抛出
 * UnsupportedOperationException。这里用 ArrayList(...) 包一层确保可变。
 */
class MessageAdapter(
    context: Context,
    initialItems: List<ChatMessage>,
    private val myUserId: Int,
    private val onAckClick: (ChatMessage) -> Unit
) : ArrayAdapter<ChatMessage>(context, R.layout.item_message, ArrayList(initialItems)) {

    fun update(newItems: List<ChatMessage>) {
        clear()
        addAll(newItems)
        notifyDataSetChanged()
    }

    private val printLabels = mapOf(
        "pending" to ("打印中" to "#D29922"),
        "success" to ("已打印" to "#3FB950"),
        "failed" to ("打印失败" to "#F85149"),
        "skipped" to ("未打印" to "#6E7681")
    )

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_message, parent, false)

        val msg = getItem(position) ?: return view
        val mine = msg.senderId == myUserId

        val outerContainer = view as LinearLayout
        outerContainer.gravity = if (mine) android.view.Gravity.END else android.view.Gravity.START

        val bubble = view.findViewById<LinearLayout>(R.id.bubbleContainer)
        bubble.setBackgroundResource(if (mine) R.drawable.bg_bubble_mine else R.drawable.bg_bubble_theirs)

        view.findViewById<TextView>(R.id.msgText).text = msg.content
        view.findViewById<TextView>(R.id.msgTime).text = formatTime(msg.createdAt)

        val printStatusView = view.findViewById<TextView>(R.id.msgPrintStatus)
        val statusInfo = msg.printStatus?.let { printLabels[it] }
        if (statusInfo != null) {
            printStatusView.text = "\uD83D\uDDB6 ${statusInfo.first}"
            printStatusView.setTextColor(android.graphics.Color.parseColor(statusInfo.second))
            printStatusView.visibility = View.VISIBLE
        } else {
            printStatusView.visibility = View.GONE
        }

        val readStatusView = view.findViewById<TextView>(R.id.msgReadStatus)
        val ackBtn = view.findViewById<Button>(R.id.msgAckBtn)
        val isRead = msg.readStatus == "read"

        if (mine) {
            // 我发出的消息：展示对方是否已查收，不显示确认按钮
            readStatusView.visibility = View.VISIBLE
            readStatusView.text = if (isRead) "✓✓ 已查收" else "✓ 已送达"
            readStatusView.setTextColor(
                android.graphics.Color.parseColor(if (isRead) "#58A6FF" else "#6E7681")
            )
            ackBtn.visibility = View.GONE
        } else {
            // 我收到的消息：未读时显示"已查收"按钮，需明确点击才确认；已读则仅展示状态文字
            readStatusView.visibility = View.GONE
            if (isRead) {
                ackBtn.visibility = View.GONE
                readStatusView.visibility = View.VISIBLE
                readStatusView.text = "✓ 已查收"
                readStatusView.setTextColor(android.graphics.Color.parseColor("#58A6FF"))
            } else if (msg.id > 0) {
                ackBtn.visibility = View.VISIBLE
                ackBtn.isEnabled = true
                ackBtn.text = "已查收"
                ackBtn.setOnClickListener {
                    ackBtn.isEnabled = false
                    ackBtn.text = "确认中…"
                    onAckClick(msg)
                }
            } else {
                ackBtn.visibility = View.GONE
            }
        }

        return view
    }

    private fun formatTime(iso: String): String {
        return try {
            // 服务端格式: "2026-06-19T07:21:11.728Z" 或 SQLite datetime "2026-06-19 07:21:11"
            val cleaned = iso.replace("T", " ").replace("Z", "")
            val parts = cleaned.split(" ")
            if (parts.size < 2) return iso
            val datePart = parts[0].split("-")
            val timePart = parts[1].split(":")
            if (datePart.size < 3 || timePart.size < 2) return iso
            "${datePart[1]}-${datePart[2]} ${timePart[0]}:${timePart[1]}"
        } catch (e: Exception) {
            iso
        }
    }
}

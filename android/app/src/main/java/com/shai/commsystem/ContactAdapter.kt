package com.shai.commsystem

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

/**
 * 注意：android.widget.ArrayAdapter 的构造函数会直接持有传入 List 的引用
 * （不会做拷贝），后续 clear()/addAll() 等方法会直接修改这个引用对象。
 * 如果传入的是 Kotlin 的不可变集合（如 emptyList()、listOf()），
 * 会在调用 clear() 时抛出 UnsupportedOperationException。
 * 这里统一用 ArrayList(...) 包一层，确保内部持有的始终是可变集合，
 * 不依赖调用方传入的集合类型，从根上避免这类问题。
 */
class ContactAdapter(
    context: Context,
    initialItems: List<Contact>,
    private var onlineUserIds: Set<Int> = emptySet()
) : ArrayAdapter<Contact>(context, R.layout.item_contact, ArrayList(initialItems)) {

    fun update(newItems: List<Contact>) {
        clear()
        addAll(newItems)
        notifyDataSetChanged()
    }

    /** 在线状态变化时调用，仅刷新视图，不重新拉取联系人数据 */
    fun updateOnlineStatus(newOnlineUserIds: Set<Int>) {
        onlineUserIds = newOnlineUserIds
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_contact, parent, false)

        val contact = getItem(position) ?: return view
        view.findViewById<TextView>(R.id.contactName).text = contact.username
        view.findViewById<TextView>(R.id.contactRole).text =
            if (contact.role == "admin") "管理员" else ""

        val isOnline = onlineUserIds.contains(contact.id)
        view.findViewById<View>(R.id.contactDot)
            .setBackgroundResource(if (isOnline) R.drawable.dot_online else R.drawable.dot_offline)

        return view
    }
}

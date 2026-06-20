package com.shai.commsystem

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class ContactAdapter(
    context: Context,
    private var items: List<Contact>,
    private var onlineUserIds: Set<Int> = emptySet()
) : ArrayAdapter<Contact>(context, R.layout.item_contact, items) {

    fun update(newItems: List<Contact>) {
        items = newItems
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

        val contact = items[position]
        view.findViewById<TextView>(R.id.contactName).text = contact.username
        view.findViewById<TextView>(R.id.contactRole).text =
            if (contact.role == "admin") "管理员" else ""

        val isOnline = onlineUserIds.contains(contact.id)
        view.findViewById<View>(R.id.contactDot)
            .setBackgroundResource(if (isOnline) R.drawable.dot_online else R.drawable.dot_offline)

        return view
    }
}

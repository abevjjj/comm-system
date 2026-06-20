package com.shai.commsystem

data class Contact(
    val id: Int,
    val username: String,
    val role: String,
    val printerIp: String?
)

data class ChatMessage(
    val id: Int,
    val senderId: Int,
    val senderName: String,
    val receiverId: Int,
    val content: String,
    val createdAt: String,
    val printStatus: String?,
    val readStatus: String? = "unread"
)

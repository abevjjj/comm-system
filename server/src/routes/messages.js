// routes/messages.js - 消息收发核心逻辑
'use strict';

const express = require('express');
const { db } = require('../db');
const { pushToUser, getOnlineUserIds } = require('../ws');
const { printMessage } = require('../printer');

const router = express.Router();

function getFontSize() {
  const row = db.prepare('SELECT value FROM settings WHERE key = ?').get('print_font_size');
  return row ? row.value : 'normal';
}

// GET /api/messages/online  获取当前在线用户ID列表
router.get('/online', (req, res) => {
  res.json({ onlineUserIds: getOnlineUserIds() });
});

// GET /api/messages/contacts  获取通讯录（所有用户，供任意已登录用户发消息选择对象）
// 注意：与 /api/users（管理员专用，含敏感配置如printer_ip）区分，这里只暴露必要字段
router.get('/contacts', (req, res) => {
  const rows = db.prepare(`
    SELECT id, username, role, printer_ip FROM users WHERE id != ? ORDER BY username
  `).all(req.user.id);
  res.json({ contacts: rows });
});

// POST /api/messages  发送一条消息
// body: { receiverId: number, content: string }
router.post('/', async (req, res) => {
  const { receiverId, content } = req.body || {};
  if (!receiverId || !content || !content.trim()) {
    return res.status(400).json({ error: '收件人和消息内容不能为空' });
  }

  const receiver = db.prepare('SELECT * FROM users WHERE id = ?').get(receiverId);
  if (!receiver) {
    return res.status(404).json({ error: '接收用户不存在' });
  }

  const now = new Date().toISOString();
  const info = db.prepare(`
    INSERT INTO messages (sender_id, receiver_id, content, created_at)
    VALUES (?, ?, ?, ?)
  `).run(req.user.id, receiverId, content.trim(), now);

  const messageId = info.lastInsertRowid;
  const messagePayload = {
    type: 'new_message',
    message: {
      id: messageId,
      senderId: req.user.id,
      senderName: req.user.username,
      receiverId,
      content: content.trim(),
      createdAt: now
    }
  };

  // ---- ① 弹窗推送：仅当 receiver.popup_enabled 开启 ----
  // 通过 WebSocket 实时推送给该用户所有在线设备（网页+安卓）。
  // 客户端（尤其安卓）收到 type=new_message 后自行决定是否触发悬浮窗弹出。
  let popupSent = false;
  if (receiver.popup_enabled) {
    popupSent = pushToUser(receiverId, messagePayload);
  }

  // 即使未弹窗推送（用户关闭开关），普通消息同步仍然推送，
  // 只是客户端据此不弹悬浮窗，这里复用同一推送、用字段区分：
  if (!receiver.popup_enabled) {
    pushToUser(receiverId, { ...messagePayload, type: 'new_message_silent' });
  }

  db.prepare('UPDATE messages SET delivered_ws = ? WHERE id = ?').run(popupSent ? 1 : 0, messageId);

  // ---- ② 打印：仅当 receiver.print_enabled 开启，服务端直接发起，不依赖客户端在线 ----
  if (receiver.print_enabled) {
    if (!receiver.printer_ip) {
      db.prepare('UPDATE messages SET print_status = ?, print_error = ? WHERE id = ?')
        .run('failed', '该用户未配置打印机IP', messageId);
    } else {
      // 异步执行，不阻塞消息发送的响应；打印结果回写状态
      printMessage({
        printerIp: receiver.printer_ip,
        printerPort: receiver.printer_port,
        senderName: req.user.username,
        content: content.trim(),
        timestamp: now.replace('T', ' ').slice(0, 19),
        fontSize: getFontSize()
      }).then(() => {
        db.prepare('UPDATE messages SET print_status = ? WHERE id = ?').run('success', messageId);
      }).catch((err) => {
        db.prepare('UPDATE messages SET print_status = ?, print_error = ? WHERE id = ?')
          .run('failed', err.message, messageId);
        console.error(`[print] 消息#${messageId} 打印失败: ${err.message}`);
      });
    }
  } else {
    db.prepare('UPDATE messages SET print_status = ? WHERE id = ?').run('skipped', messageId);
  }

  res.status(201).json({ message: messagePayload.message });
});

// GET /api/messages?with=<userId>&limit=50  获取与某用户的历史消息（双向）
router.get('/', (req, res) => {
  const withUserId = Number(req.query.with);
  const limit = Math.min(Number(req.query.limit) || 50, 200);

  if (!withUserId) {
    return res.status(400).json({ error: '缺少 with 参数（对方用户ID）' });
  }

  const rows = db.prepare(`
    SELECT m.*, su.username AS sender_name, ru.username AS receiver_name
    FROM messages m
    JOIN users su ON su.id = m.sender_id
    JOIN users ru ON ru.id = m.receiver_id
    WHERE (m.sender_id = ? AND m.receiver_id = ?)
       OR (m.sender_id = ? AND m.receiver_id = ?)
    ORDER BY m.created_at DESC
    LIMIT ?
  `).all(req.user.id, withUserId, withUserId, req.user.id, limit);

  res.json({ messages: rows.reverse() });
});

// POST /api/messages/:id/read  标记消息为已读（需明确点击"已查收"按钮才调用，非自动）
router.post('/:id/read', (req, res) => {
  const messageId = Number(req.params.id);
  const msg = db.prepare('SELECT * FROM messages WHERE id = ?').get(messageId);
  if (!msg) return res.status(404).json({ error: '消息不存在' });

  // 只有接收方本人才能确认已读
  if (msg.receiver_id !== req.user.id) {
    return res.status(403).json({ error: '只能确认自己收到的消息' });
  }

  if (msg.read_status !== 'read') {
    const readAt = new Date().toISOString();
    db.prepare('UPDATE messages SET read_status = ?, read_at = ? WHERE id = ?')
      .run('read', readAt, messageId);

    // 回推给发送方，便于发送方实时看到"已查收"状态
    pushToUser(msg.sender_id, {
      type: 'message_read',
      messageId,
      readerId: req.user.id,
      readerName: req.user.username,
      readAt
    });
  }

  res.json({ ok: true });
});

module.exports = router;

// routes/users.js - 管理员用户管理
'use strict';

const express = require('express');
const { db, hashPassword } = require('../db');
const { sanitizeUser } = require('../auth');

const router = express.Router();

// GET /api/users  获取所有用户列表（管理员）
router.get('/', (req, res) => {
  const users = db.prepare('SELECT * FROM users ORDER BY id').all();
  res.json({ users: users.map(sanitizeUser) });
});

// POST /api/users  创建新用户（管理员）
router.post('/', (req, res) => {
  const { username, password, role, printerIp, printerPort, popupEnabled, printEnabled } = req.body || {};
  if (!username || !password) {
    return res.status(400).json({ error: '用户名和密码不能为空' });
  }
  const existing = db.prepare('SELECT id FROM users WHERE username = ?').get(username);
  if (existing) {
    return res.status(409).json({ error: '用户名已存在' });
  }

  const { hash, salt } = hashPassword(password);
  const info = db.prepare(`
    INSERT INTO users (username, password_hash, password_salt, role, printer_ip, printer_port, popup_enabled, print_enabled)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
    username,
    hash,
    salt,
    role === 'admin' ? 'admin' : 'member',
    printerIp || null,
    printerPort || 9100,
    popupEnabled === false ? 0 : 1,
    printEnabled === false ? 0 : 1
  );

  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(info.lastInsertRowid);
  res.status(201).json({ user: sanitizeUser(user) });
});

// PATCH /api/users/:id  更新用户信息/开关/打印机IP（管理员）
router.patch('/:id', (req, res) => {
  const id = Number(req.params.id);
  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(id);
  if (!user) return res.status(404).json({ error: '用户不存在' });

  const fields = [];
  const values = [];

  const { password, role, printerIp, printerPort, popupEnabled, printEnabled } = req.body || {};

  if (password) {
    const { hash, salt } = hashPassword(password);
    fields.push('password_hash = ?', 'password_salt = ?');
    values.push(hash, salt);
  }
  if (role === 'admin' || role === 'member') {
    fields.push('role = ?');
    values.push(role);
  }
  if (printerIp !== undefined) {
    fields.push('printer_ip = ?');
    values.push(printerIp || null);
  }
  if (printerPort !== undefined) {
    fields.push('printer_port = ?');
    values.push(printerPort || 9100);
  }
  if (popupEnabled !== undefined) {
    fields.push('popup_enabled = ?');
    values.push(popupEnabled ? 1 : 0);
  }
  if (printEnabled !== undefined) {
    fields.push('print_enabled = ?');
    values.push(printEnabled ? 1 : 0);
  }

  if (fields.length === 0) {
    return res.status(400).json({ error: '没有提供任何更新字段' });
  }

  values.push(id);
  db.prepare(`UPDATE users SET ${fields.join(', ')} WHERE id = ?`).run(...values);

  const updated = db.prepare('SELECT * FROM users WHERE id = ?').get(id);
  res.json({ user: sanitizeUser(updated) });
});

// DELETE /api/users/:id  删除用户（管理员）
router.delete('/:id', (req, res) => {
  const id = Number(req.params.id);
  if (id === req.user.id) {
    return res.status(400).json({ error: '不能删除自己当前登录的账号' });
  }
  const info = db.prepare('DELETE FROM users WHERE id = ?').run(id);
  if (info.changes === 0) return res.status(404).json({ error: '用户不存在' });
  // 同时清理该用户的会话
  db.prepare('DELETE FROM sessions WHERE user_id = ?').run(id);
  res.json({ ok: true });
});

module.exports = router;

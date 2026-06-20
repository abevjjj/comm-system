// routes/settings.js - 全局设置（打印字体大小等）
'use strict';

const express = require('express');
const { db } = require('../db');
const { FONT_SIZE_MAP } = require('../printer');

const router = express.Router();

// GET /api/settings  获取全局设置（任何已登录用户可读，便于前端展示）
router.get('/', (req, res) => {
  const rows = db.prepare('SELECT key, value FROM settings').all();
  const settings = {};
  for (const r of rows) settings[r.key] = r.value;
  res.json({ settings });
});

// PATCH /api/settings  更新全局设置（管理员）
router.patch('/', (req, res) => {
  if (req.user.role !== 'admin') {
    return res.status(403).json({ error: '需要管理员权限' });
  }
  const { print_font_size } = req.body || {};
  if (print_font_size !== undefined) {
    if (!Object.keys(FONT_SIZE_MAP).includes(print_font_size)) {
      return res.status(400).json({
        error: `无效的字体大小，可选值: ${Object.keys(FONT_SIZE_MAP).join(', ')}`
      });
    }
    db.prepare(`
      INSERT INTO settings (key, value) VALUES ('print_font_size', ?)
      ON CONFLICT(key) DO UPDATE SET value = excluded.value
    `).run(print_font_size);
  }
  const rows = db.prepare('SELECT key, value FROM settings').all();
  const settings = {};
  for (const r of rows) settings[r.key] = r.value;
  res.json({ settings });
});

module.exports = router;

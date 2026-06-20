// routes/auth.js
'use strict';

const express = require('express');
const { login, logout, requireAuth } = require('../auth');

const router = express.Router();

// POST /api/auth/login  无需登录
router.post('/login', (req, res) => {
  const { username, password, deviceType, deviceRole } = req.body || {};
  if (!username || !password) {
    return res.status(400).json({ error: '用户名和密码不能为空' });
  }
  const result = login(
    username,
    password,
    deviceType === 'android' ? 'android' : 'web',
    deviceRole === 'tablet_pc' ? 'tablet_pc' : 'phone'
  );
  if (!result) {
    return res.status(401).json({ error: '用户名或密码错误' });
  }
  res.json(result); // { token, user }
});

// POST /api/auth/logout  需要登录
router.post('/logout', requireAuth, (req, res) => {
  if (req.token) logout(req.token);
  res.json({ ok: true });
});

// GET /api/auth/me  需要登录；用于App启动时的连通性+登录态校验
router.get('/me', requireAuth, (req, res) => {
  res.json({ user: req.user });
});

module.exports = router;

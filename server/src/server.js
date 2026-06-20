// server.js - 服务端主入口
'use strict';

const express = require('express');
const http = require('node:http');
const path = require('node:path');

const { requireAuth, requireAdmin } = require('./auth');
const { setupWebSocketServer } = require('./ws');

const authRoutes = require('./routes/auth');
const userRoutes = require('./routes/users');
const messageRoutes = require('./routes/messages');
const settingsRoutes = require('./routes/settings');

const PORT = process.env.PORT || 3000;
const app = express();

app.use(express.json());

// 简单的访问日志，便于排查局域网联调问题
app.use((req, res, next) => {
  console.log(`${new Date().toISOString()} ${req.method} ${req.path}`);
  next();
});

// ---- 公开路由（login公开，logout/me在内部各自要求登录）----
app.use('/api/auth', authRoutes);

// 健康检查 / 连通性探测接口（安卓端启动时调用）
app.get('/api/ping', (req, res) => {
  res.json({ ok: true, time: new Date().toISOString() });
});

// ---- 需要登录的路由 ----
app.use('/api/messages', requireAuth, messageRoutes);
app.use('/api/settings', requireAuth, settingsRoutes); // GET所有登录用户可读；PATCH权限校验在routes/settings.js内部完成

// ---- 仅管理员路由 ----
app.use('/api/users', requireAuth, requireAdmin, userRoutes);

// ---- 网页端静态资源 ----
const WEB_DIR = path.join(__dirname, '..', '..', 'web');
app.use(express.static(WEB_DIR));
app.get('*', (req, res, next) => {
  if (req.path.startsWith('/api') || req.path.startsWith('/ws')) return next();
  res.sendFile(path.join(WEB_DIR, 'index.html'));
});

// ---- 错误处理 ----
app.use((err, req, res, next) => {
  console.error('[error]', err);
  res.status(500).json({ error: '服务器内部错误' });
});

const server = http.createServer(app);
setupWebSocketServer(server);

server.listen(PORT, () => {
  console.log(`服务已启动，监听端口 ${PORT}`);
  console.log(`网页端: http://<服务器IP>:${PORT}`);
  console.log(`WebSocket: ws://<服务器IP>:${PORT}/ws`);
});

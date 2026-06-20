// ws.js - WebSocket 连接管理
// 客户端连接时需在 URL query 中携带 token，例如: ws://host/ws?token=xxxx
'use strict';

const { WebSocketServer } = require('ws');
const { getUserByToken } = require('./auth');

// userId -> Set<ws>  (同一用户可能多端同时在线：网页+安卓)
const connections = new Map();

function addConnection(userId, ws) {
  const wasOffline = !isUserOnline(userId);
  if (!connections.has(userId)) connections.set(userId, new Set());
  connections.get(userId).add(ws);
  if (wasOffline) broadcastOnlineStatus(userId, true);
}

function removeConnection(userId, ws) {
  const set = connections.get(userId);
  if (!set) return;
  set.delete(ws);
  if (set.size === 0) {
    connections.delete(userId);
    broadcastOnlineStatus(userId, false);
  }
}

function isUserOnline(userId) {
  return connections.has(userId) && connections.get(userId).size > 0;
}

/** 当前所有在线用户ID列表 */
function getOnlineUserIds() {
  return Array.from(connections.keys());
}

/** 向指定用户的所有在线连接推送一条 JSON 消息 */
function pushToUser(userId, payload) {
  const set = connections.get(userId);
  if (!set || set.size === 0) return false;
  const data = JSON.stringify(payload);
  for (const ws of set) {
    if (ws.readyState === ws.OPEN) {
      ws.send(data);
    }
  }
  return true;
}

/** 向所有在线用户广播一条 JSON 消息（在线状态变化等全局事件） */
function broadcastToAll(payload) {
  const data = JSON.stringify(payload);
  for (const set of connections.values()) {
    for (const ws of set) {
      if (ws.readyState === ws.OPEN) ws.send(data);
    }
  }
}

/** 某用户上线/离线时，广播给所有人，便于客户端实时更新联系人列表的在线圆点 */
function broadcastOnlineStatus(userId, online) {
  broadcastToAll({ type: 'online_status', userId, online });
}

function setupWebSocketServer(httpServer) {
  const wss = new WebSocketServer({ server: httpServer, path: '/ws' });

  wss.on('connection', (ws, req) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    const token = url.searchParams.get('token');
    const user = getUserByToken(token);

    if (!user) {
      ws.send(JSON.stringify({ type: 'error', message: '未授权，连接被拒绝' }));
      ws.close(4001, 'unauthorized');
      return;
    }

    ws.userId = user.id;
    addConnection(user.id, ws);

    // 连接建立后，立即告知该客户端当前所有在线用户，便于初始化联系人列表的在线状态
    ws.send(JSON.stringify({ type: 'connected', userId: user.id, onlineUserIds: getOnlineUserIds() }));

    // 心跳保活，便于及时清理失效连接（尤其安卓后台/网络切换场景）
    ws.isAlive = true;
    ws.on('pong', () => { ws.isAlive = true; });

    ws.on('message', (raw) => {
      let msg;
      try { msg = JSON.parse(raw.toString()); } catch { return; }
      if (msg.type === 'ping') {
        ws.send(JSON.stringify({ type: 'pong' }));
      }
      // 注意：消息发送统一走 REST API（/api/messages），
      // WS 仅用于服务端 -> 客户端的实时推送，不接受客户端通过WS直接发消息，
      // 这样发消息逻辑（写库、判断打印/弹窗）只有一条路径，避免不一致。
    });

    ws.on('close', () => {
      removeConnection(user.id, ws);
    });

    ws.on('error', () => {
      removeConnection(user.id, ws);
    });
  });

  // 每 30 秒心跳检测一次，清理僵尸连接
  const interval = setInterval(() => {
    wss.clients.forEach((ws) => {
      if (ws.isAlive === false) {
        if (ws.userId) removeConnection(ws.userId, ws);
        return ws.terminate();
      }
      ws.isAlive = false;
      ws.ping();
    });
  }, 30000);

  wss.on('close', () => clearInterval(interval));

  return wss;
}

module.exports = {
  setupWebSocketServer,
  pushToUser,
  broadcastToAll,
  isUserOnline,
  getOnlineUserIds
};

// auth.js - 登录与 Token 鉴权
'use strict';

const crypto = require('node:crypto');
const { db, verifyPassword } = require('./db');

// Token 有效期：30 天（满足"保持登录"的需求，客户端持久化存储即可）
const TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;

function generateToken() {
  return crypto.randomBytes(32).toString('hex');
}

/**
 * 登录：校验用户名密码，签发 token 并写入 sessions 表
 * @param {string} deviceType  'web' | 'android'
 * @param {string} deviceRole  'phone' | 'tablet_pc'  （决定客户端保活策略，由登录页手动选择）
 * @returns {{token, user} | null}
 */
function login(username, password, deviceType = 'web', deviceRole = 'phone') {
  const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username);
  if (!user) return null;
  if (!verifyPassword(password, user.password_salt, user.password_hash)) return null;

  const token = generateToken();
  const expiresAt = new Date(Date.now() + TOKEN_TTL_MS).toISOString();
  const role = deviceRole === 'tablet_pc' ? 'tablet_pc' : 'phone';

  db.prepare(`
    INSERT INTO sessions (token, user_id, device_type, device_role, expires_at)
    VALUES (?, ?, ?, ?, ?)
  `).run(token, user.id, deviceType, role, expiresAt);

  return {
    token,
    user: sanitizeUser(user)
  };
}

/** 登出：删除指定 token */
function logout(token) {
  db.prepare('DELETE FROM sessions WHERE token = ?').run(token);
}

/** 根据 token 查找有效会话对应的用户，过期/不存在返回 null */
function getUserByToken(token) {
  if (!token) return null;
  const session = db.prepare('SELECT * FROM sessions WHERE token = ?').get(token);
  if (!session) return null;

  if (new Date(session.expires_at).getTime() < Date.now()) {
    db.prepare('DELETE FROM sessions WHERE token = ?').run(token);
    return null;
  }

  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(session.user_id);
  if (!user) return null;

  // 滑动续期：每次有效访问都刷新过期时间，实现"保持登录状态"
  const newExpiresAt = new Date(Date.now() + TOKEN_TTL_MS).toISOString();
  db.prepare('UPDATE sessions SET expires_at = ? WHERE token = ?').run(newExpiresAt, token);

  return sanitizeUser(user);
}

/** 去除敏感字段后返回用户对象 */
function sanitizeUser(user) {
  const { password_hash, password_salt, ...safe } = user;
  return safe;
}

/** Express 中间件：要求登录 */
function requireAuth(req, res, next) {
  const authHeader = req.headers['authorization'] || '';
  const token = authHeader.startsWith('Bearer ') ? authHeader.slice(7) : null;
  const user = getUserByToken(token);
  if (!user) {
    return res.status(401).json({ error: '未登录或登录已过期' });
  }
  req.user = user;
  req.token = token;
  next();
}

/** Express 中间件：要求管理员权限（须在 requireAuth 之后使用）*/
function requireAdmin(req, res, next) {
  if (!req.user || req.user.role !== 'admin') {
    return res.status(403).json({ error: '需要管理员权限' });
  }
  next();
}

// 定期清理过期 session（每小时一次）
setInterval(() => {
  db.prepare('DELETE FROM sessions WHERE expires_at < ?').run(new Date().toISOString());
}, 60 * 60 * 1000).unref();

module.exports = {
  login,
  logout,
  getUserByToken,
  sanitizeUser,
  requireAuth,
  requireAdmin
};

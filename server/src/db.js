// db.js - SQLite 数据库初始化与访问层
// 使用 Node.js 内置 node:sqlite（要求 Node >= 22.5）
'use strict';

const { DatabaseSync } = require('node:sqlite');
const path = require('node:path');
const fs = require('node:fs');
const crypto = require('node:crypto');

const DATA_DIR = path.join(__dirname, '..', 'data');
if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });

const DB_PATH = path.join(DATA_DIR, 'app.db');
const db = new DatabaseSync(DB_PATH);

db.exec(`
  PRAGMA journal_mode = WAL;

  CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    password_salt TEXT NOT NULL,
    role TEXT NOT NULL DEFAULT 'member',   -- 'admin' | 'member'
    printer_ip TEXT,                       -- 80mm 网络热敏打印机 IP
    printer_port INTEGER DEFAULT 9100,
    popup_enabled INTEGER NOT NULL DEFAULT 1,  -- 0/1
    print_enabled INTEGER NOT NULL DEFAULT 1,  -- 0/1
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
  );

  CREATE TABLE IF NOT EXISTS messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    sender_id INTEGER NOT NULL,
    receiver_id INTEGER NOT NULL,
    content TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    delivered_ws INTEGER NOT NULL DEFAULT 0,   -- 是否已通过WS推送在线设备
    print_status TEXT NOT NULL DEFAULT 'pending', -- pending|skipped|success|failed
    print_error TEXT,
    read_status TEXT NOT NULL DEFAULT 'unread',   -- unread|read  （需明确点击"已查收"才算read）
    read_at TEXT,
    FOREIGN KEY (sender_id) REFERENCES users(id),
    FOREIGN KEY (receiver_id) REFERENCES users(id)
  );

  CREATE INDEX IF NOT EXISTS idx_messages_receiver ON messages(receiver_id, created_at);

  CREATE TABLE IF NOT EXISTS sessions (
    token TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    device_type TEXT NOT NULL DEFAULT 'web', -- 'web' | 'android'
    device_role TEXT NOT NULL DEFAULT 'phone', -- 'phone' | 'tablet_pc' （手动选择，决定客户端保活策略）
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    expires_at TEXT NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id)
  );

  CREATE INDEX IF NOT EXISTS idx_sessions_expires ON sessions(expires_at);

  CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
  );
`);

// 默认全局设置：打印字体大小
// 取值: 'normal' | 'double_height' | 'double_width' | 'double_both'
const defaultSettings = {
  print_font_size: 'normal'
};
const getSettingStmt = db.prepare('SELECT value FROM settings WHERE key = ?');
const insertSettingStmt = db.prepare('INSERT INTO settings (key, value) VALUES (?, ?)');
for (const [k, v] of Object.entries(defaultSettings)) {
  if (!getSettingStmt.get(k)) insertSettingStmt.run(k, v);
}

// ---- 密码哈希（PBKDF2，避免额外依赖 bcrypt 的原生编译问题）----
function hashPassword(password, salt = crypto.randomBytes(16).toString('hex')) {
  const hash = crypto.pbkdf2Sync(password, salt, 100000, 64, 'sha512').toString('hex');
  return { hash, salt };
}

function verifyPassword(password, salt, expectedHash) {
  const { hash } = hashPassword(password, salt);
  return crypto.timingSafeEqual(Buffer.from(hash), Buffer.from(expectedHash));
}

// ---- 初始化默认管理员账号（仅当用户表为空时）----
function ensureDefaultAdmin() {
  const countRow = db.prepare('SELECT COUNT(*) AS c FROM users').get();
  if (countRow.c === 0) {
    const { hash, salt } = hashPassword('admin123');
    db.prepare(`
      INSERT INTO users (username, password_hash, password_salt, role, popup_enabled, print_enabled)
      VALUES (?, ?, ?, 'admin', 1, 0)
    `).run('admin', hash, salt);
    console.log('[init] 已创建默认管理员账号 admin / admin123，请登录后立即修改密码！');
  }
}
ensureDefaultAdmin();

module.exports = {
  db,
  hashPassword,
  verifyPassword
};

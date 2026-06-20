// app.js - 消息调度台 前端逻辑
'use strict';

const API_BASE = ''; // 同源部署，留空即可
const TOKEN_KEY = 'comm_token';
const USER_KEY = 'comm_user';

// ---------------- 状态 ----------------
const state = {
  token: localStorage.getItem(TOKEN_KEY) || null,
  me: null,
  users: [],
  onlineUserIds: new Set(),
  activePeerId: null,
  ws: null,
  wsReconnectTimer: null,
  wsReconnectDelay: 1000, // 指数退避起始值
};

// ---------------- DOM 引用 ----------------
const $ = (id) => document.getElementById(id);
const loginView = $('loginView');
const mainView = $('mainView');
const loginForm = $('loginForm');
const loginError = $('loginError');
const connDot = $('connDot');
const connLabel = $('connLabel');
const meName = $('meName');
const adminBtn = $('adminBtn');
const logoutBtn = $('logoutBtn');
const userListEl = $('userList');
const chatEmpty = $('chatEmpty');
const chatActive = $('chatActive');
const chatPeerName = $('chatPeerName');
const chatPeerMeta = $('chatPeerMeta');
const messageListEl = $('messageList');
const sendForm = $('sendForm');
const sendInput = $('sendInput');

// ---------------- 工具函数 ----------------

async function api(path, { method = 'GET', body } = {}) {
  const headers = { 'Content-Type': 'application/json' };
  if (state.token) headers['Authorization'] = `Bearer ${state.token}`;
  const resp = await fetch(API_BASE + path, {
    method,
    headers,
    body: body ? JSON.stringify(body) : undefined,
  });
  const data = await resp.json().catch(() => ({}));
  if (!resp.ok) {
    const err = new Error(data.error || `请求失败 (${resp.status})`);
    err.status = resp.status;
    throw err;
  }
  return data;
}

function fmtTime(iso) {
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

const PRINT_TAG = {
  pending: { cls: 'pending', label: '打印中' },
  success: { cls: 'success', label: '已打印' },
  failed:  { cls: 'failed',  label: '打印失败' },
  skipped: { cls: 'skipped', label: '未打印' },
};

// ---------------- 登录态持久化（满足"无需重复登录"需求）----------------

function saveSession(token, user) {
  state.token = token;
  state.me = user;
  // localStorage 在部分浏览器隐私模式/存储配额受限场景下可能抛错，
  // 这里做防御性处理：写入失败不应阻断登录本身（仅影响"下次免登录"这个体验），
  // 否则会出现"登录请求明明成功，但因为这一步异常导致页面卡在登录页"的诡异现象。
  try {
    localStorage.setItem(TOKEN_KEY, token);
    localStorage.setItem(USER_KEY, JSON.stringify(user));
  } catch (e) {
    console.warn('登录状态本地持久化失败（不影响本次登录）:', e);
  }
}

function clearSession() {
  state.token = null;
  state.me = null;
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(USER_KEY);
}

async function tryRestoreSession() {
  if (!state.token) return false;
  try {
    const { user } = await api('/api/auth/me');
    state.me = user;
    localStorage.setItem(USER_KEY, JSON.stringify(user));
    return true;
  } catch (e) {
    clearSession();
    return false;
  }
}

// ---------------- 视图切换 ----------------

function showLogin(message) {
  loginView.hidden = false;
  mainView.hidden = true;
  if (message) {
    loginError.textContent = message;
    loginError.hidden = false;
  } else {
    loginError.hidden = true;
  }
}

function showMain() {
  loginView.hidden = true;
  mainView.hidden = false;
  meName.textContent = state.me.username;
  adminBtn.hidden = state.me.role !== 'admin';
}

// ---------------- 登录 / 登出 ----------------

loginForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  loginError.hidden = true;
  const username = $('loginUsername').value.trim();
  const password = $('loginPassword').value;
  try {
    const { token, user } = await api('/api/auth/login', {
      method: 'POST',
      body: { username, password, deviceType: 'web' },
    });
    saveSession(token, user);
    await bootMain();
  } catch (err) {
    console.error('登录流程异常:', err);
    loginError.textContent = err.message || '登录失败，请查看控制台获取详细信息';
    loginError.hidden = false;
  }
});

logoutBtn.addEventListener('click', async () => {
  try { await api('/api/auth/logout', { method: 'POST' }); } catch (e) {}
  clearSession();
  if (state.ws) state.ws.close();
  location.reload();
});

// ---------------- WebSocket：实时推送 + 自动重连 ----------------

function connectWebSocket() {
  if (!state.token) return;
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const url = `${proto}://${location.host}/ws?token=${encodeURIComponent(state.token)}`;
  const ws = new WebSocket(url);
  state.ws = ws;

  ws.onopen = () => {
    setConnStatus(true);
    state.wsReconnectDelay = 1000; // 重连成功后重置退避
  };

  ws.onmessage = (evt) => {
    let data;
    try { data = JSON.parse(evt.data); } catch { return; }

    if (data.type === 'new_message' || data.type === 'new_message_silent') {
      handleIncomingMessage(data.message, data.type === 'new_message');
    } else if (data.type === 'connected') {
      state.onlineUserIds = new Set(data.onlineUserIds || []);
      renderUserList();
    } else if (data.type === 'online_status') {
      if (data.online) state.onlineUserIds.add(data.userId);
      else state.onlineUserIds.delete(data.userId);
      renderUserList();
    } else if (data.type === 'message_read') {
      handleMessageRead(data.messageId, data.readAt);
    }
  };

  ws.onclose = () => {
    setConnStatus(false);
    scheduleReconnect();
  };

  ws.onerror = () => {
    ws.close();
  };
}

function scheduleReconnect() {
  if (state.wsReconnectTimer) return;
  state.wsReconnectTimer = setTimeout(() => {
    state.wsReconnectTimer = null;
    connectWebSocket();
  }, state.wsReconnectDelay);
  // 指数退避，最大 15 秒
  state.wsReconnectDelay = Math.min(state.wsReconnectDelay * 1.6, 15000);
}

function setConnStatus(online) {
  connDot.classList.toggle('online', online);
  connDot.classList.toggle('offline', !online);
  connLabel.textContent = online ? '已连接' : '连接已断开，重连中…';
}

function handleIncomingMessage(message, shouldNotify) {
  const peerId = message.senderId === state.me.id ? message.receiverId : message.senderId;
  if (state.activePeerId === peerId) {
    appendMessageToView(message);
    scrollMessagesToBottom();
  }
  if (shouldNotify && message.receiverId === state.me.id) {
    showBrowserNotification(message);
  }
}

function showBrowserNotification(message) {
  if (!('Notification' in window)) return;
  if (Notification.permission === 'granted') {
    new Notification(`来自 ${message.senderName} 的消息`, { body: message.content });
  } else if (Notification.permission !== 'denied') {
    Notification.requestPermission();
  }
}

function handleMessageRead(messageId, readAt) {
  // 更新当前聊天界面中对应消息气泡的已读标记（若该消息正显示在屏幕上）
  const bubble = messageListEl.querySelector(`[data-msg-id="${messageId}"]`);
  if (bubble) {
    const readTag = bubble.querySelector('.read-tag');
    if (readTag) {
      readTag.textContent = '✓✓ 已查收';
      readTag.classList.add('read');
    }
  }
}

// ---------------- 用户列表 / 联系人 ----------------

async function loadUsers() {
  try {
    const { contacts } = await api('/api/messages/contacts');
    state.users = contacts;
  } catch (e) {
    state.users = [];
  }
  renderUserList();
}

function renderUserList() {
  userListEl.innerHTML = '';
  if (state.users.length === 0) {
    const li = document.createElement('li');
    li.className = 'hint';
    li.style.padding = '10px';
    li.textContent = '暂无可联系的用户';
    userListEl.appendChild(li);
    return;
  }
  for (const u of state.users) {
    const li = document.createElement('li');
    li.className = 'user-item' + (u.id === state.activePeerId ? ' active' : '');
    li.dataset.userId = u.id;

    const dot = document.createElement('span');
    dot.className = 'user-dot' + (state.onlineUserIds.has(u.id) ? ' online' : '');

    const name = document.createElement('span');
    name.className = 'user-name';
    name.textContent = u.username;

    const badges = document.createElement('span');
    badges.className = 'user-badges';
    if (u.role === 'admin') {
      const b = document.createElement('span');
      b.className = 'badge on';
      b.textContent = '管理';
      badges.appendChild(b);
    }

    li.append(dot, name, badges);
    li.addEventListener('click', () => openChat(u));
    userListEl.appendChild(li);
  }
}

// ---------------- 会话 / 消息 ----------------

async function openChat(user) {
  state.activePeerId = user.id;
  renderUserList();

  chatEmpty.hidden = true;
  chatActive.hidden = false;
  chatPeerName.textContent = user.username;
  chatPeerMeta.textContent = user.printer_ip ? `打印机: ${user.printer_ip}` : '未配置打印机';

  messageListEl.innerHTML = '<div class="hint" style="padding:8px 0;">加载中…</div>';
  try {
    const { messages } = await api(`/api/messages?with=${user.id}&limit=100`);
    messageListEl.innerHTML = '';
    for (const m of messages) appendMessageToView(normalizeMsg(m));
    scrollMessagesToBottom();
  } catch (e) {
    messageListEl.innerHTML = `<div class="hint">加载失败: ${e.message}</div>`;
  }
}

function normalizeMsg(row) {
  return {
    id: row.id,
    senderId: row.sender_id,
    senderName: row.sender_name,
    receiverId: row.receiver_id,
    content: row.content,
    createdAt: row.created_at,
    printStatus: row.print_status,
    readStatus: row.read_status,
  };
}

function appendMessageToView(message) {
  const mine = message.senderId === state.me.id;
  const row = document.createElement('div');
  row.className = 'msg-row' + (mine ? ' mine' : '');

  const bubble = document.createElement('div');
  bubble.className = 'msg-bubble';
  if (message.id) bubble.dataset.msgId = message.id;

  const text = document.createElement('div');
  text.className = 'msg-text';
  text.textContent = message.content;

  const meta = document.createElement('div');
  meta.className = 'msg-meta';
  const time = document.createElement('span');
  time.textContent = fmtTime(message.createdAt || new Date().toISOString());
  meta.appendChild(time);

  if (message.printStatus) {
    const tagInfo = PRINT_TAG[message.printStatus] || PRINT_TAG.skipped;
    const tag = document.createElement('span');
    tag.className = `print-tag ${tagInfo.cls}`;
    tag.textContent = `🖶 ${tagInfo.label}`;
    meta.appendChild(tag);
  }

  bubble.append(text, meta);

  if (mine) {
    // 我发出的消息：展示对方是否已查收
    const readTag = document.createElement('span');
    readTag.className = 'read-tag' + (message.readStatus === 'read' ? ' read' : '');
    readTag.textContent = message.readStatus === 'read' ? '✓✓ 已查收' : '✓ 已送达';
    meta.appendChild(readTag);
  } else if (message.id) {
    // 我收到的消息：若未读，显示"已查收"按钮，需明确点击才确认（不因打开聊天窗口自动已读）
    if (message.readStatus !== 'read') {
      const ackBtn = document.createElement('button');
      ackBtn.type = 'button';
      ackBtn.className = 'ack-btn';
      ackBtn.textContent = '已查收';
      ackBtn.addEventListener('click', () => confirmRead(message.id, ackBtn));
      bubble.appendChild(ackBtn);
    } else {
      const readDone = document.createElement('span');
      readDone.className = 'read-tag read';
      readDone.textContent = '✓ 已查收';
      bubble.appendChild(readDone);
    }
  }

  row.appendChild(bubble);
  messageListEl.appendChild(row);
}

async function confirmRead(messageId, btnEl) {
  btnEl.disabled = true;
  btnEl.textContent = '确认中…';
  try {
    await api(`/api/messages/${messageId}/read`, { method: 'POST' });
    const readDone = document.createElement('span');
    readDone.className = 'read-tag read';
    readDone.textContent = '✓ 已查收';
    btnEl.replaceWith(readDone);
  } catch (e) {
    btnEl.disabled = false;
    btnEl.textContent = '已查收';
    alert('确认失败: ' + e.message);
  }
}

function scrollMessagesToBottom() {
  messageListEl.scrollTop = messageListEl.scrollHeight;
}

sendForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const content = sendInput.value.trim();
  if (!content || !state.activePeerId) return;
  sendInput.value = '';
  autoGrow();

  try {
    const { message } = await api('/api/messages', {
      method: 'POST',
      body: { receiverId: state.activePeerId, content },
    });
    appendMessageToView({ ...message, printStatus: 'pending' });
    scrollMessagesToBottom();
  } catch (err) {
    alert('发送失败: ' + err.message);
  }
});

sendInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    sendForm.requestSubmit();
  }
});
sendInput.addEventListener('input', autoGrow);
function autoGrow() {
  sendInput.style.height = 'auto';
  sendInput.style.height = Math.min(sendInput.scrollHeight, 120) + 'px';
}

// ---------------- 管理后台：抽屉 ----------------

const adminDrawer = $('adminDrawer');
adminBtn.addEventListener('click', () => { adminDrawer.hidden = false; loadAdminUsers(); loadAdminSettings(); });
$('drawerClose').addEventListener('click', () => adminDrawer.hidden = true);
$('drawerBackdrop').addEventListener('click', () => adminDrawer.hidden = true);

document.querySelectorAll('.tab-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('.tab-btn').forEach(b => b.classList.remove('active'));
    document.querySelectorAll('.tab-panel').forEach(p => p.classList.remove('active'));
    btn.classList.add('active');
    const tabName = btn.dataset.tab;
    $('tab' + tabName.charAt(0).toUpperCase() + tabName.slice(1)).classList.add('active');
  });
});

async function loadAdminUsers() {
  const list = $('adminUserList');
  list.innerHTML = '<div class="hint">加载中…</div>';
  try {
    const { users } = await api('/api/users');
    list.innerHTML = '';
    for (const u of users) {
      const row = document.createElement('div');
      row.className = 'admin-user-row';
      row.innerHTML = `
        <span class="uname">${escapeHtml(u.username)}</span>
        <span class="urole">${u.role === 'admin' ? '管理员' : '成员'}</span>
      `;
      row.addEventListener('click', () => openUserModal(u));
      list.appendChild(row);
    }
  } catch (e) {
    list.innerHTML = `<div class="hint">加载失败: ${e.message}</div>`;
  }
}

async function loadAdminSettings() {
  try {
    const { settings } = await api('/api/settings');
    $('fontSizeSelect').value = settings.print_font_size || 'normal';
  } catch (e) {}
}

$('saveSettingsBtn').addEventListener('click', async () => {
  const msgEl = $('settingsMsg');
  try {
    await api('/api/settings', {
      method: 'PATCH',
      body: { print_font_size: $('fontSizeSelect').value },
    });
    msgEl.textContent = '已保存';
    msgEl.className = 'hint ok';
    msgEl.hidden = false;
    setTimeout(() => msgEl.hidden = true, 2000);
  } catch (e) {
    msgEl.textContent = '保存失败: ' + e.message;
    msgEl.className = 'hint';
    msgEl.hidden = false;
  }
});

function escapeHtml(s) {
  const div = document.createElement('div');
  div.textContent = s;
  return div.innerHTML;
}

// ---------------- 用户编辑弹层 ----------------

const userModal = $('userModal');
const userForm = $('userForm');

$('newUserBtn').addEventListener('click', () => openUserModal(null));
$('userModalClose').addEventListener('click', () => userModal.hidden = true);
$('userModalBackdrop').addEventListener('click', () => userModal.hidden = true);

function openUserModal(user) {
  userForm.reset();
  $('userFormError').hidden = true;
  $('userFormId').value = user ? user.id : '';
  $('userModalTitle').textContent = user ? '编辑用户' : '新建用户';
  $('userFormPasswordLabel').textContent = user ? '新密码（留空则不修改）' : '密码';
  $('userFormUsername').value = user ? user.username : '';
  $('userFormUsername').disabled = !!user;
  $('userFormRole').value = user ? user.role : 'member';
  $('userFormPrinterIp').value = user ? (user.printer_ip || '') : '';
  $('userFormPrinterPort').value = user ? (user.printer_port || 9100) : 9100;
  $('userFormPopup').checked = user ? !!user.popup_enabled : true;
  $('userFormPrint').checked = user ? !!user.print_enabled : true;
  $('userFormDelete').hidden = !user;
  userModal.hidden = false;
}

userForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const id = $('userFormId').value;
  const errEl = $('userFormError');
  errEl.hidden = true;

  const payload = {
    role: $('userFormRole').value,
    printerIp: $('userFormPrinterIp').value.trim(),
    printerPort: Number($('userFormPrinterPort').value) || 9100,
    popupEnabled: $('userFormPopup').checked,
    printEnabled: $('userFormPrint').checked,
  };
  const password = $('userFormPassword').value;
  if (password) payload.password = password;

  try {
    if (id) {
      await api(`/api/users/${id}`, { method: 'PATCH', body: payload });
    } else {
      payload.username = $('userFormUsername').value.trim();
      if (!password) { errEl.textContent = '新建用户必须设置密码'; errEl.hidden = false; return; }
      await api('/api/users', { method: 'POST', body: payload });
    }
    userModal.hidden = true;
    await loadAdminUsers();
    await loadUsers();
  } catch (err) {
    errEl.textContent = err.message;
    errEl.hidden = false;
  }
});

$('userFormDelete').addEventListener('click', async () => {
  const id = $('userFormId').value;
  if (!id) return;
  if (!confirm('确定删除该用户？此操作不可撤销。')) return;
  try {
    await api(`/api/users/${id}`, { method: 'DELETE' });
    userModal.hidden = true;
    await loadAdminUsers();
    await loadUsers();
  } catch (err) {
    $('userFormError').textContent = err.message;
    $('userFormError').hidden = false;
  }
});

// ---------------- 启动流程 ----------------
// 满足需求 4(a)：启动时验证服务端连通性，离线则提示

async function checkServerConnectivity() {
  try {
    const resp = await fetch(API_BASE + '/api/ping');
    return resp.ok;
  } catch (e) {
    return false;
  }
}

async function bootMain() {
  showMain();
  try {
    await loadUsers();
  } catch (e) {
    console.error('加载联系人失败:', e);
  }
  try {
    connectWebSocket();
  } catch (e) {
    console.error('WebSocket连接初始化失败:', e);
  }
}

(async function init() {
  const serverUp = await checkServerConnectivity();
  if (!serverUp) {
    showLogin('无法连接到服务器，请检查网络或服务器状态后刷新页面重试。');
    return;
  }

  const restored = await tryRestoreSession();
  if (restored) {
    await bootMain();
  } else {
    showLogin();
  }
})();

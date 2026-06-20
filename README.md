# 消息调度台 (Comm System)

局域网内部通信系统，包含服务端、网页端、安卓客户端。与普通聊天软件的核心区别：
**每条消息可以被服务端自动转发到收件人绑定的 80mm 网络热敏打印机上打印**，
打印逻辑由服务端直接发起（直连打印机 IP），不依赖客户端在线。

## 目录结构

```
comm-system/
├── server/     Node.js 服务端（Express + ws + node:sqlite）
├── web/        网页端（原生 HTML/JS，登录后管理员可见后台菜单）
├── android/    安卓客户端工程（Kotlin，minSdk 21 / Android 5.0）
└── .github/workflows/build-apk.yml   自动构建APK的CI配置
```

## 核心设计：打印任务由谁发起？

**服务端直接发起**，不依赖客户端。

发消息 → 服务端写库 → 服务端并行执行两件事：
1. 若收件人开启了"弹窗"，通过 WebSocket 推送给该用户所有在线设备；
2. 若收件人开启了"自动打印"，服务端直接用 TCP Socket 连接该用户绑定的
   打印机 IP（端口 9100），发送 ESC/POS 指令完成打印。

这样即使收件人的手机/网页都没打开，只要打印开关开着，消息依然会被打印——
这是局域网场景下最直接可靠的实现方式。

## 服务端部署（Ubuntu）

### 环境要求

**Node.js >= 22.5**（因为用到了内置的 `node:sqlite` 模块，避免引入需要
原生编译的第三方 SQLite 库，简化部署）。Ubuntu 自带的 apt 版本的 Node 通常
版本过旧，**请勿用 `apt install nodejs`**，改用 NodeSource 仓库或 nvm：

```bash
# 方式一：nvm（推荐，方便切换版本）
curl -o- https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash
source ~/.bashrc
nvm install 22
nvm use 22

# 方式二：NodeSource
curl -fsSL https://deb.nodesource.com/setup_22.x | sudo -E bash -
sudo apt install -y nodejs
```

### 安装与启动

```bash
cd server
npm install
PORT=3000 npm start
```

首次启动会自动创建默认管理员账号 `admin / admin123`，
**请登录后立即在管理后台修改密码**。

### 作为 systemd 服务常驻（推荐）

```ini
# /etc/systemd/system/comm-system.service
[Unit]
Description=Comm System Server
After=network.target

[Service]
Type=simple
WorkingDirectory=/path/to/comm-system/server
ExecStart=/usr/bin/node src/server.js
Environment=PORT=3000
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now comm-system
```

### 网页端

无需单独部署，服务端已经把 `web/` 目录作为静态资源托管。
直接用浏览器访问 `http://<服务器IP>:3000` 即可。

## 打印机要求

- 80mm 宽幅热敏打印机
- 支持网络接口（以太网或 WiFi），监听 9100 端口（标准 ESC/POS RAW 端口）
- 中文编码采用 GB18030（兼容绝大多数国产热敏打印机），由 `iconv-lite` 处理

在管理后台为每个用户配置好对应打印机的局域网 IP 地址即可。

## 安卓客户端

### 构建方式

完全依赖 GitHub Actions 自动编译，无需本地 Android Studio 环境：

1. 将本仓库推送到 GitHub
2. push 到 `main` 分支且改动了 `android/` 目录下文件时，自动触发构建
   （也可以在 Actions 页面手动触发 `workflow_dispatch`）
3. 构建完成后，在该次 workflow 运行页面的 Artifacts 中下载
   `comm-system-debug-apk`

### 使用方式

1. 安装 APK 到安卓设备（Android 5.0 及以上）
2. 首次打开，在登录页填写服务器地址，例如 `http://192.168.1.100:3000`
3. 输入管理员预先创建好的账号密码登录
4. 登录后会启动一个前台服务（通知栏会有常驻通知），用于保持与服务器的
   WebSocket 长连接，接收实时消息推送

### 关于"弹窗覆盖所有应用"的实现方式

最初讨论方案是用 `SYSTEM_ALERT_WINDOW` 悬浮窗权限，但实际开发中改为
**全屏透明 Activity** 方案，原因：

- `SYSTEM_ALERT_WINDOW` 在 Android 6.0+ 需要用户去系统设置里手动授权，
  流程繁琐、容易被忽略，一旦忘记授权弹窗功能就完全失效
- 全屏透明 Activity 由前台服务在收到消息时主动 `startActivity()` 拉起，
  配合 `FLAG_SHOW_WHEN_LOCKED` 等窗口标志，可以做到锁屏可见、打断当前
  操作，达到与悬浮窗相近的"强提醒"效果，且不需要额外的用户授权步骤

Manifest 中仍然声明了 `SYSTEM_ALERT_WINDOW` 权限，如果后续实测发现某些
场景下必须用真正的悬浮窗（比如需要同时与其他界面共存而非全屏打断），
可以平滑切换实现。

### 已知限制 / 后续可改进项

- **消息历史**：当前只保留文本消息，未实现图片/文件传输（按需求约定
  纯文本即可）。
- **Token 有效期**：默认 30 天，每次有效请求会自动续期（滑动过期），
  正常使用下不会主动掉登录态。

## 在线状态广播

服务端维护每个用户当前的 WebSocket 连接数，用户上线（0→1个连接）或离线
（连接数归零）时，会通过 WebSocket 广播给所有在线用户：

```json
{"type": "online_status", "userId": 2, "online": true}
```

客户端首次建立 WS 连接时也会收到一次完整快照：

```json
{"type": "connected", "userId": 1, "onlineUserIds": [1, 2]}
```

网页端和安卓端的联系人列表会据此实时更新在线圆点。安卓端在 WS 连接建立
之前，也会先用 `GET /api/messages/online` 做一次兜底拉取，避免列表短暂
全部显示离线。

## 已读确认（已查收）

消息默认是"未读"状态，**必须由接收方明确点击"已查收"按钮**才会标记为
已读，不会因为打开聊天窗口、消息进入可见区域等行为自动触发——这是为了
避免误触导致的虚假已读。

确认已读后，服务端会通过 WebSocket 把已读回执实时推送给发送方：

```json
{"type": "message_read", "messageId": 5, "readerId": 2, "readerName": "zhangsan", "readAt": "..."}
```

发送方界面上会把"✓ 已送达"更新为"✓✓ 已查收"。

## 全屏强提醒弹窗

安卓端收到开启了"弹窗"的消息时，会拉起一个全屏透明 Activity，并且：

- **拦截返回键**，无法通过返回键关闭弹窗
- 不提供"忽略/关闭"选项，**必须点击"已查收"才能离开该界面**
- 点击"查看会话"也会先完成已读确认，再跳转到聊天界面，避免绕开确认机制

如果服务端已读确认请求失败（例如网络抖动），不会把用户卡在弹窗界面，
而是放行离开，消息在服务端仍保留为未读，用户后续在聊天列表中仍可手动确认。

## 保活模式（手机 / 平板·电脑）

安卓端登录页提供一个手动选择（不自动判断设备类型）：

- **手机模式**（默认）：仅常规前台服务 + `START_STICKY`，尊重系统省电
  策略，不做额外抢占资源的操作。
- **平板 / Android电脑模式**：面向常电源供电、长期固定使用的"值班屏"
  场景，额外启用：
  - `PARTIAL_WAKE_LOCK`，防止 CPU 休眠导致 WebSocket 连接中断
  - `AlarmManager` 周期性看门狗（每 5 分钟自唤醒一次），检测前台服务
    是否存活，不在则重新拉起
  - 引导用户将本应用加入系统电池优化白名单（用户可在系统弹窗中拒绝，
    不强制）

两种模式都可以正常开机自启动（`BootReceiver`），看门狗仅在选择了
"平板/电脑"模式时才会被 `ConnectionService` 启动。

**强保活模式会明显增加耗电**，请仅在确实需要"消息绝对不漏接"的固定
值守设备上选用，手机日常使用建议保持默认的"手机模式"。

## 默认账号

| 用户名 | 密码 | 角色 |
|---|---|---|
| admin | admin123 | 管理员 |

**首次登录后请立即修改密码。**

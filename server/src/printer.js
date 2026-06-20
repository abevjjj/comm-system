// printer.js - ESC/POS 打印机通信模块
// 适用于 80mm 宽支持网络接口（TCP/IP 9100 端口）的热敏打印机
'use strict';

const net = require('node:net');

// ---- ESC/POS 常用指令常量 ----
const ESC = 0x1b;
const GS = 0x1d;
const FS = 0x1c;

const CMD = {
  INIT: Buffer.from([ESC, 0x40]),                    // 初始化打印机
  ALIGN_LEFT: Buffer.from([ESC, 0x61, 0x00]),
  ALIGN_CENTER: Buffer.from([ESC, 0x61, 0x01]),
  CUT: Buffer.from([GS, 0x56, 0x42, 0x00]),           // 走纸并切纸（partial cut）
  FEED_LINES: (n) => Buffer.from([ESC, 0x64, n]),     // 走纸n行
  // 字符放大倍率：GS ! n  (高4bit=高度倍率-1, 低4bit=宽度倍率-1)
  CHAR_SIZE: (widthMult, heightMult) => {
    const n = ((widthMult - 1) & 0x07) << 4 | ((heightMult - 1) & 0x07);
    return Buffer.from([GS, 0x21, n]);
  },
  // 中文模式开关（部分国产ESC/POS打印机使用 FS ! 设置汉字模式）
  CN_MODE_ON: Buffer.from([FS, 0x26]),
};

// 全局字体大小映射表，对应管理后台的设置项
const FONT_SIZE_MAP = {
  normal:        { w: 1, h: 1 },
  double_height: { w: 1, h: 2 },
  double_width:  { w: 2, h: 1 },
  double_both:   { w: 2, h: 2 },
};

/**
 * 将文本编码为 GB18030（兼容大多数国产热敏打印机的中文显示）
 */
function encodeGB18030(text) {
  // Node 原生不带 GB18030 编码，这里用 iconv-lite 会引入依赖；
  // 改为优先尝试，若环境无该模块则退化为 UTF-8（提示在 README 中说明）
  try {
    const iconv = require('iconv-lite');
    return iconv.encode(text, 'gb18030');
  } catch (e) {
    console.warn('[printer] 未安装 iconv-lite，使用 UTF-8 编码，国产热敏打印机可能出现中文乱码');
    return Buffer.from(text, 'utf8');
  }
}

/**
 * 构建一条消息的打印数据（ESC/POS字节流）
 * @param {object} opts
 * @param {string} opts.senderName  发送者用户名
 * @param {string} opts.content     消息正文
 * @param {string} opts.timestamp   时间字符串
 * @param {string} opts.fontSize    'normal'|'double_height'|'double_width'|'double_both'
 */
function buildPrintBuffer({ senderName, content, timestamp, fontSize = 'normal' }) {
  const size = FONT_SIZE_MAP[fontSize] || FONT_SIZE_MAP.normal;
  const parts = [];

  parts.push(CMD.INIT);
  parts.push(CMD.CN_MODE_ON);
  parts.push(CMD.ALIGN_LEFT);

  // 头部：发件人 + 时间（小字号，固定normal，便于多消息时统一对齐阅读）
  parts.push(CMD.CHAR_SIZE(1, 1));
  parts.push(encodeGB18030(`来自: ${senderName}\n`));
  parts.push(encodeGB18030(`时间: ${timestamp}\n`));
  parts.push(encodeGB18030('--------------------------------\n'));

  // 正文：使用管理员配置的字体大小
  parts.push(CMD.CHAR_SIZE(size.w, size.h));
  parts.push(encodeGB18030(content + '\n'));

  // 恢复正常字号，走纸后切纸
  parts.push(CMD.CHAR_SIZE(1, 1));
  parts.push(CMD.FEED_LINES(3));
  parts.push(CMD.CUT);

  return Buffer.concat(parts);
}

/**
 * 将打印数据发送到指定 IP:PORT 的热敏打印机
 * @returns {Promise<void>} 成功 resolve，失败 reject(Error)
 */
function sendToPrinter(ip, port, buffer, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    if (!ip) {
      reject(new Error('未配置打印机IP'));
      return;
    }
    const socket = new net.Socket();
    let settled = false;

    const finish = (err) => {
      if (settled) return;
      settled = true;
      socket.destroy();
      err ? reject(err) : resolve();
    };

    socket.setTimeout(timeoutMs);
    socket.once('timeout', () => finish(new Error(`连接打印机超时 (${ip}:${port})`)));
    socket.once('error', (err) => finish(new Error(`打印机连接错误: ${err.message}`)));

    socket.connect(port, ip, () => {
      socket.write(buffer, (err) => {
        if (err) {
          finish(new Error(`写入打印机数据失败: ${err.message}`));
          return;
        }
        // 写完即可视为成功，不强制等待对端响应（多数ESC/POS打印机无回应）
        finish(null);
      });
    });
  });
}

/**
 * 高层接口：打印一条消息
 */
async function printMessage({ printerIp, printerPort, senderName, content, timestamp, fontSize }) {
  const buffer = buildPrintBuffer({ senderName, content, timestamp, fontSize });
  await sendToPrinter(printerIp, printerPort || 9100, buffer);
}

module.exports = {
  buildPrintBuffer,
  sendToPrinter,
  printMessage,
  FONT_SIZE_MAP
};

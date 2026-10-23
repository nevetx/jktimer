'use strict';

/**
 * 區網搜尋 JK Timer 平板。
 * 三種方式互補：
 *  1. listen()：接收平板每 2 秒發出的 UDP 廣播（最快，但部分路由器會擋）
 *  2. scan()  ：逐一詢問同網段每個 IP 的 /api/info（不靠廣播，約數秒）
 *  3. probe() ：確認指定 IP 是否為 JK Timer（用於上次 IP、手動輸入、連線監測）
 * 以下常數必須與平板 App（DeviceIdentity.APP_ID、DiscoveryBeacon.DISCOVERY_PORT、RoomTimerApp.HTTP_PORT）一致。
 */

const dgram = require('dgram');
const os = require('os');

const APP_ID = 'jktimer-gen2';
const DISCOVERY_PORT = 41234;
const DEFAULT_HTTP_PORT = 8080;

/** 將平板回傳的身分資訊整理成統一格式 */
function toDevice(info, host, fallbackPort) {
  return {
    deviceId: String(info.deviceId || ''),
    deviceName: String(info.deviceName || '未命名平板'),
    version: String(info.version || ''),
    host,
    port: Number(info.port) || fallbackPort || DEFAULT_HTTP_PORT,
    lastSeen: Date.now(),
  };
}

/**
 * 詢問指定主機的 /api/info，確認是 JK Timer 才回傳裝置資訊，否則回傳 null。
 */
async function probe(host, port = DEFAULT_HTTP_PORT, timeoutMs = 1500) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const res = await fetch(`http://${host}:${port}/api/info`, { signal: controller.signal, cache: 'no-store' });
    if (!res.ok) return null;
    const info = await res.json();
    if (!info || info.app !== APP_ID || !info.deviceId) return null;
    return toDevice(info, host, port);
  } catch {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * 開始接收平板的 UDP 廣播；每收到一次就呼叫 onFound(device)。
 * 回傳 socket，呼叫端可在結束時 close()。
 * 若連接埠被占用或被防火牆阻擋，僅記錄警告，搜尋仍可靠 scan() 完成。
 */
function listen(onFound, onError) {
  const socket = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  socket.on('message', (msg, rinfo) => {
    try {
      const info = JSON.parse(msg.toString('utf8'));
      if (info && info.app === APP_ID && info.deviceId) onFound(toDevice(info, rinfo.address));
    } catch {
      // 非 JK Timer 的封包，忽略
    }
  });
  socket.on('error', (err) => {
    if (onError) onError(err);
    try { socket.close(); } catch { /* 已關閉 */ }
  });
  socket.bind(DISCOVERY_PORT);
  return socket;
}

function ipToInt(ip) {
  return ip.split('.').reduce((acc, part) => ((acc << 8) | (Number(part) & 255)) >>> 0, 0);
}

function intToIp(n) {
  return [24, 16, 8, 0].map((shift) => (n >>> shift) & 255).join('.');
}

function prefixLength(netmask) {
  return ipToInt(netmask).toString(2).replace(/0/g, '').length;
}

/**
 * 列出本機所在網段中需要掃描的 IP。
 * 網段大於 /24 時只掃描本機所在的 /24，避免掃描數千個位址；排除本機、網路位址、廣播位址與 169.254.x.x。
 */
function localScanHosts() {
  const hosts = new Set();
  const selfIps = new Set();
  for (const addrs of Object.values(os.networkInterfaces())) {
    for (const a of addrs || []) {
      if (a.family !== 'IPv4' || a.internal || a.address.startsWith('169.254.')) continue;
      selfIps.add(a.address);
      const prefix = Math.max(prefixLength(a.netmask), 24);
      if (prefix > 30) continue;
      const mask = prefix === 0 ? 0 : (0xffffffff << (32 - prefix)) >>> 0;
      const network = (ipToInt(a.address) & mask) >>> 0;
      const broadcast = (network | (~mask >>> 0)) >>> 0;
      for (let n = network + 1; n < broadcast; n++) hosts.add(intToIp(n));
    }
  }
  selfIps.forEach((ip) => hosts.delete(ip));
  return [...hosts];
}

/**
 * 掃描本機網段，找到 JK Timer 就呼叫 onFound(device)。
 * shouldStop() 回傳 true 時提前結束（例如已經連上）。
 */
async function scan(onFound, { port = DEFAULT_HTTP_PORT, concurrency = 64, timeoutMs = 800, shouldStop = () => false } = {}) {
  const hosts = localScanHosts();
  let index = 0;
  const worker = async () => {
    while (index < hosts.length && !shouldStop()) {
      const host = hosts[index++];
      const device = await probe(host, port, timeoutMs);
      if (device) onFound(device);
    }
  };
  await Promise.all(Array.from({ length: Math.min(concurrency, hosts.length) }, worker));
  return hosts.length;
}

/** 解析使用者手動輸入的「IP」或「IP:埠」 */
function parseHostInput(text) {
  const m = String(text || '').trim().match(/^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?$/);
  if (!m) return null;
  if (m[1].split('.').some((p) => Number(p) > 255)) return null;
  return { host: m[1], port: m[2] ? Number(m[2]) : DEFAULT_HTTP_PORT };
}

module.exports = { APP_ID, DISCOVERY_PORT, DEFAULT_HTTP_PORT, probe, listen, scan, localScanHosts, parseHostInput };

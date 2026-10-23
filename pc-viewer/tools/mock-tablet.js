'use strict';

/**
 * 模擬平板（開發測試用）：在沒有實體平板時測試檢視器的搜尋與連線流程。
 * 提供 /api/info、/api/status、/ 並每 2 秒發出 UDP 廣播，格式與平板 App 相同。
 *
 * 用法：node tools/mock-tablet.js [HTTP 埠] [裝置名稱]
 *   例：node tools/mock-tablet.js 18080 "測試平板 A"
 * 同時開兩個（不同埠與名稱）即可測試「多台平板」的選擇畫面。
 */

const http = require('http');
const dgram = require('dgram');
const crypto = require('crypto');
const { APP_ID, DISCOVERY_PORT } = require('../src/discovery');

const port = Number(process.argv[2]) || 18080;
const deviceName = process.argv[3] || `模擬平板 ${port}`;
const deviceId = crypto.createHash('md5').update(deviceName).digest('hex');

const info = () => ({ app: APP_ID, name: 'JK Timer Gen 2', version: 'mock', deviceId, deviceName, port });

const status = () => ({
  serverTime: Date.now(),
  deviceName,
  rooms: Array.from({ length: 10 }, (_, i) => ({
    room: i + 1, state: 'IDLE', remainingSeconds: 0, display: '--:--', label: null, labelShort: null, complete: false, todo: [],
  })),
  announceQueue: [],
});

const page = `<!DOCTYPE html><html lang="zh-Hant"><head><meta charset="utf-8"><title>${deviceName}</title></head>
<body style="background:#15181d;color:#fff;font-family:sans-serif;padding:40px">
<h1>模擬平板：${deviceName}</h1><p id="tts">語音：尚未啟用</p>
<script>function unlockTts(){document.getElementById("tts").textContent="語音：已由檢視器自動啟用";}</script>
</body></html>`;

http.createServer((req, res) => {
  const send = (code, type, body) => {
    res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });
    res.end(body);
  };
  if (req.url === '/api/info') return send(200, 'application/json; charset=utf-8', JSON.stringify(info()));
  if (req.url === '/api/status') return send(200, 'application/json; charset=utf-8', JSON.stringify(status()));
  if (req.url === '/' || req.url === '/index.html') return send(200, 'text/html; charset=utf-8', page);
  return send(404, 'text/plain', 'Not found');
}).listen(port, () => console.log(`[mock] HTTP http://0.0.0.0:${port}/  name=${deviceName}  id=${deviceId}`));

const beacon = dgram.createSocket('udp4');
beacon.bind(() => {
  beacon.setBroadcast(true);
  setInterval(() => {
    const data = Buffer.from(JSON.stringify(info()));
    ['255.255.255.255', '127.0.0.1'].forEach((addr) => beacon.send(data, DISCOVERY_PORT, addr));
  }, 2000);
  console.log(`[mock] UDP beacon -> :${DISCOVERY_PORT} every 2s`);
});

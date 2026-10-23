'use strict';

const { app, BrowserWindow, Tray, Menu, ipcMain, powerSaveBlocker, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const discovery = require('./discovery');

// ===== 背景不降頻、允許自動播放語音（必須在 app ready 之前設定）=====
// 一般瀏覽器會把背景 / 被遮蔽的視窗降頻，且要求使用者先互動才能發聲；檢視器需要長時間在背景可靠播報
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.commandLine.appendSwitch('disable-background-timer-throttling');
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');

const DECIDE_DELAY_MS = 4500;        // 等待平板廣播的時間（平板每 2 秒廣播一次）
const RESCAN_INTERVAL_MS = 15000;    // 找不到平板時，每隔多久重新掃描網段
const MONITOR_INTERVAL_MS = 3000;    // 連線中每隔多久確認平板仍在線
const MONITOR_MAX_FAILURES = 5;      // 連續失敗幾次（約 15 秒）視為斷線並重新搜尋
const DEVICE_STALE_MS = 60000;       // 超過此時間未再看到的平板不列入選單
const BEACON_RETRY_MS = 10000;       // UDP 監聽失敗（例如連接埠被占用）時的重試間隔

const ICON_PATH = path.join(__dirname, '..', 'assets', 'icon.png');
const LAUNCHER_PATH = path.join(__dirname, 'launcher.html');

let win = null;
let tray = null;
let isQuitting = false;
let hiddenHintShown = false;
let settings = {};

const devices = new Map();           // deviceId -> 裝置資訊
const state = {
  mode: 'searching',                 // searching | choosing | connected
  message: '',
  scanning: false,
  forceChoose: false,                // 使用者主動「切換平板」：不自動連線，一律讓使用者選
  connected: null,                   // 目前連線中的裝置
};
let searchToken = 0;                 // 每次開始新搜尋或連線時遞增，讓舊的搜尋流程自動停止
let decideTimer = null;
let rescanTimer = null;
let monitorTimer = null;
let monitorFailures = 0;

// ===== 設定檔（記住上次連線的平板與開機啟動偏好）=====
function settingsPath() {
  return path.join(app.getPath('userData'), 'settings.json');
}

function loadSettings() {
  try {
    settings = JSON.parse(fs.readFileSync(settingsPath(), 'utf8')) || {};
  } catch {
    settings = {};
  }
}

function saveSettings() {
  try {
    fs.mkdirSync(path.dirname(settingsPath()), { recursive: true });
    fs.writeFileSync(settingsPath(), JSON.stringify(settings, null, 2));
  } catch (err) {
    console.warn('儲存設定失敗', err);
  }
}

// ===== 開機自動啟動 =====
// 免安裝版每次執行會解壓到暫存資料夾，必須登記原始 exe 的路徑（electron-builder 提供 PORTABLE_EXECUTABLE_FILE）
function exePath() {
  return process.env.PORTABLE_EXECUTABLE_FILE || process.execPath;
}

function isAutoStart() {
  return app.isPackaged && app.getLoginItemSettings({ path: exePath() }).openAtLogin;
}

function setAutoStart(enable) {
  if (!app.isPackaged) return;  // 開發模式不登記，避免把 electron.exe 加入開機啟動
  app.setLoginItemSettings({ openAtLogin: enable, path: exePath() });
  settings.autoStart = enable;
  saveSettings();
  updateTray();
}

// ===== 啟動頁（搜尋 / 選擇平板）狀態 =====
function isOnLauncher() {
  return !!win && !win.isDestroyed() && win.webContents.getURL().startsWith('file:');
}

function buildState() {
  const now = Date.now();
  return {
    mode: state.mode,
    message: state.message,
    scanning: state.scanning,
    savedDeviceId: settings.deviceId || null,
    devices: [...devices.values()]
      .filter((d) => now - d.lastSeen < DEVICE_STALE_MS)
      .sort((a, b) => a.deviceName.localeCompare(b.deviceName)),
  };
}

function pushState() {
  if (isOnLauncher()) win.webContents.send('viewer:state', buildState());
}

function showLauncher() {
  if (!win || win.isDestroyed()) return;
  if (isOnLauncher()) pushState();
  else win.loadFile(LAUNCHER_PATH).catch(() => {});
}

// ===== 搜尋流程 =====
function onDeviceFound(device) {
  devices.set(device.deviceId, device);

  if (state.mode === 'connected') {
    // 連線中的平板換了 IP（目前位址已連不上）：直接切換到廣播中的新位址
    const c = state.connected;
    if (c && device.deviceId === c.deviceId && device.host !== c.host && monitorFailures > 0) connectTo(device);
    return;
  }
  // 找到上次使用的平板：立即連線
  if (!state.forceChoose && settings.deviceId && device.deviceId === settings.deviceId) {
    connectTo(device);
    return;
  }
  pushState();
}

function startSearch({ forceChoose = false, message } = {}) {
  stopMonitor();
  clearTimeout(decideTimer);
  clearTimeout(rescanTimer);
  const token = ++searchToken;
  state.mode = 'searching';
  state.connected = null;
  state.forceChoose = forceChoose;
  state.message = message || '正在區網中搜尋平板…';
  updateTray();
  showLauncher();

  // 1. 先試上次的 IP（通常瞬間就能連上）
  if (!forceChoose && settings.lastHost) {
    discovery.probe(settings.lastHost, settings.lastPort).then((d) => {
      if (d && token === searchToken) onDeviceFound(d);
    });
  }
  // 2. UDP 廣播於程式啟動時即持續接收（見 startBeaconListener）
  // 3. 同時掃描網段
  runScan(token);
  decideTimer = setTimeout(() => decide(token), DECIDE_DELAY_MS);
}

async function runScan(token) {
  state.scanning = true;
  pushState();
  try {
    await discovery.scan(onDeviceFound, { shouldStop: () => token !== searchToken });
  } catch (err) {
    console.warn('掃描網段失敗', err);
  }
  if (token !== searchToken) return;
  state.scanning = false;
  decide(token);
  rescanTimer = setTimeout(() => {
    if (token === searchToken) runScan(token);
  }, RESCAN_INTERVAL_MS);
}

/** 依目前找到的平板決定：自動連線、讓使用者選擇，或繼續搜尋 */
function decide(token) {
  if (token !== searchToken || state.mode === 'connected') return;
  const list = buildState().devices;

  if (!state.forceChoose && settings.deviceId) {
    // 已記住某台平板：只自動連那一台，避免連錯分店 / 備用平板
    const name = settings.deviceName || '上次使用的平板';
    state.mode = list.length ? 'choosing' : 'searching';
    state.message = list.length
      ? `找不到上次使用的平板「${name}」，仍在持續搜尋；也可以改選下方其他平板。`
      : `找不到上次使用的平板「${name}」，持續搜尋中…`;
  } else if (!state.forceChoose && list.length === 1) {
    connectTo(list[0]);
    return;
  } else if (list.length > 0) {
    state.mode = 'choosing';
    state.message = state.forceChoose
      ? '請選擇要連線的平板'
      : '區網中找到多台平板，請選擇要連線的一台（之後會自動記住）';
  } else {
    state.mode = 'searching';
    state.message = '尚未找到平板，持續搜尋中…';
  }
  pushState();
}

function connectTo(device) {
  clearTimeout(decideTimer);
  clearTimeout(rescanTimer);
  searchToken++;  // 停止進行中的搜尋
  state.mode = 'connected';
  state.connected = device;
  state.forceChoose = false;
  state.scanning = false;
  Object.assign(settings, {
    deviceId: device.deviceId,
    deviceName: device.deviceName,
    lastHost: device.host,
    lastPort: device.port,
  });
  saveSettings();
  updateTray();
  win.loadURL(`http://${device.host}:${device.port}/`).catch(() => {});  // 失敗由 did-fail-load 處理
  startMonitor();
}

// ===== 連線監測：平板關機、斷網或換 IP 時自動重新搜尋 =====
function startMonitor() {
  stopMonitor();
  monitorFailures = 0;
  monitorTimer = setInterval(async () => {
    const c = state.connected;
    if (!c) return;
    const d = await discovery.probe(c.host, c.port, 2000);
    if (state.connected !== c) return;
    if (d && d.deviceId === c.deviceId) {
      monitorFailures = 0;
      if (d.deviceName !== settings.deviceName) {
        settings.deviceName = d.deviceName;
        c.deviceName = d.deviceName;
        saveSettings();
        updateTray();
      }
      return;
    }
    monitorFailures++;
    if (monitorFailures >= MONITOR_MAX_FAILURES) {
      startSearch({ message: `與平板「${c.deviceName}」失去連線，重新搜尋中…` });
    }
  }, MONITOR_INTERVAL_MS);
}

function stopMonitor() {
  clearInterval(monitorTimer);
  monitorTimer = null;
}

function startBeaconListener() {
  discovery.listen(onDeviceFound, (err) => {
    console.warn('UDP 廣播監聽失敗，稍後重試（搜尋仍會以網段掃描進行）', err.message);
    setTimeout(startBeaconListener, BEACON_RETRY_MS);
  });
}

// ===== 視窗 =====
function showWindow() {
  if (!win || win.isDestroyed()) return;
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function createWindow() {
  win = new BrowserWindow({
    width: 1366,
    height: 800,
    minWidth: 800,
    minHeight: 500,
    show: false,
    backgroundColor: '#15181d',
    title: app.getName(),
    icon: ICON_PATH,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false,  // 視窗在背景 / 被遮蔽 / 隱藏時仍正常執行計時與語音
    },
  });
  win.removeMenu();
  win.once('ready-to-show', () => {
    win.maximize();
    win.show();
  });

  // 按 X 不結束，只隱藏到系統列，避免員工誤關而錯過播報
  win.on('close', (e) => {
    if (isQuitting) return;
    e.preventDefault();
    win.hide();
    if (!hiddenHintShown && tray) {
      hiddenHintShown = true;
      tray.displayBalloon({
        iconType: 'info',
        title: app.getName(),
        content: '程式仍在背景執行並持續語音播報。要結束請在右下角圖示按右鍵選「結束」。',
      });
    }
  });

  const wc = win.webContents;
  wc.setWindowOpenHandler(() => ({ action: 'deny' }));
  wc.on('did-finish-load', () => {
    if (isOnLauncher()) {
      pushState();
    } else if (state.mode === 'connected') {
      // 以「使用者手勢」身分啟用儀表板的語音（等同使用者點了一下），不需員工手動點擊
      wc.executeJavaScript('typeof unlockTts === "function" && unlockTts();', true).catch(() => {});
    }
  });
  wc.on('did-fail-load', (_e, code, _desc, url, isMainFrame) => {
    // -3 (ERR_ABORTED) 為切換頁面時取消前一次載入，屬正常情況
    if (!isMainFrame || code === -3) return;
    if (state.mode === 'connected' && url.startsWith('http')) {
      startSearch({ message: '無法載入平板畫面，重新搜尋中…' });
    }
  });
  // 頁面程序意外結束時自動重新載入
  wc.on('render-process-gone', () => {
    setTimeout(() => {
      if (!win.isDestroyed()) wc.reload();
    }, 1000);
  });
}

// ===== 系統列圖示 =====
function createTray() {
  tray = new Tray(nativeImage.createFromPath(ICON_PATH).resize({ width: 16, height: 16 }));
  tray.on('click', showWindow);
  updateTray();
}

function updateTray() {
  if (!tray) return;
  const c = state.connected;
  tray.setToolTip(c ? `${app.getName()}\n已連線：${c.deviceName}（${c.host}）` : `${app.getName()}\n搜尋平板中…`);
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: c ? `已連線：${c.deviceName}（${c.host}）` : '尚未連線', enabled: false },
    { type: 'separator' },
    { label: '顯示視窗', click: showWindow },
    { label: '重新搜尋平板', click: () => { showWindow(); startSearch(); } },
    { label: '切換平板…', click: () => { showWindow(); startSearch({ forceChoose: true, message: '請選擇要連線的平板' }); } },
    { type: 'separator' },
    {
      label: '開機時自動啟動',
      type: 'checkbox',
      checked: isAutoStart(),
      enabled: app.isPackaged,
      click: (item) => setAutoStart(item.checked),
    },
    { type: 'separator' },
    { label: '結束（將停止 PC 語音播報）', click: () => { isQuitting = true; app.quit(); } },
  ]));
}

// ===== 啟動頁的操作（僅接受本機啟動頁發出的請求）=====
function fromLauncher(e) {
  return !!e.senderFrame && e.senderFrame.url.startsWith('file:');
}

ipcMain.handle('viewer:getState', (e) => (fromLauncher(e) ? buildState() : null));

ipcMain.handle('viewer:connect', (e, deviceId) => {
  if (!fromLauncher(e)) return false;
  const d = devices.get(deviceId);
  if (d) connectTo(d);
  return !!d;
});

ipcMain.handle('viewer:connectManual', async (e, text) => {
  if (!fromLauncher(e)) return { ok: false };
  const target = discovery.parseHostInput(text);
  if (!target) return { ok: false, error: '格式不正確，請輸入例如 192.168.1.50 或 192.168.1.50:8080' };
  const d = await discovery.probe(target.host, target.port, 3000);
  if (!d) return { ok: false, error: `在 ${target.host}:${target.port} 找不到 JK Timer，請確認 IP 與平板狀態` };
  devices.set(d.deviceId, d);
  connectTo(d);
  return { ok: true };
});

ipcMain.handle('viewer:rescan', (e) => {
  if (fromLauncher(e)) startSearch({ forceChoose: state.forceChoose, message: state.forceChoose ? '請選擇要連線的平板' : undefined });
});

// ===== 程式進入點 =====
if (!app.requestSingleInstanceLock()) {
  // 已有一個檢視器在執行：交給它顯示視窗，本程序直接結束
  app.quit();
} else {
  app.on('second-instance', showWindow);
  app.on('before-quit', () => { isQuitting = true; });

  app.whenReady().then(() => {
    loadSettings();
    // 執行期間阻止 Windows 因閒置進入睡眠（螢幕仍可關閉）
    powerSaveBlocker.start('prevent-app-suspension');
    // 首次執行預設開機啟動；之後每次啟動以目前 exe 路徑更新登記（免安裝版被搬移位置時仍有效）
    if (settings.autoStart === undefined || settings.autoStart) setAutoStart(true);

    createWindow();
    createTray();
    startBeaconListener();
    startSearch();
  });
}

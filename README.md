# RoomTimer

Android 平板用的 10 房間倒數計時器（Kotlin + Jetpack Compose），內建唯讀 HTTP 伺服器。

- 平板：安裝 `app-debug.apk`，開啟後畫面頂部會顯示 PC 檢視網址。
- PC（同一 LAN）：建議使用 PC 檢視器（見下方 `pc-viewer`）；也可用瀏覽器打開 `http://<平板IP>:8080/`（即時儀表板，每 0.5 秒更新）。只接受 GET/HEAD，無任何操作接口。
- API：`GET /api/status`（房間狀態 JSON）、`GET /api/info`（平板身分：`app`、`version`、`deviceId`、`deviceName`、`port`）。
- 區網廣播：平板每 2 秒以 UDP 廣播身分資訊至埠 `41234`（`DiscoveryBeacon.kt`），供 PC 檢視器自動搜尋。

## TTS 規則
- Room TTS：`Room N, Time is up.`（每次到 0 報讀 1 次，優先）
- Global TTS：`Room 5, 3, 7`（按到時先後排序）
- Room TTS 讀完：若有其他 Room TTS 排隊即刻讀；否則 3 秒後讀 Global TTS
- Global TTS 讀完：5 秒後再讀（期間有 Room TTS 會先讀）；陣列空了就停
- 常數位於 `TtsScheduler.kt`（`AFTER_ROOM_MS`、`GLOBAL_REPEAT_MS`）
- PC 網頁儀表板以瀏覽器語音（Web Speech API）依相同規則播報，常數直接取自 `TtsScheduler`；語音固定開啟、無法關閉；瀏覽器需使用者先點擊頁面一次才能發聲；以 Web Worker 驅動輪詢，降低背景分頁被降頻的影響

## 建置
```
./gradlew assembleDebug
```
輸出：`app/build/outputs/apk/debug/app-debug.apk`

## PC 檢視器（pc-viewer，Electron）
唯讀顯示平板儀表板並語音播報，專為長時間在背景執行設計：
- 自動搜尋平板：上次 IP → UDP 廣播 → 掃描本機網段（/24）→ 手動輸入 IP；區網有多台平板時讓使用者選擇並記住（以 `deviceId` 辨識，平板換 IP 仍能自動跟上）
- 斷線約 15 秒自動重新搜尋；平板換 IP 時依廣播自動切換
- 背景不降頻、免點擊即可發聲、執行期間阻止 Windows 閒置睡眠
- 按 X 縮到系統列（右下角圖示右鍵可「切換平板」「開機時自動啟動」「結束」）；預設開機自動啟動
- 設定檔：`%APPDATA%\JK Timer Gen 2 Viewer\settings.json`

```
cd pc-viewer
npm install
npm start          # 開發執行
npm run dist       # 打包免安裝版 → dist/JKTimerViewer-<版本>-portable.exe
node tools/mock-tablet.js 18080 "測試平板 A"   # 沒有平板時用模擬平板測試
```
注意：
- 若在由 Electron 開發的工具（例如部分 IDE 終端機）中執行，需先清除環境變數 `ELECTRON_RUN_AS_NODE`，否則 Electron 會以純 Node 模式執行。
- 首次執行時 Windows 防火牆會詢問是否允許網路存取，請選「允許」（私人網路），否則只能靠網段掃描搜尋。
- exe 未經數位簽章，首次執行 Windows SmartScreen 可能出現警告，按「其他資訊 → 仍要執行」。
- 搜尋相關常數（`APP_ID`、`DISCOVERY_PORT`、HTTP 埠）需與平板 App 一致，位於 `pc-viewer/src/discovery.js`。

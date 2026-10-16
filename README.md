# RoomTimer

Android 平板用的 10 房間倒數計時器（Kotlin + Jetpack Compose），內建唯讀 HTTP 伺服器。

- 平板：安裝 `app-debug.apk`，開啟後畫面頂部會顯示 PC 檢視網址。
- PC（同一 LAN）：瀏覽器打開 `http://<平板IP>:8080/`（即時儀表板，每 0.5 秒更新），或 `GET /api/status` 取得 JSON。只接受 GET/HEAD，無任何操作接口。

## TTS 規則
- Room TTS：`Room N, Time is up.`（每次到 0 報讀 1 次，優先）
- Global TTS：`Room 5, 3, 7`（按到時先後排序）
- Room TTS 讀完：若有其他 Room TTS 排隊即刻讀；否則 3 秒後讀 Global TTS
- Global TTS 讀完：5 秒後再讀（期間有 Room TTS 會先讀）；陣列空了就停
- 常數位於 `TtsScheduler.kt`（`AFTER_ROOM_MS`、`GLOBAL_REPEAT_MS`）

## 建置
```
./gradlew assembleDebug
```
輸出：`app/build/outputs/apk/debug/app-debug.apk`

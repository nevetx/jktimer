package com.nevetx.roomtimer

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** Read-only HTTP server: "/" shows a live dashboard, "/api/status" returns JSON. No control endpoints. */
class StatusHttpServer(val port: Int) {
    private val pool = Executors.newFixedThreadPool(8)
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        thread(isDaemon = true, name = "http-accept") {
            while (true) {
                try {
                    ServerSocket().use { ss ->
                        ss.reuseAddress = true
                        ss.bind(InetSocketAddress(port))
                        while (true) {
                            val s = ss.accept()
                            pool.execute { handle(s) }
                        }
                    }
                } catch (e: IOException) {
                    Log.e("StatusHttpServer", "server error, retrying", e)
                    Thread.sleep(3000)
                }
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
                val requestLine = reader.readLine() ?: return
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: ""
                val path = parts.getOrNull(1)?.substringBefore('?') ?: "/"
                val out = s.getOutputStream()
                if (method != "GET" && method != "HEAD") {
                    respond(out, 405, "Method Not Allowed", "text/plain; charset=utf-8", "Read only".toByteArray(), method == "HEAD")
                    return
                }
                when (path) {
                    "/", "/index.html" -> respond(out, 200, "OK", "text/html; charset=utf-8", DASHBOARD_HTML.toByteArray(), method == "HEAD")
                    "/api/status" -> respond(out, 200, "OK", "application/json; charset=utf-8", statusJson().toByteArray(), method == "HEAD")
                    "/api/info" -> respond(out, 200, "OK", "application/json; charset=utf-8", DeviceIdentity.infoJson().toString().toByteArray(), method == "HEAD")
                    else -> respond(out, 404, "Not Found", "text/plain; charset=utf-8", "Not found".toByteArray(), method == "HEAD")
                }
            }
        } catch (e: IOException) {
            // client went away
        }
    }

    private fun respond(out: java.io.OutputStream, code: Int, reason: String, type: String, body: ByteArray, headOnly: Boolean) {
        val header = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun statusJson(): String {
        val now = System.currentTimeMillis()
        val rooms = JSONArray()
        TimerController.rooms.value.forEach { r ->
            rooms.put(JSONObject().apply {
                put("room", r.room)
                put("state", r.phase.name)
                put("remainingSeconds", r.remainingSec)
                put("display", r.display)
                put("label", r.label?.full ?: JSONObject.NULL)
                put("labelShort", r.label?.short ?: JSONObject.NULL)
                put("complete", r.complete)
                // 時間到後經過的毫秒數（由平板計算，PC 端不受電腦時鐘誤差影響）；非時間到為 null
                put("expiredElapsedMs", if (r.expiredAt > 0) (now - r.expiredAt).coerceAtLeast(0) else JSONObject.NULL)
                put("todo", JSONArray().apply {
                    r.todos.forEach { t ->
                        put(JSONObject().apply {
                            put("name", t.item.full)
                            put("short", t.item.short)
                            put("done", t.done)
                        })
                    }
                })
            })
        }
        return JSONObject().apply {
            put("serverTime", now)
            put("deviceName", DeviceIdentity.name)
            put("rooms", rooms)
            put("announceQueue", JSONArray(TimerController.announceQueue.value))
        }.toString()
    }

    companion object {
        fun localIpv4Addresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" } }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }

        private val DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="zh-Hant">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${RoomTimerApp.DISPLAY_TITLE}</title>
<link rel="icon" type="image/png" href="data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAAXNSR0IArs4c6QAAAARnQU1BAACxjwv8YQUAAAAJcEhZcwAADsMAAA7DAcdvqGQAAAOrSURBVDhPJY79L9x3AMe/v4gfFslaGjEWWcwErVZNyzyNiHVFp6c6VBVjncfWUz3VfA7l6lkPd1MP53BHPdSVUo93nCCcrmPrZssWe8j6y/6AJVvyWjo/v155vd+SvcvZUEc3f2F9wlVY27sLa0dPYePsLY65+Aq3gGhx/ZZcuAdEimMu54WNs4+wcvAUVq89W1fh4OYvJPt3fRudToaQXiRo14zRPfKEDu048vYe1HoDM2YLKt0k1cpeHgwc8bb+UTIKBU6eIUivl6/nlNGpm0Y3s8zy9h7aqXnG5sxMGHdYefEDk8ZtdNMraB/PM7O2Q9/4LJ26KZJzypGsbF1ERZOKkgYVEfHZyFv62Dr4nmHTOsoFExN7+3TOGumdXeL5bz9T2dpDUEwaxYouKhq7jh6UKJS0ayfIKlPQsb2I5nczg3ubDH5rYeuvP9DuWzAcvqD3JxOa2XmySu/Tpp2kuF6JZOPoKXKrmrhd2cLVBjkf6WvQHJoR8xPsvvqFv//9h6aXi9zfnyMlXhB2LYdUtYL8qjZyv2xEOubiLa5llVParOJidQkJmnpk081kaR7SsfYM46/fUT6l4+PUMt6/kE6CTkF0XQllLWoSs8qRbF19RNzNIhTqYfSra4TLC7mhbSK4ooyGmSk2Xv3IucwC7M7KSBtu5ULDHUaMJpp7HpGYXYpk735efJKaR3V7HwajhZFVE+E1RQSWlnBHq0H/jRm/3CKOe8QQmJqN3mxiYmEThWqIKxkFSPZuviL06k3qv9LR3GtA3qZnYmOdc7eKuKxsInNnjHh1K3GZVTjbRdHdb8CwusO9riHCP/0C6W1Pf+EeFENZo4rIJDmSdSjeVwqwi00idaCTpT8PiGmoo2dxnmCfzzjjJGPw8TL5tUpOhciOAl6hMoIup9Da94iotFqOByRxKi2fUv0QYepqBlaM7B4copsxUlXby702DUGyNE6HyZBs3zktwuIyiEzIpv6BhoV1C9NLG5i291jZfI68u5/FdQuL5l1mV3eYX/+axq4h4lKLiIjLQHrDwVXIkvOoqVFTmF+HSj2KfvAp009MjOuesWV5yezTdaamVtCPztGl0lNR0kS9opfY5DwkmxMuIjQ2jcrWbmIzCvAIjCIht5xWzTi6OROD04uMLphR6gwk5lbg9sFF4j4v5G7LQz6MuYH0psN7je5+EeRW1pNZWotXyCXe8vTHKySKSyk5JGQVE5t+G++wGBw8/DgZHE3OXcX/fkhUAv8BW3tmjTRMDdgAAAAASUVORK5CYII=">
<style>
  * { box-sizing: border-box; }
  body { margin: 0; font-family: "Segoe UI", "Microsoft JhengHei", sans-serif; background: #15181d; color: #fff; }
  header { display: flex; align-items: center; gap: 24px; padding: 10px 16px; background: #0d0f12; }
  header h1 { font-size: 22px; margin: 0; }
  #dev { font-size: 14px; color: #b0bec5; }
  #queue { font-size: 18px; color: #ffb4b4; }
  #conn { margin-left: auto; font-size: 14px; color: #8f8; }
  #conn.bad { color: #f66; }
  #ttsHint { display: none; padding: 8px 16px; background: #ff8f00; color: #000; font-weight: bold; text-align: center; cursor: pointer; }
  .grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; padding: 12px; }
  .card { border-radius: 12px; padding: 12px 16px; background: #2b2f36; min-height: 170px; display: flex; flex-direction: column; }
  .card.RUNNING { background: rgba(27, 94, 32, 0.8); }
  .card.EXPIRED { background: #d32f2f; }
  /* 標籤：與 App 一致，左上角 44px 圓形、白色外框、顯示簡稱；未選標籤時為灰色「選」 */
  .label { position: absolute; left: 16px; top: 12px; z-index: 4; width: 44px; height: 44px; border-radius: 50%;
           border: 2px solid #fff; background: #455a64; color: #fff; font-size: 20px; font-weight: bold;
           display: flex; align-items: center; justify-content: center; }
  .cat-腰 { background: linear-gradient(145deg, #0b64a0, #083552); }
  .cat-頸 { background: linear-gradient(145deg, #3498db, #135a88); }
  .cat-針 { background: linear-gradient(145deg, #ff5e4d, #c0392b); }
  .cat-罐 { background: linear-gradient(145deg, #f2aed1, #8e44ad); }
  .cat-電 { background: linear-gradient(145deg, #b684ef, #780ff1); }
  .cat-紅 { background: linear-gradient(145deg, #12f3d1, #0b6053); }
  .cat-敷 { background: linear-gradient(145deg, #606060, #000); }
  .cat-他 { background: linear-gradient(145deg, #95a5a6, #2c3e50); }
  .cat-手 { background: linear-gradient(145deg, #8e4e2c, #5a2d14); }
  .cat-超 { background: linear-gradient(145deg, #8cf0f2, #4a9696); }
  .cat-衝 { background: linear-gradient(145deg, #e23df5, #8a1e8c); }
  .todos { position: absolute; left: 16px; top: 62px; bottom: 12px; z-index: 3; display: flex; flex-direction: column; flex-wrap: wrap;
           align-content: flex-start; gap: 3px; pointer-events: none; }
  /* ToDo 圖示：與 App 一致，直徑為標籤的 50%（22px） */
  .todo { position: relative; width: 22px; height: 22px; }
  .todo .ic { display: flex; width: 100%; height: 100%; border-radius: 50%; align-items: center; justify-content: center;
              font-size: 13px; line-height: 1; font-weight: bold; color: #fff; border: 1px solid rgba(255,255,255,0.25); }
  .todo.done .ic { opacity: 0.35; }
  .todo .ck { position: absolute; right: -2px; bottom: -2px; width: 11px; height: 11px; border-radius: 50%; background: #43a047;
              border: 1px solid #fff; color: #fff; font-size: 8px; line-height: 9px; text-align: center; font-style: normal; }
  .time { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; font-family: Consolas, monospace; font-weight: bold; }
  .time .minutes, .time .seconds { font-size: clamp(64px, 7.5vw, 140px); line-height: 0.85; margin: 0; padding: 0; }
  .time .minutes { color: #a98beb; }
  .time .seconds { color: #5b9bea; }
  .card.IDLE .time { opacity: 0.45; }
  .card { position: relative; }
  .room-watermark { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); font-size: 120px; font-weight: bold; color: rgba(255,255,255,0.06); pointer-events: none; z-index: 0; }
  .time { position: relative; z-index: 2; }
  .complete { position: absolute; right: 8px; bottom: 22px; z-index: 3; transform: rotate(-25deg); padding: 4px 12px; border-radius: 6px;
              font-size: 15px; font-weight: bold; letter-spacing: 2px; pointer-events: none;
              color: rgba(255,255,255,0.2); background: rgba(255,255,255,0.06); border: 1px solid rgba(255,255,255,0.12); }
  .complete.on { color: #e8f5e9; background: linear-gradient(135deg, #7cc67f, #4caf50); border-color: #a5d6a7; box-shadow: 0 0 12px rgba(102,255,102,0.6); opacity: 0.6; }
  /* ===== 時間到特效：火焰由下往上燒（以 Canvas 繪製，演算法與 App 的 RoomEffects.kt 相同，3 分鐘到頂），燒滿後卡片震動 ===== */
  .fx { display: none; position: absolute; inset: 0; border-radius: 12px; overflow: hidden; pointer-events: none; z-index: 1; }
  .card.EXPIRED .fx { display: block; }
  .flame { position: absolute; inset: 0; width: 100%; height: 100%; }
  /* 震動：每秒前 45% 快速抖動並逐漸減弱，其餘時間靜止（與 App 一致） */
  .card.shake { animation: shake 1s linear infinite; }
  @keyframes shake {
    0% { transform: none; }
    4% { transform: translateX(5px) rotate(0.8deg); }
    8% { transform: translateX(-5px) rotate(-0.8deg); }
    12% { transform: translateX(4px) rotate(0.6deg); }
    16% { transform: translateX(-4px) rotate(-0.6deg); }
    20% { transform: translateX(3px) rotate(0.5deg); }
    24% { transform: translateX(-3px) rotate(-0.5deg); }
    28% { transform: translateX(2px) rotate(0.3deg); }
    32% { transform: translateX(-2px) rotate(-0.3deg); }
    36% { transform: translateX(1px) rotate(0.1deg); }
    40% { transform: translateX(-1px) rotate(-0.1deg); }
    45%, 100% { transform: none; }
  }
  @media (max-width: 1100px) { .grid { grid-template-columns: repeat(2, 1fr); } }
</style>
</head>
<body>
<header>
  <h1>${RoomTimerApp.DISPLAY_TITLE}</h1>
  <div id="dev"></div>
  <div id="queue"></div>
  <div id="conn">連線中…</div>
</header>
<div id="ttsHint">瀏覽器需要先互動才能播放語音，請點擊畫面任意位置以啟用語音播報</div>
<div class="grid" id="grid"></div>
<script>
  const grid = document.getElementById("grid");
  const cards = [];
  const FLAME_MS = ${TimerController.FLAME_DURATION_MS};  // 火焰燒到頂部所需時間（與 App 共用）
  for (let i = 1; i <= 10; i++) {
    const c = document.createElement("div");
    c.className = "card IDLE";
    c.innerHTML = '<div class="room-watermark">' + i + '</div>' +
                  '<div class="fx"><canvas class="flame"></canvas></div>' +
                  '<div class="label" style="display:none"></div>' +
                  '<div class="time"><div class="minutes">00</div><div class="seconds">00</div></div>' +
                  '<div class="todos"></div>' +
                  '<div class="complete">COMPLETE</div>';
    grid.appendChild(c);
    cards.push(c);
  }
  // ===== 火焰特效（演算法、顏色與 App 的 RoomEffects.kt 一致）=====
  // 外層火焰：頂端淡黃 → 中段橙 → 底部深橙紅；內層火芯：較亮的黃色
  const FLAME_COLORS = ["rgba(255,213,79,0.40)", "rgba(255,160,0,0.80)", "rgba(255,109,0,0.90)"];
  const CORE_COLORS = ["rgba(255,245,157,0.25)", "rgba(255,213,79,0.70)"];
  const MAX_TONGUE_PX = 48;   // 火舌最大高度（CSS 像素）
  const FLAME_STEPS = 40;     // 火焰輪廓取樣點數

  /** 0~1 的波形；speed 為整數，確保動畫時鐘由 1 回到 0 時畫面連續不跳動 */
  function wave(phase, t, speed) {
    return (Math.sin(2 * Math.PI * (phase + speed * t)) + 1) / 2;
  }

  /** 火焰輪廓：底部填滿，頂部為多組不同頻率的正弦波疊加而成的跳動火舌 */
  function flamePath(ctx, w, h, top, tongue, t, seed) {
    ctx.beginPath();
    ctx.moveTo(0, h);
    for (let s = 0; s <= FLAME_STEPS; s++) {
      const fx = s / FLAME_STEPS;
      const n = 0.55 * wave(fx * 7 + seed, t, 1) +
                0.30 * wave(fx * 13 - seed, t, -2) +
                0.15 * wave(fx * 23 + seed * 2, t, 3);
      ctx.lineTo(fx * w, Math.min(h, top - tongue * n));
    }
    ctx.lineTo(w, h);
    ctx.closePath();
  }

  function fillGradient(ctx, colors, y0, y1) {
    const g = ctx.createLinearGradient(0, y0, 0, y1);
    colors.forEach((color, i) => g.addColorStop(i / (colors.length - 1), color));
    ctx.fillStyle = g;
    ctx.fill();
  }

  function drawFlame(canvas, progress, t) {
    // 依實際顯示大小與螢幕縮放設定畫布解析度，避免模糊
    const dpr = window.devicePixelRatio || 1;
    const w = Math.round(canvas.clientWidth * dpr);
    const h = Math.round(canvas.clientHeight * dpr);
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    const ctx = canvas.getContext("2d");
    ctx.clearRect(0, 0, w, h);
    if (progress <= 0 || w === 0 || h === 0) return;
    // 火舌高度隨火勢增長，避免剛開始時就出現過高的火舌
    const tongue = Math.min(h * 0.14, MAX_TONGUE_PX * dpr) * (0.5 + 0.5 * progress);
    const top = h * (1 - progress);
    flamePath(ctx, w, h, top, tongue, t, 0);
    fillGradient(ctx, FLAME_COLORS, top - tongue, h);
    flamePath(ctx, w, h, top + tongue * 0.6, tongue * 0.7, t, 1.7);
    fillGradient(ctx, CORE_COLORS, top, h);
  }

  /** 動畫迴圈：只重畫時間到的房間；火焰高度以「已燒時間 + 取得資料後經過的時間」逐格推算，平順上升 */
  function animateFlames(now) {
    const t = (now % 1000) / 1000;  // 每秒 0 到 1 循環的動畫時鐘
    cards.forEach(c => {
      const canvas = c.querySelector(".flame");
      if (!c._fx) {
        if (canvas._drawn) { canvas.getContext("2d").clearRect(0, 0, canvas.width, canvas.height); canvas._drawn = false; }
        return;
      }
      const progress = Math.min(1, (c._fx.elapsed + (now - c._fx.at)) / FLAME_MS);
      drawFlame(canvas, progress, t);
      canvas._drawn = true;
    });
    requestAnimationFrame(animateFlames);
  }
  requestAnimationFrame(animateFlames);

  // ===== 語音播報：規則與 App 的 TtsScheduler 完全一致 =====
  // - Room TTS：「Room N, Time is up.」每個房間到 0 時讀 1 次，優先
  // - Global TTS：「Room 5, 3, 7」依到時先後排序
  // - Room TTS 讀完：若有其他 Room TTS 排隊即刻讀；否則間隔 AFTER_ROOM_MS 後讀 Global TTS
  // - Global TTS 讀完：間隔 GLOBAL_REPEAT_MS 再讀（期間有 Room TTS 會先讀）；清單空了就停
  const AFTER_ROOM_MS = ${TtsScheduler.AFTER_ROOM_MS};
  const GLOBAL_REPEAT_MS = ${TtsScheduler.GLOBAL_REPEAT_MS};
  const WATCHDOG_MS = 20000;
  // 語音播報固定開啟，不提供關閉選項
  const tts = {
    unlocked: false,       // 瀏覽器是否已允許發聲（需使用者互動一次）
    roomQueue: [],         // 待讀的 Room TTS
    globalRooms: [],       // Global TTS 清單（依到時先後）
    expired: new Set(),    // 目前時間到的房間
    speaking: false,
    globalDueAt: null,
    timer: null,
    initialized: false,    // 第一次取得狀態時不補讀已經時間到的 Room TTS
  };
  const supported = "speechSynthesis" in window;

  function updateTtsUi() {
    const hint = document.getElementById("ttsHint");
    if (!supported) {
      hint.textContent = "此瀏覽器不支援語音播報，請改用 Chrome 或 Edge";
      hint.style.display = "block";
    } else {
      hint.style.display = tts.unlocked ? "none" : "block";
    }
  }

  function unlockTts() {
    if (!supported || tts.unlocked) return;
    tts.unlocked = true;
    // 在使用者互動當下先播一段空語音，讓瀏覽器授權之後的自動播放
    speechSynthesis.speak(new SpeechSynthesisUtterance(""));
    updateTtsUi();
    pumpTts();
  }

  function speakTts(text, kind) {
    tts.speaking = true;
    let finished = false;
    const finish = () => {
      if (finished) return;
      finished = true;
      clearTimeout(watchdog);
      tts.speaking = false;
      tts.globalDueAt = Date.now() + (kind === "global" ? GLOBAL_REPEAT_MS : AFTER_ROOM_MS);
      pumpTts();
    };
    const watchdog = setTimeout(finish, WATCHDOG_MS);
    const u = new SpeechSynthesisUtterance(text);
    u.lang = "en-US";
    u.onend = finish;
    u.onerror = finish;
    speechSynthesis.speak(u);
  }

  function pumpTts() {
    clearTimeout(tts.timer);
    if (!supported || !tts.unlocked || tts.speaking) return;

    tts.roomQueue = tts.roomQueue.filter(r => tts.expired.has(r));
    if (tts.roomQueue.length) {
      speakTts("Room " + tts.roomQueue.shift() + ", Time is up.", "room");
      return;
    }
    if (!tts.globalRooms.length) {
      tts.globalDueAt = null;
      return;
    }
    const now = Date.now();
    if (tts.globalDueAt === null) tts.globalDueAt = now + AFTER_ROOM_MS;
    if (now >= tts.globalDueAt) {
      speakTts("Room " + tts.globalRooms.join(", "), "global");
    } else {
      tts.timer = setTimeout(pumpTts, tts.globalDueAt - now);
    }
  }

  /** 依最新狀態更新語音排程：新進入時間到的房間加入 Room TTS 佇列 */
  function syncTts(data) {
    const nowExpired = new Set(data.rooms.filter(r => r.state === "EXPIRED").map(r => r.room));
    if (tts.initialized) {
      // 依 Global 清單順序（到時先後）加入新到時的房間
      data.announceQueue.forEach(room => {
        if (nowExpired.has(room) && !tts.expired.has(room) && !tts.roomQueue.includes(room)) tts.roomQueue.push(room);
      });
    }
    tts.initialized = true;
    tts.expired = nowExpired;
    tts.globalRooms = data.announceQueue.slice();
    pumpTts();
  }

  ["click", "keydown", "touchstart"].forEach(ev => document.addEventListener(ev, unlockTts));
  updateTtsUi();

  let inFlight = false;  // 避免網路較慢時多個請求重疊
  async function refresh() {
    if (inFlight) return;
    inFlight = true;
    try {
      const res = await fetch("/api/status", { cache: "no-store" });
      const data = await res.json();
      syncTts(data);
      data.rooms.forEach((r, idx) => {
        const c = cards[idx];
        // 時間到特效：記下平板提供的「已燒時間」與取得時刻，由動畫迴圈逐格推算火焰高度；燒滿後加上震動
        const burning = r.state === "EXPIRED" && typeof r.expiredElapsedMs === "number";
        c._fx = burning ? { elapsed: r.expiredElapsedMs, at: performance.now() } : null;
        const flameProgress = burning ? Math.min(1, r.expiredElapsedMs / FLAME_MS) : 0;
        // className 內容不變時瀏覽器不會重新開始動畫，震動得以連續
        c.className = "card " + r.state + (flameProgress >= 1 ? " shake" : "");
        c.querySelector(".room-watermark").textContent = r.room;
        const total = Math.max(0, r.remainingSeconds || 0);
        c.querySelector(".minutes").textContent = String(Math.floor(total / 60)).padStart(2, '0');
        c.querySelector(".seconds").textContent = String(total % 60).padStart(2, '0');
        c.querySelector(".complete").className = "complete" + (r.complete ? " on" : "");
        // ToDo 圖示：內容有變動才重建，避免每 0.5 秒閃爍
        const todos = r.todo || [];
        const todoKey = todos.map(t => t.short + (t.done ? "1" : "0")).join(",");
        if (c.dataset.todoKey !== todoKey) {
          c.dataset.todoKey = todoKey;
          c.querySelector(".todos").innerHTML = todos.map(t =>
            '<div class="todo' + (t.done ? ' done' : '') + '" title="' + t.name + '">' +
            '<span class="ic cat-' + t.short + '">' + t.short + '</span>' +
            (t.done ? '<i class="ck">✓</i>' : '') + '</div>').join("");
        }
        // 標籤圓圈：與 App 一致，僅計時中 / 時間到顯示；未選標籤時顯示灰色「選」
        const l = c.querySelector(".label");
        if (r.state !== "IDLE") {
          l.textContent = r.labelShort || "選";
          l.title = r.label || "";
          l.className = "label" + (r.labelShort ? " cat-" + r.labelShort : "");
          l.style.display = "";
        } else {
          l.style.display = "none";
        }
      });
      document.getElementById("dev").textContent = data.deviceName ? "平板：" + data.deviceName : "";
      document.getElementById("queue").textContent =
        data.announceQueue.length ? "時間到: Room " + data.announceQueue.join(", ") : "";
      const conn = document.getElementById("conn");
      conn.textContent = "已連線 " + new Date().toLocaleTimeString();
      conn.className = "";
    } catch (e) {
      const conn = document.getElementById("conn");
      conn.textContent = "連線中斷，重試中…";
      conn.className = "bad";
    } finally {
      inFlight = false;
    }
  }

  // ===== 輪詢節拍 =====
  // 瀏覽器會把背景分頁（最小化、被其他視窗蓋住）主執行緒的計時器大幅降頻，
  // 長時間在背景時甚至每分鐘才執行一次，可能延誤報時。
  // 因此改由 Web Worker 發出節拍（Worker 的計時器一般不受此限制），主執行緒收到訊息才更新。
  const POLL_MS = 500;
  function startTicker() {
    try {
      const src = "setInterval(function () { postMessage(0); }, " + POLL_MS + ");";
      const worker = new Worker(URL.createObjectURL(new Blob([src], { type: "text/javascript" })));
      worker.onmessage = refresh;
    } catch (e) {
      setInterval(refresh, POLL_MS);  // 不支援 Worker 時退回一般計時器
    }
  }
  // 分頁回到前景時立即更新一次
  document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); });
  refresh();
  startTicker();
</script>
</body>
</html>
""".trimIndent()
    }
}

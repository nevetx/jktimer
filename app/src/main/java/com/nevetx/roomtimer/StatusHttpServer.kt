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
        val rooms = JSONArray()
        TimerController.rooms.value.forEach { r ->
            rooms.put(JSONObject().apply {
                put("room", r.room)
                put("state", r.phase.name)
                put("remainingSeconds", r.remainingSec)
                put("display", r.display)
                put("label", r.label?.full ?: JSONObject.NULL)
                put("labelShort", r.label?.short ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("serverTime", System.currentTimeMillis())
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
<title>Room 計時器</title>
<style>
  * { box-sizing: border-box; }
  body { margin: 0; font-family: "Segoe UI", "Microsoft JhengHei", sans-serif; background: #15181d; color: #fff; }
  header { display: flex; align-items: center; gap: 24px; padding: 10px 16px; background: #0d0f12; }
  header h1 { font-size: 22px; margin: 0; }
  #queue { font-size: 18px; color: #ffb4b4; }
  #conn { margin-left: auto; font-size: 14px; color: #8f8; }
  #conn.bad { color: #f66; }
  .grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; padding: 12px; }
  .card { border-radius: 12px; padding: 12px 16px; background: #2b2f36; min-height: 170px; display: flex; flex-direction: column; }
  .card.RUNNING { background: #1e3a5f; }
  .card.EXPIRED { background: #d32f2f; }
  .top { display: flex; align-items: center; justify-content: space-between; }
  .room { font-size: 24px; font-weight: bold; }
  .label { font-size: 20px; background: #ffc107; color: #000; border-radius: 8px; padding: 2px 10px; font-weight: bold; }
  .time { flex: 1; display: flex; align-items: center; justify-content: center; font-family: Consolas, monospace; font-size: 64px; font-weight: bold; }
  .card.IDLE .time { color: #777; }
  .state { text-align: center; font-size: 14px; opacity: .8; }
  @media (max-width: 1100px) { .grid { grid-template-columns: repeat(2, 1fr); } }
</style>
</head>
<body>
<header>
  <h1>Room 計時器（唯讀）</h1>
  <div id="queue"></div>
  <div id="conn">連線中…</div>
</header>
<div class="grid" id="grid"></div>
<script>
  const STATE_TEXT = { IDLE: "閒置", RUNNING: "倒數中", EXPIRED: "時間到" };
  const grid = document.getElementById("grid");
  const cards = [];
  for (let i = 1; i <= 10; i++) {
    const c = document.createElement("div");
    c.className = "card IDLE";
    c.innerHTML = '<div class="top"><span class="room">Room ' + i + '</span><span class="label" style="display:none"></span></div>' +
                  '<div class="time">--:--</div><div class="state">閒置</div>';
    grid.appendChild(c);
    cards.push(c);
  }
  async function refresh() {
    try {
      const res = await fetch("/api/status", { cache: "no-store" });
      const data = await res.json();
      data.rooms.forEach((r, idx) => {
        const c = cards[idx];
        c.className = "card " + r.state;
        c.querySelector(".time").textContent = r.display;
        c.querySelector(".state").textContent = STATE_TEXT[r.state];
        const l = c.querySelector(".label");
        if (r.label) { l.textContent = r.label; l.style.display = ""; } else { l.style.display = "none"; }
      });
      document.getElementById("queue").textContent =
        data.announceQueue.length ? "時間到: Room " + data.announceQueue.join(", ") : "";
      const conn = document.getElementById("conn");
      conn.textContent = "已連線 " + new Date().toLocaleTimeString();
      conn.className = "";
    } catch (e) {
      const conn = document.getElementById("conn");
      conn.textContent = "連線中斷，重試中…";
      conn.className = "bad";
    }
  }
  refresh();
  setInterval(refresh, 500);
</script>
</body>
</html>
""".trimIndent()
    }
}

package com.nevetx.roomtimer

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * 區網廣播：每隔 [INTERVAL_MS] 以 UDP 廣播平板的身分資訊（[DeviceIdentity.infoJson]），
 * 讓 PC 端（Electron）不需輸入 IP 即可找到平板；平板換 IP 後 PC 端也能在數秒內跟上。
 * 同時送往 255.255.255.255 與各網卡的子網廣播位址，提高在不同路由器上的成功率。
 */
class DiscoveryBeacon(private val port: Int = DISCOVERY_PORT) {
    companion object {
        const val DISCOVERY_PORT = 41234
        private const val INTERVAL_MS = 2_000L
        private const val TAG = "DiscoveryBeacon"
    }

    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        thread(isDaemon = true, name = "discovery-beacon") {
            while (true) {
                try {
                    DatagramSocket().use { socket ->
                        socket.broadcast = true
                        while (true) {
                            val data = DeviceIdentity.infoJson().toString().toByteArray(Charsets.UTF_8)
                            broadcastTargets().forEach { target ->
                                try {
                                    socket.send(DatagramPacket(data, data.size, target, port))
                                } catch (e: Exception) {
                                    // 個別位址失敗（例如網卡剛斷線）不影響其他位址
                                }
                            }
                            Thread.sleep(INTERVAL_MS)
                        }
                    }
                } catch (e: InterruptedException) {
                    return@thread
                } catch (e: Exception) {
                    Log.w(TAG, "beacon error, retrying", e)
                    Thread.sleep(INTERVAL_MS)
                }
            }
        }
    }

    private fun broadcastTargets(): Set<InetAddress> {
        val targets = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNullTo(targets) { it.broadcast }
        } catch (e: Exception) {
            // 取得網卡資訊失敗時仍使用全域廣播位址
        }
        return targets
    }
}

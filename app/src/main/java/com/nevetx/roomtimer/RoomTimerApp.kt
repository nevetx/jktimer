package com.nevetx.roomtimer

import android.app.Application

class RoomTimerApp : Application() {
    companion object {
        const val HTTP_PORT = 8080
        const val APP_NAME = "JK Timer Gen 2"
        /** 顯示用標題，含版本號，例如「JK Timer Gen 2 (v1.0)」；版本號來自 build.gradle.kts 的 versionName */
        val DISPLAY_TITLE = "$APP_NAME (v${BuildConfig.VERSION_NAME})"
        val httpServer = StatusHttpServer(HTTP_PORT)
        private val discoveryBeacon = DiscoveryBeacon()
    }

    override fun onCreate() {
        super.onCreate()
        DeviceIdentity.init(this)
        TimerController.init(this)
        ClickSound.init(this)
        httpServer.start()
        discoveryBeacon.start()
        TimerController.speakWelcome()
    }
}

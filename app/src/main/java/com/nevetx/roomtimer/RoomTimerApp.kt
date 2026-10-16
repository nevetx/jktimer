package com.nevetx.roomtimer

import android.app.Application

class RoomTimerApp : Application() {
    companion object {
        const val HTTP_PORT = 8080
        val httpServer = StatusHttpServer(HTTP_PORT)
    }

    override fun onCreate() {
        super.onCreate()
        TimerController.init(this)
        httpServer.start()
    }
}

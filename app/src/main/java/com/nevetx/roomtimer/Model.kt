package com.nevetx.roomtimer

enum class Phase { IDLE, RUNNING, EXPIRED }

enum class TimerLabel(val full: String, val short: String) {
    WAIST("拉腰", "腰"),
    NECK("拉頸", "頸"),
    ACUPUNCTURE("針灸", "針"),
    CUPPING("拔罐", "罐"),
    ELECTRO("電療", "電"),
    INFRARED("紅外線", "紅"),
    COMPRESS("冷熱敷", "敷"),
    OTHER("其他", "他"),
}

data class RoomState(
    val room: Int,
    val phase: Phase,
    val remainingSec: Long,
    val label: TimerLabel?,
) {
    val display: String
        get() = if (phase == Phase.IDLE) "--:--" else "%02d:%02d".format(remainingSec / 60, remainingSec % 60)
}

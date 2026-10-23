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

/**
 * ToDo 項目：包含所有計時標籤，另加三項不需計時的治療（手法、超聲波、衝擊波）。
 * 宣告順序即為 ToDo 對話框中的排列順序。
 * [label] 為對應的計時標籤；計時到 0 時，若標籤相符則標記該 ToDo 為已做。
 */
enum class TodoItem(val full: String, val short: String, val label: TimerLabel?) {
    MANUAL("手法", "手", null),
    WAIST("拉腰", "腰", TimerLabel.WAIST),
    NECK("拉頸", "頸", TimerLabel.NECK),
    ACUPUNCTURE("針灸", "針", TimerLabel.ACUPUNCTURE),
    CUPPING("拔罐", "罐", TimerLabel.CUPPING),
    ELECTRO("電療", "電", TimerLabel.ELECTRO),
    INFRARED("紅外線", "紅", TimerLabel.INFRARED),
    COMPRESS("冷熱敷", "敷", TimerLabel.COMPRESS),
    ULTRASOUND("超聲波", "超", null),
    SHOCKWAVE("衝擊波", "衝", null),
    OTHER("其他", "他", TimerLabel.OTHER),
}

/** ToDo 清單中的一項；清單順序即為使用者選取的先後順序。 */
data class TodoEntry(val item: TodoItem, val done: Boolean = false)

data class RoomState(
    val room: Int,
    val phase: Phase,
    val remainingSec: Long,
    val label: TimerLabel?,
    /** COMPLETE 標記：客人今日所有治療已於本次計時後完成 */
    val complete: Boolean = false,
    /** 客人今日的 ToDo 清單（依選取順序） */
    val todos: List<TodoEntry> = emptyList(),
    /** 時間到的時間點（系統時鐘毫秒）；非時間到狀態為 0。用於火焰特效進度 */
    val expiredAt: Long = 0L,
) {
    val display: String
        get() = if (phase == Phase.IDLE) "--:--" else "%02d:%02d".format(remainingSec / 60, remainingSec % 60)
}

package com.nevetx.roomtimer

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Holds all timer state. Every mutating call must happen on the main thread. */
object TimerController {
    const val ROOM_COUNT = 10
    /** 時間到後火焰由房間底部燒到頂部所需時間；燒滿後房間開始震動（App 與 PC 網頁共用） */
    const val FLAME_DURATION_MS = 180_000L
    private const val TICK_MS = 200L

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TtsScheduler

    private val phases = Array(ROOM_COUNT) { Phase.IDLE }
    private val endAt = LongArray(ROOM_COUNT)
    private val expiredAt = LongArray(ROOM_COUNT)
    private val labels = arrayOfNulls<TimerLabel>(ROOM_COUNT)
    private val completes = BooleanArray(ROOM_COUNT)
    private val todos = Array(ROOM_COUNT) { emptyList<TodoEntry>() }
    private val expiredOrder = mutableListOf<Int>()

    private val _rooms = MutableStateFlow(List(ROOM_COUNT) { RoomState(it + 1, Phase.IDLE, 0, null) })
    val rooms: StateFlow<List<RoomState>> = _rooms.asStateFlow()

    /** Global TTS array: rooms whose time is up, in the order they expired. */
    private val _announceQueue = MutableStateFlow<List<Int>>(emptyList())
    val announceQueue: StateFlow<List<Int>> = _announceQueue.asStateFlow()

    private var initialized = false

    private val tick = object : Runnable {
        override fun run() {
            val now = now()
            for (i in 0 until ROOM_COUNT) {
                // 以原定結束時間作為時間到時間點（App 被關閉期間到時，重開後火焰進度仍正確）
                if (phases[i] == Phase.RUNNING && endAt[i] <= now) expire(i, at = endAt[i])
            }
            publish()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        prefs = context.getSharedPreferences("timers", Context.MODE_PRIVATE)
        tts = TtsScheduler(
            context,
            globalRooms = { expiredOrder.toList() },
            isExpired = { room -> phases[room - 1] == Phase.EXPIRED },
        )
        restore()
        handler.post(tick)
    }

    fun speakWelcome() {
        tts.speakImmediate("Welcome to JK Timer Gen 2")
    }

    /** "立即調整時間": only while running or expired. */
    fun adjust(room: Int, deltaSec: Int) {
        val i = room - 1
        val now = now()
        when (phases[i]) {
            Phase.IDLE -> return
            Phase.RUNNING -> {
                endAt[i] += deltaSec * 1000L
                if (endAt[i] <= now) expire(i)
            }
            Phase.EXPIRED -> {
                if (deltaSec <= 0) return
                leaveExpired(i)
                phases[i] = Phase.RUNNING
                endAt[i] = now + deltaSec * 1000L
            }
        }
        changed()
    }

    /** "設定時間並立即開始計時". Label is kept; UI decides whether to ask for it. */
    fun startPreset(room: Int, minutes: Int) {
        val i = room - 1
        if (phases[i] == Phase.EXPIRED) leaveExpired(i)
        phases[i] = Phase.RUNNING
        endAt[i] = now() + minutes * 60_000L
        changed()
    }

    fun setLabel(room: Int, label: TimerLabel) {
        val i = room - 1
        if (phases[i] == Phase.IDLE) return
        labels[i] = label
        changed()
    }

    /** COMPLETE 切換：計時中與時間到皆可切換（閒置不可）；房間重設為閒置時自動清除。 */
    fun toggleComplete(room: Int) {
        val i = room - 1
        if (phases[i] == Phase.IDLE) return
        completes[i] = !completes[i]
        changed()
    }

    /** 以使用者在 ToDo 對話框確定的清單取代目前清單（任何狀態皆可）；重複項目只保留第一個。 */
    fun setTodos(room: Int, entries: List<TodoEntry>) {
        todos[room - 1] = entries.distinctBy { it.item }
        changed()
    }

    fun stop(room: Int) {
        val i = room - 1
        // COMPLETE 已開啟（計時中或時間到）：客人今日療程結束，重設時一併清除 ToDo
        if (completes[i]) todos[i] = emptyList()
        if (phases[i] == Phase.EXPIRED) leaveExpired(i)
        phases[i] = Phase.IDLE
        endAt[i] = 0
        labels[i] = null
        completes[i] = false
        changed()
    }

    private fun expire(i: Int, at: Long = now()) {
        phases[i] = Phase.EXPIRED
        expiredAt[i] = at
        endAt[i] = 0
        // 計時到 0：將與目前標籤相符的 ToDo 項目標記為已做
        labels[i]?.let { label ->
            todos[i] = todos[i].map { if (it.item.label == label) it.copy(done = true) else it }
        }
        val room = i + 1
        if (room !in expiredOrder) expiredOrder.add(room)
        save()
        tts.enqueueRoom(room)
    }

    private fun leaveExpired(i: Int) {
        expiredAt[i] = 0
        val room = i + 1
        expiredOrder.remove(room)
        tts.cancelRoom(room)
    }

    private fun changed() {
        save()
        publish()
        tts.onStateChanged()
    }

    private fun publish() {
        val now = now()
        _rooms.value = List(ROOM_COUNT) { i ->
            val remaining = when (phases[i]) {
                Phase.RUNNING -> ((endAt[i] - now).coerceAtLeast(0) + 999) / 1000
                else -> 0L
            }
            RoomState(
                i + 1, phases[i], remaining, labels[i], completes[i], todos[i],
                expiredAt = if (phases[i] == Phase.EXPIRED) expiredAt[i] else 0L,
            )
        }
        _announceQueue.value = expiredOrder.toList()
    }

    private fun save() {
        val e = prefs.edit()
        for (i in 0 until ROOM_COUNT) {
            e.putString("phase_$i", phases[i].name)
            e.putLong("end_$i", endAt[i])
            e.putLong("expired_at_$i", expiredAt[i])
            e.putString("label_$i", labels[i]?.name)
            e.putBoolean("complete_$i", completes[i])
            // 格式：項目名稱:已做(0/1)，以逗號分隔，例如 "MANUAL:0,ELECTRO:1"
            e.putString("todo_$i", todos[i].joinToString(",") { "${it.item.name}:${if (it.done) 1 else 0}" })
        }
        e.putString("expired_order", expiredOrder.joinToString(","))
        e.apply()
    }

    private fun restore() {
        expiredOrder.clear()
        prefs.getString("expired_order", "")!!.split(",")
            .mapNotNull { it.toIntOrNull() }
            .filter { it in 1..ROOM_COUNT }
            .forEach { if (it !in expiredOrder) expiredOrder.add(it) }
        for (i in 0 until ROOM_COUNT) {
            phases[i] = runCatching { Phase.valueOf(prefs.getString("phase_$i", null)!!) }.getOrDefault(Phase.IDLE)
            endAt[i] = prefs.getLong("end_$i", 0)
            expiredAt[i] = prefs.getLong("expired_at_$i", 0)
            // 舊版資料沒有時間到時間點：從現在開始計算火焰進度
            if (phases[i] == Phase.EXPIRED && expiredAt[i] == 0L) expiredAt[i] = now()
            labels[i] = runCatching { TimerLabel.valueOf(prefs.getString("label_$i", null)!!) }.getOrNull()
            completes[i] = prefs.getBoolean("complete_$i", false)
            todos[i] = prefs.getString("todo_$i", "")!!.split(",")
                .mapNotNull { token ->
                    val parts = token.split(":")
                    val item = runCatching { TodoItem.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
                    TodoEntry(item, parts.getOrNull(1) == "1")
                }
                .distinctBy { it.item }
            if (phases[i] == Phase.IDLE) labels[i] = null
            if (phases[i] == Phase.EXPIRED && (i + 1) !in expiredOrder) expiredOrder.add(i + 1)
        }
        expiredOrder.retainAll { phases[it - 1] == Phase.EXPIRED }
        publish()
    }

    private fun now() = System.currentTimeMillis()
}

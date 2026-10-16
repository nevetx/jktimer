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
    private const val TICK_MS = 200L

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var tts: TtsScheduler

    private val phases = Array(ROOM_COUNT) { Phase.IDLE }
    private val endAt = LongArray(ROOM_COUNT)
    private val labels = arrayOfNulls<TimerLabel>(ROOM_COUNT)
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
                if (phases[i] == Phase.RUNNING && endAt[i] <= now) expire(i)
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

    fun stop(room: Int) {
        val i = room - 1
        if (phases[i] == Phase.EXPIRED) leaveExpired(i)
        phases[i] = Phase.IDLE
        endAt[i] = 0
        labels[i] = null
        changed()
    }

    private fun expire(i: Int) {
        phases[i] = Phase.EXPIRED
        endAt[i] = 0
        val room = i + 1
        if (room !in expiredOrder) expiredOrder.add(room)
        save()
        tts.enqueueRoom(room)
    }

    private fun leaveExpired(i: Int) {
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
            RoomState(i + 1, phases[i], remaining, labels[i])
        }
        _announceQueue.value = expiredOrder.toList()
    }

    private fun save() {
        val e = prefs.edit()
        for (i in 0 until ROOM_COUNT) {
            e.putString("phase_$i", phases[i].name)
            e.putLong("end_$i", endAt[i])
            e.putString("label_$i", labels[i]?.name)
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
            labels[i] = runCatching { TimerLabel.valueOf(prefs.getString("label_$i", null)!!) }.getOrNull()
            if (phases[i] == Phase.IDLE) labels[i] = null
            if (phases[i] == Phase.EXPIRED && (i + 1) !in expiredOrder) expiredOrder.add(i + 1)
        }
        expiredOrder.retainAll { phases[it - 1] == Phase.EXPIRED }
        publish()
    }

    private fun now() = System.currentTimeMillis()
}

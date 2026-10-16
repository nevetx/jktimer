package com.nevetx.roomtimer

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Serialises "Room TTS" (one-off "Room N, Time is up.") and "Global TTS" ("Room 5, 3, 7").
 * - Never speaks two utterances at once.
 * - Room TTS has priority and is spoken as soon as the engine is free.
 * - After a Room TTS with nothing else queued, Global TTS follows after [AFTER_ROOM_MS].
 * - After a Global TTS, it repeats after [GLOBAL_REPEAT_MS] while the array is non-empty.
 */
class TtsScheduler(
    context: Context,
    private val globalRooms: () -> List<Int>,
    private val isExpired: (Int) -> Boolean,
) {
    companion object {
        const val AFTER_ROOM_MS = 3_000L
        const val GLOBAL_REPEAT_MS = 5_000L
        private const val WATCHDOG_MS = 20_000L
        private const val TAG = "TtsScheduler"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val roomQueue = ArrayDeque<Int>()
    private var ready = false
    private var speakingId: String? = null
    private var globalDueAt: Long? = null
    private var seq = 0

    private val pumpRunnable = Runnable { pump() }
    private val watchdog = Runnable { speakingId?.let { finished(it) } }

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        handler.post { onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS init failed: $status")
            return
        }
        val r = tts.setLanguage(Locale.US)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.ENGLISH)
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = post(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = post(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = post(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = post(utteranceId)
            private fun post(id: String?) {
                if (id != null) handler.post { finished(id) }
            }
        })
        ready = true
        pump()
    }

    fun enqueueRoom(room: Int) {
        if (room !in roomQueue) roomQueue.addLast(room)
        pump()
    }

    fun cancelRoom(room: Int) {
        roomQueue.remove(room)
    }

    fun onStateChanged() = pump()

    private fun finished(id: String) {
        if (id != speakingId) return
        handler.removeCallbacks(watchdog)
        speakingId = null
        val delay = if (id.startsWith("room")) AFTER_ROOM_MS else GLOBAL_REPEAT_MS
        globalDueAt = SystemClock.elapsedRealtime() + delay
        pump()
    }

    private fun pump() {
        handler.removeCallbacks(pumpRunnable)
        if (!ready || speakingId != null) return

        while (roomQueue.isNotEmpty() && !isExpired(roomQueue.first())) roomQueue.removeFirst()
        if (roomQueue.isNotEmpty()) {
            val room = roomQueue.removeFirst()
            speak("Room $room, Time is up.", "room-$room-${seq++}")
            return
        }

        val rooms = globalRooms()
        if (rooms.isEmpty()) {
            globalDueAt = null
            return
        }
        val now = SystemClock.elapsedRealtime()
        val due = globalDueAt ?: (now + AFTER_ROOM_MS).also { globalDueAt = it }
        if (now >= due) {
            speak("Room " + rooms.joinToString(", "), "global-${seq++}")
        } else {
            handler.postDelayed(pumpRunnable, due - now)
        }
    }

    private fun speak(text: String, id: String) {
        speakingId = id
        Log.i(TAG, "speak [$id]: $text")
        handler.postDelayed(watchdog, WATCHDOG_MS)
        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "speak failed for $id")
            handler.post { finished(id) }
        }
    }
}

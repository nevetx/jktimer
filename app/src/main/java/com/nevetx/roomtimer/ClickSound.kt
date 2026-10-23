package com.nevetx.roomtimer

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool

/**
 * 按鈕點擊音效。
 * 使用 SoundPool：音效預先載入記憶體，播放延遲低，快速連按也能每次都發聲。
 */
object ClickSound {
    /** 點擊聲相對音量（0~1）；與 TTS（[TtsScheduler.TTS_VOLUME]）同走媒體音量，比例固定 */
    private const val VOLUME = 0.3f

    private var pool: SoundPool? = null
    private var soundId = 0
    @Volatile private var loaded = false

    fun init(context: Context) {
        if (pool != null) return
        // 與 TTS 同走媒體音量通道，平板只需調整一個音量，兩者比例不變
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        pool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()
            .apply {
                setOnLoadCompleteListener { _, _, status -> loaded = status == 0 }
                soundId = load(context.applicationContext, R.raw.click, 1)
            }
    }

    /** 播放點擊聲；音效尚未載入完成時略過（僅發生在 App 剛啟動的瞬間）。 */
    fun play() {
        if (loaded) pool?.play(soundId, VOLUME, VOLUME, 1, 0, 1f)
    }
}

/** 包裝點擊動作：先播放點擊聲再執行原動作。 */
fun withClick(action: () -> Unit): () -> Unit = {
    ClickSound.play()
    action()
}

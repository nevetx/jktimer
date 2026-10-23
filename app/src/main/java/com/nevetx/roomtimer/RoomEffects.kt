package com.nevetx.roomtimer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/*
 * 時間到特效：
 * 1. 火焰：時間到後，橙黃色火焰由房間底部向上蔓延，FLAME_DURATION_MS（3 分鐘）燒到頂部
 * 2. 震動：火焰燒滿後，房間卡片週期性震動，直到使用者重設房間
 * 進度以「時間到時間點」計算，App 重開或 PC 端顯示都能接續同一進度。
 */

private const val SHAKE_FREQ = 14          // 每秒震動來回次數（須為整數，動畫循環才連續）
private const val SHAKE_PORTION = 0.45f    // 每秒中震動所佔比例；其餘時間靜止，方便點按按鈕
private const val SHAKE_ROTATION = 0.8f    // 震動時的最大旋轉角度（度）
private val SHAKE_AMPLITUDE = 5.dp         // 震動時的最大左右位移
private val MAX_TONGUE = 48.dp             // 火舌最大高度

// 外層火焰：頂端淡黃 → 中段橙 → 底部深橙紅
private val FlameColors = listOf(Color(0x66FFD54F), Color(0xCCFFA000), Color(0xE6FF6D00))
// 內層火芯：較亮的黃色
private val CoreColors = listOf(Color(0x40FFF59D), Color(0xB3FFD54F))

/** 特效動畫時鐘：啟用時每秒由 0 到 1 循環一次；停用時不產生任何動畫，避免閒置房間耗電 */
@Composable
fun rememberFxClock(active: Boolean): State<Float> =
    if (active) {
        rememberInfiniteTransition(label = "fx").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1000, easing = LinearEasing)),
            label = "fxClock",
        )
    } else {
        remember { mutableFloatStateOf(0f) }
    }

/** 火焰進度 0~1（1 代表已燒到頂部）；expiredAt 為 0 時回傳 0 */
fun flameProgress(expiredAt: Long, now: Long = System.currentTimeMillis()): Float =
    if (expiredAt <= 0L) 0f
    else ((now - expiredAt).toFloat() / TimerController.FLAME_DURATION_MS).coerceIn(0f, 1f)

/** 火焰燒滿後的震動（套用在卡片最外層，整張卡片一起震動） */
fun Modifier.burntShake(expiredAt: Long, clock: State<Float>): Modifier =
    if (expiredAt <= 0L) this
    else graphicsLayer {
        val t = clock.value
        if (flameProgress(expiredAt) >= 1f && t < SHAKE_PORTION) {
            val decay = 1f - t / SHAKE_PORTION
            val wave = sin(2 * PI * SHAKE_FREQ * t).toFloat()
            translationX = SHAKE_AMPLITUDE.toPx() * wave * decay
            rotationZ = SHAKE_ROTATION * wave * decay
        } else {
            translationX = 0f
            rotationZ = 0f
        }
    }

/** 火焰背景（套用在卡片底色之後、內容之前，位於時間數字下層） */
fun Modifier.flameBackground(expiredAt: Long, clock: State<Float>): Modifier =
    if (expiredAt <= 0L) this
    else drawBehind { drawFlame(flameProgress(expiredAt), clock.value) }

private fun DrawScope.drawFlame(progress: Float, t: Float) {
    if (progress <= 0f) return
    val h = size.height
    // 火舌高度隨火勢增長，避免剛開始時就出現過高的火舌
    val tongue = minOf(h * 0.14f, MAX_TONGUE.toPx()) * (0.5f + 0.5f * progress)
    val top = h * (1f - progress)

    drawPath(
        flamePath(top, tongue, t, seed = 0f),
        Brush.verticalGradient(FlameColors, startY = top - tongue, endY = h),
    )
    drawPath(
        flamePath(top + tongue * 0.6f, tongue * 0.7f, t, seed = 1.7f),
        Brush.verticalGradient(CoreColors, startY = top, endY = h),
    )
}

/** 火焰輪廓：底部填滿，頂部為多組不同頻率的正弦波疊加而成的跳動火舌 */
private fun DrawScope.flamePath(top: Float, tongue: Float, t: Float, seed: Float): Path {
    val w = size.width
    val h = size.height
    val steps = 40
    return Path().apply {
        moveTo(0f, h)
        for (s in 0..steps) {
            val fx = s / steps.toFloat()
            val n = 0.55f * wave(fx * 7f + seed, t, 1) +
                0.30f * wave(fx * 13f - seed, t, -2) +
                0.15f * wave(fx * 23f + seed * 2f, t, 3)
            lineTo(fx * w, (top - tongue * n).coerceAtMost(h))
        }
        lineTo(w, h)
        close()
    }
}

/** 0~1 的波形；speed 為整數，確保時鐘由 1 回到 0 時畫面連續不跳動 */
private fun wave(phase: Float, t: Float, speed: Int): Float =
    (sin(2 * PI * (phase + speed * t)).toFloat() + 1f) / 2f

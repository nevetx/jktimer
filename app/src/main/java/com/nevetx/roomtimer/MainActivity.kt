package com.nevetx.roomtimer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay

private val BgColor = Color(0xFF15181D)
private val IdleColor = Color(0xFF2B2F36)
private val RunningColor = Color(0xCC1B5E20)  // 半透明深綠色 - 正在倒數
private val ExpiredColor = Color(0xFFD32F2F)
private val LabelChipColor = Color(0xFFFFC107)
private val MinutesColor = Color(0xFFA98BEB)  // 紫色 - 分鐘
private val SecondsColor = Color(0xFF5B9BEA)  // 藍色 - 秒數
private val CompleteGlow = Color(0xFF66FF66)  // COMPLETE 啟用時的光暈色
private const val COMPLETE_ON_ALPHA = 0.6f    // COMPLETE 啟用時的整體不透明度
private const val MEASURE_SIZE = 100f         // 量測字形用的參考字體大小（px）
private const val DIGIT_GAP_RATIO = 0.12f     // 兩行數字間距 / 字形高度
private const val DIGIT_FILL = 0.94f          // 數字佔可用空間的比例（保留邊界）
private const val ROOM_WATERMARK_ALPHA = 0.06f // 背景房號透明度（越小越淡）
private const val IDLE_DIGIT_ALPHA = 0.45f     // 閒置時時間數字的不透明度（與使用中區分）

// 標籤顏色定義
private val LabelColors = mapOf(
    TimerLabel.WAIST to listOf(Color(0xFF0B64A0), Color(0xFF083552)),      // 深藍 - 拉腰
    TimerLabel.NECK to listOf(Color(0xFF3498DB), Color(0xFF135A88)),       // 天藍 - 拉頸
    TimerLabel.ACUPUNCTURE to listOf(Color(0xFFFF5E4D), Color(0xFFC0392B)), // 紅 - 針灸
    TimerLabel.CUPPING to listOf(Color(0xFFF2AED1), Color(0xFF8E44AD)),     // 粉紫 - 拔罐
    TimerLabel.ELECTRO to listOf(Color(0xFFB684EF), Color(0xFF780FF1)),    // 藍紫 - 電療
    TimerLabel.INFRARED to listOf(Color(0xFF12F3D1), Color(0xFF0B6053)),    // 青色 - 紅外線
    TimerLabel.COMPRESS to listOf(Color(0xFF606060), Color(0xFF000000)),    // 黑/灰 - 冷熱敷
    TimerLabel.OTHER to listOf(Color(0xFF95A5A6), Color(0xFF2C3E50)),       // 灰 - 其他
)

// ToDo 專屬項目（不需計時）的顏色；其餘項目沿用對應標籤的顏色
private val TodoExtraColors = mapOf(
    TodoItem.MANUAL to listOf(Color(0xFF8E4E2C), Color(0xFF5A2D14)),      // 咖啡 - 手法
    TodoItem.ULTRASOUND to listOf(Color(0xFF8CF0F2), Color(0xFF4A9696)),  // 青綠 - 超聲波
    TodoItem.SHOCKWAVE to listOf(Color(0xFFE23DF5), Color(0xFF8A1E8C)),   // 洋紅 - 衝擊波
)

private fun todoColors(item: TodoItem): List<Color> =
    item.label?.let { LabelColors[it] } ?: TodoExtraColors[item] ?: listOf(Color(0xFF607D8B), Color(0xFF37474F))

private val LABEL_SIZE = 44.dp          // 左上角標籤圓圈大小
private val TODO_ICON_SIZE = LABEL_SIZE * 0.5f // 房間卡片上 ToDo 小圖示直徑（標籤的 50%）
private val TODO_ICON_GAP = 3.dp
private const val TOP_BAR_ALPHA = 0.5f  // 浮動資訊列（標題 / PC 網址）的不透明度
private const val UPPER_WEIGHT = 0.65f  // 上部介面佔房間卡片的比例
private const val LOWER_WEIGHT = 0.35f  // 下部介面（三行按鈕）佔房間卡片的比例
private const val TODO_DONE_ALPHA = 0.35f // 已做項目的不透明度
private val TODO_TILE_SIZE = 96.dp      // ToDo 對話框方格大小
private val TODO_TILE_GAP = 14.dp
private val DoneGreen = Color(0xFF43A047)
private val OrderBadgeRed = Color(0xFFE04B3A)

/** 將顏色乘上指定不透明度（用於不想建立離屏圖層、避免裁切光暈的情況）。 */
private fun Color.fade(factor: Float) = copy(alpha = alpha * factor)

private val ADJUSTMENTS = listOf("-1m" to -60, "+1m" to 60, "-10s" to -10, "+10s" to 10)
private val PRESETS = listOf(1, 3, 5, 10, 15)

private sealed interface DialogState {
    data class Label(val room: Int) : DialogState
    data class ConfirmPreset(val room: Int, val minutes: Int) : DialogState
    data class ConfirmStop(val room: Int) : DialogState
    data class Todo(val room: Int) : DialogState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 畫面延伸到瀏海 / 挖孔區域，並由 App 自行繪製整個螢幕
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.hideSystemBars()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ContextCompat.startForegroundService(this, Intent(this, TimerService::class.java))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { TimerScreen() }
        }
    }

    // 部分平板在對話框關閉、通知下拉或切換 App 後會重新顯示系統列，取得焦點時再隱藏一次
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.hideSystemBars()
    }
}

/**
 * 沉浸式全螢幕：隱藏狀態列與導覽列。
 * 使用者從螢幕邊緣滑動時系統列會暫時出現，數秒後自動隱藏。
 */
private fun Window.hideSystemBars() {
    WindowInsetsControllerCompat(this, decorView).apply {
        hide(WindowInsetsCompat.Type.systemBars())
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

/** 對話框有獨立視窗，開啟時系統列可能重新出現；於對話框內呼叫以保持全螢幕。 */
@Composable
private fun KeepDialogImmersive() {
    val view = LocalView.current
    SideEffect {
        val provider = (view as? DialogWindowProvider) ?: (view.parent as? DialogWindowProvider)
        provider?.window?.hideSystemBars()
    }
}

@Composable
private fun TimerScreen() {
    val rooms by TimerController.rooms.collectAsState()
    val queue by TimerController.announceQueue.collectAsState()
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    Box(Modifier.fillMaxSize().background(BgColor)) {
    // 房間卡片佔滿整個畫面
    Column(
        Modifier
            .fillMaxSize()
            .padding(6.dp)
    ) {
        for (row in 0 until 2) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 5) {
                    val state = rooms[row * 5 + col]
                    RoomCard(
                        state = state,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp),
                        onAdjust = { sec -> TimerController.adjust(state.room, sec) },
                        onPreset = { min ->
                            if (state.phase == Phase.RUNNING) {
                                dialog = DialogState.ConfirmPreset(state.room, min)
                            } else {
                                TimerController.startPreset(state.room, min)
                                if (state.label == null) {
                                    dialog = DialogState.Label(state.room)
                                }
                            }
                        },
                        onLabel = { dialog = DialogState.Label(state.room) },
                        onStop = {
                            if (state.phase == Phase.EXPIRED) {
                                TimerController.stop(state.room)
                            } else {
                                dialog = DialogState.ConfirmStop(state.room)
                            }
                        },
                        onComplete = { TimerController.toggleComplete(state.room) },
                        onTodo = { dialog = DialogState.Todo(state.room) },
                    )
                }
            }
        }
    }

    // 標題 / PC 網址資訊列：浮在最上層、半透明，不佔版面也不攔截觸控
    TopBar(queue, Modifier.align(Alignment.TopStart))
    }

    when (val d = dialog) {
        is DialogState.Label -> LabelDialog(
            room = d.room,
            current = rooms[d.room - 1].label,
            onSelect = { TimerController.setLabel(d.room, it); dialog = null },
        )
        is DialogState.ConfirmPreset -> ConfirmDialog(
            title = "Room ${d.room}",
            text = "Room ${d.room} 正在倒數，確定重設為 ${d.minutes} 分鐘並繼續計時？",
            onConfirm = {
                TimerController.startPreset(d.room, d.minutes)
                dialog = if (TimerController.rooms.value[d.room - 1].label == null) DialogState.Label(d.room) else null
            },
            onDismiss = { dialog = null },
        )
        is DialogState.ConfirmStop -> ConfirmDialog(
            title = "Room ${d.room}",
            text = "確定要立即停止 Room ${d.room} 的計時，回到閒置狀態？",
            onConfirm = { TimerController.stop(d.room); dialog = null },
            onDismiss = { dialog = null },
        )
        is DialogState.Todo -> TodoDialog(
            room = d.room,
            current = rooms[d.room - 1].todos,
            onConfirm = { TimerController.setTodos(d.room, it); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> {}
    }
}

@Composable
private fun TopBar(queue: List<Int>, modifier: Modifier = Modifier) {
    var ips by remember { mutableStateOf(StatusHttpServer.localIpv4Addresses()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            ips = StatusHttpServer.localIpv4Addresses()
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .alpha(TOP_BAR_ALPHA)
            .padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(RoomTimerApp.DISPLAY_TITLE, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        val url = if (ips.isEmpty()) "未連接網絡" else ips.joinToString("  ") { "http://$it:${RoomTimerApp.HTTP_PORT}" }
        Text("PC 檢視: $url", color = Color(0xFFB0BEC5), fontSize = 11.sp)
        Spacer(Modifier.width(12.dp))
        Text("裝置: ${DeviceIdentity.name}", color = Color(0xFFB0BEC5), fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        if (queue.isNotEmpty()) {
            Text("時間到: Room ${queue.joinToString(", ")}", color = Color(0xFFFF8A80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RoomCard(
    state: RoomState,
    modifier: Modifier,
    onAdjust: (Int) -> Unit,
    onPreset: (Int) -> Unit,
    onLabel: () -> Unit,
    onStop: () -> Unit,
    onComplete: () -> Unit,
    onTodo: () -> Unit,
) {
    val bg = when (state.phase) {
        Phase.IDLE -> IdleColor
        Phase.RUNNING -> RunningColor
        Phase.EXPIRED -> ExpiredColor
    }
    val active = state.phase != Phase.IDLE
    val onRed = state.phase == Phase.EXPIRED
    val btnColors = ButtonDefaults.buttonColors(
        containerColor = if (onRed) Color(0xFF7F0000) else Color(0xFF455A64),
        contentColor = Color.White,
        disabledContainerColor = Color(0x22FFFFFF),
        disabledContentColor = Color(0x55FFFFFF),
    )
    val presetColors = ButtonDefaults.buttonColors(
        containerColor = if (onRed) Color(0xFF1B5E20) else Color(0xFF2E7D32),
        contentColor = Color.White,
    )
    val stopColors = ButtonDefaults.buttonColors(
        containerColor = if (onRed) Color.Black else Color(0xFFB71C1C),
        contentColor = Color.White,
        disabledContainerColor = Color(0x22FFFFFF),
        disabledContentColor = Color(0x55FFFFFF),
    )

    // 時間到特效：火焰由下往上燒（3 分鐘到頂），燒滿後卡片震動，直到重設
    val burningSince = if (state.phase == Phase.EXPIRED) state.expiredAt else 0L
    val fxClock = rememberFxClock(burningSince > 0L)

    Box(
        modifier
            .burntShake(burningSince, fxClock)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .flameBackground(burningSince, fxClock)
            .padding(6.dp)
    ) {
        Column(Modifier.fillMaxSize()) {
            // 上部介面（佔卡片 65%）：背景 Room 號碼 + 上分下秒 + 標籤 + ToDo + COMPLETE
            Box(
                Modifier
                    .weight(UPPER_WEIGHT)
                    .fillMaxWidth()
                    .then(
                        if (state.phase == Phase.EXPIRED) {
                            Modifier.clickable(onClick = withClick(onStop))
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                // 圖層順序（下 → 上）：背景房號 → 時間數字 → COMPLETE（半透明）→ 標籤
                RoomWatermark(room = state.room, modifier = Modifier.fillMaxSize())

                // 閒置時顯示 00 / 00（與參考圖一致），計時中顯示剩餘分 / 秒
                TimeDigits(
                    minText = "%02d".format(state.remainingSec / 60),
                    secText = "%02d".format(state.remainingSec % 60),
                    alpha = if (state.phase == Phase.IDLE) IDLE_DIGIT_ALPHA else 1f,
                    modifier = Modifier.fillMaxSize(),
                )

                // 左側 ToDo：無項目時顯示淡色「ToDo」字樣，有項目時顯示小圖示（排在標籤圓圈下方）
                TodoStrip(
                    todos = state.todos,
                    onOpen = onTodo,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = LABEL_SIZE + 6.dp),
                )

                // 右下角斜置 COMPLETE 切換按鈕（疊在時間上方，啟用時半透明以免完全遮擋時間）
                CompleteToggle(
                    checked = state.complete,
                    enabled = state.phase != Phase.IDLE,
                    onToggle = onComplete,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 2.dp, bottom = 10.dp),
                )

                // 左上角圓形標籤（僅計時中 / 時間到時顯示）
                if (state.phase != Phase.IDLE) {
                    val label = state.label
                    val colors = if (label != null) {
                        LabelColors[label] ?: listOf(LabelChipColor, LabelChipColor)
                    } else {
                        listOf(Color(0xFF455A64), Color(0xFF455A64))
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopStart)
                            .size(LABEL_SIZE)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(colors))
                            .border(2.dp, Color.White, CircleShape)
                            .clickable(onClick = withClick(onLabel)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label?.short ?: "選", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 下部介面（佔卡片 35%）：三行按鈕平均分配高度
            Column(
                Modifier
                    .weight(LOWER_WEIGHT)
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                // Adjustment buttons
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    ADJUSTMENTS.forEach { (text, sec) ->
                        SmallButton(text, active, btnColors) { onAdjust(sec) }
                    }
                }

                // Preset buttons row 1: 1m, 3m, 5m
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    SmallButton("1m", true, presetColors) { onPreset(1) }
                    SmallButton("3m", true, presetColors) { onPreset(3) }
                    SmallButton("5m", true, presetColors) { onPreset(5) }
                }

                // Preset buttons row 2: 10m, 15m, STOP
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    SmallButton("10m", true, presetColors) { onPreset(10) }
                    SmallButton("15m", true, presetColors) { onPreset(15) }
                    SmallButton("STOP", active, stopColors) { onStop() }
                }
            }
        }
    }
}

/**
 * 以 Canvas 繪製「上分鐘、下秒數」。
 * 依數字字形的實際像素邊界計算最大字體，確保兩行完整顯示、不被裁切。
 */
@Composable
private fun rememberDigitPaint() = remember {
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
    }
}

/** 背景 Room 號碼：高度約填滿區域的 85%，置中淡色顯示。 */
@Composable
private fun RoomWatermark(room: Int, modifier: Modifier = Modifier) {
    val paint = rememberDigitPaint()
    val bounds = remember { android.graphics.Rect() }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        val roomText = room.toString()
        paint.textSize = MEASURE_SIZE
        paint.getTextBounds(roomText, 0, roomText.length, bounds)
        val roomScale = minOf(
            h * 0.85f / bounds.height(),
            w * 0.9f / paint.measureText(roomText),
        )
        paint.textSize = MEASURE_SIZE * roomScale
        paint.color = Color.White.copy(alpha = ROOM_WATERMARK_ALPHA).toArgb()
        val roomBaseline = h / 2f - (bounds.top + bounds.height() / 2f) * roomScale
        drawIntoCanvas { it.nativeCanvas.drawText(roomText, w / 2f, roomBaseline, paint) }
    }
}

@Composable
private fun TimeDigits(minText: String, secText: String, alpha: Float = 1f, modifier: Modifier = Modifier) {
    val digitPaint = rememberDigitPaint()
    val bounds = remember { android.graphics.Rect() }

    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas

        // 以參考字體量測「00」的字形邊界（數字為等寬字形，用固定字串量測可避免字體隨數字跳動）
        digitPaint.textSize = MEASURE_SIZE
        digitPaint.getTextBounds("00", 0, 2, bounds)
        val glyphH = bounds.height().toFloat()
        val glyphW = digitPaint.measureText("00")
        val glyphTop = bounds.top.toFloat()  // 基線以上的距離（負值）

        // 兩行字形 + 行距，並保留四周邊界
        val totalUnits = glyphH * (2 + DIGIT_GAP_RATIO)
        val scale = minOf(h * DIGIT_FILL / totalUnits, w * DIGIT_FILL / glyphW)
        val lineH = glyphH * scale
        val gap = glyphH * DIGIT_GAP_RATIO * scale
        val top = (h - (lineH * 2 + gap)) / 2f
        val baseline1 = top - glyphTop * scale
        val baseline2 = baseline1 + lineH + gap
        val cx = w / 2f

        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas

            // 分鐘（紫）與秒數（藍）
            digitPaint.textSize = MEASURE_SIZE * scale
            digitPaint.color = MinutesColor.fade(alpha).toArgb()
            native.drawText(minText, cx, baseline1, digitPaint)
            digitPaint.color = SecondsColor.fade(alpha).toArgb()
            native.drawText(secText, cx, baseline2, digitPaint)
        }
    }
}

/**
 * 斜置的 COMPLETE 切換按鈕。
 * - 未啟用：淡色半透明，隱約可見
 * - 已啟用：綠色漸層 + 光暈，清楚提示同事「客人今日療程已完成」
 */
@Composable
private fun CompleteToggle(checked: Boolean, enabled: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    // 啟用時的半透明直接套用在各顏色上（不使用 Modifier.alpha，避免離屏圖層裁切旋轉後的按鈕）
    fun Color.on() = copy(alpha = alpha * COMPLETE_ON_ALPHA)
    Box(
        modifier
            .rotate(-25f)
            .then(
                if (checked) {
                    Modifier.shadow(
                        elevation = 8.dp,
                        shape = shape,
                        ambientColor = CompleteGlow.on(),
                        spotColor = CompleteGlow.on(),
                    )
                } else {
                    Modifier
                }
            )
            .clip(shape)
            .background(
                if (checked) {
                    Brush.linearGradient(listOf(Color(0xFF7CC67F).on(), Color(0xFF4CAF50).on()))
                } else {
                    Brush.linearGradient(listOf(Color(0x14FFFFFF), Color(0x0AFFFFFF)))
                }
            )
            .border(1.dp, if (checked) Color(0xFFA5D6A7).on() else Color(0x1FFFFFFF), shape)
            .clickable(enabled = enabled, onClick = withClick(onToggle))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "COMPLETE",
            color = if (checked) Color(0xFFE8F5E9).on() else Color(0x33FFFFFF),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
    }
}

/**
 * 房間卡片左側的 ToDo 區塊。
 * - 無項目：淡色「ToDo」字樣（任何狀態皆可點）
 * - 有項目：依選取順序由上而下排列小圖示，一欄放不下時往右換欄；點任一圖示開啟對話框
 */
@Composable
private fun TodoStrip(todos: List<TodoEntry>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (todos.isEmpty()) {
        Text(
            "ToDo",
            color = Color(0x40FFFFFF),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = withClick(onOpen))
                .padding(horizontal = 4.dp, vertical = 8.dp),
        )
        return
    }
    BoxWithConstraints(modifier.fillMaxHeight()) {
        // 依可用高度計算每欄可放幾個圖示
        val perColumn = ((maxHeight + TODO_ICON_GAP) / (TODO_ICON_SIZE + TODO_ICON_GAP)).toInt().coerceAtLeast(1)
        Row(horizontalArrangement = Arrangement.spacedBy(TODO_ICON_GAP)) {
            todos.chunked(perColumn).forEach { column ->
                Column(verticalArrangement = Arrangement.spacedBy(TODO_ICON_GAP)) {
                    column.forEach { TodoIcon(it, onOpen) }
                }
            }
        }
    }
}

/** 單一 ToDo 小圖示：圓形漸層 + 簡稱；已做時變淡並於右下角加 ✓。 */
@Composable
private fun TodoIcon(entry: TodoEntry, onClick: () -> Unit) {
    val fade = if (entry.done) TODO_DONE_ALPHA else 1f
    Box(Modifier.size(TODO_ICON_SIZE).clickable(onClick = withClick(onClick))) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(Brush.linearGradient(todoColors(entry.item).map { it.fade(fade) }))
                .border(1.dp, Color(0x40FFFFFF).fade(fade), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            // 字體隨圖示直徑縮放（約 60%），避免小圖示內文字溢出
            val textSize = with(LocalDensity.current) { (TODO_ICON_SIZE * 0.6f).toSp() }
            Text(
                entry.item.short,
                color = Color.White.fade(fade),
                style = TextStyle(
                    fontSize = textSize,
                    lineHeight = textSize,
                    fontWeight = FontWeight.Bold,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                ),
                maxLines = 1,
            )
        }
        if (entry.done) DoneBadge(Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp).size(TODO_ICON_SIZE * 0.5f))
    }
}

/** 綠色圓形 ✓ 徽章（以 Canvas 繪製，確保在任何字型下都置中）。 */
@Composable
private fun DoneBadge(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val r = size.minDimension / 2f
        drawCircle(DoneGreen, r)
        drawCircle(Color.White, r, style = Stroke(width = r * 0.18f))
        val check = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.52f)
            lineTo(size.width * 0.44f, size.height * 0.68f)
            lineTo(size.width * 0.74f, size.height * 0.36f)
        }
        drawPath(check, Color.White, style = Stroke(width = r * 0.28f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Room N Todo List 對話框。
 * 點選項目加入清單（依點選先後編號），再點一次移除，後續編號自動遞補。
 * 「清空」只清除對話框內的選取，按「確定」才會套用；「取消」放棄本次修改。
 */
@Composable
private fun TodoDialog(
    room: Int,
    current: List<TodoEntry>,
    onConfirm: (List<TodoEntry>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selection = remember { mutableStateListOf<TodoEntry>().apply { addAll(current) } }
    val gridWidth = TODO_TILE_SIZE * 4 + TODO_TILE_GAP * 3
    val panelShape = RoundedCornerShape(24.dp)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .clip(panelShape)
                .background(Color(0xFF262626))
                .border(1.dp, Color(0x33FFFFFF), panelShape)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 22.dp)
                .width(gridWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ModalTitle("Room $room Todo List")
            Spacer(Modifier.height(26.dp))

            Column(verticalArrangement = Arrangement.spacedBy(TODO_TILE_GAP)) {
                TodoItem.entries.chunked(4).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(TODO_TILE_GAP)) {
                        rowItems.forEach { item ->
                            val index = selection.indexOfFirst { it.item == item }
                            TodoTile(
                                item = item,
                                order = if (index >= 0) index + 1 else null,
                                done = index >= 0 && selection[index].done,
                            ) {
                                if (index >= 0) selection.removeAt(index) else selection.add(TodoEntry(item))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DialogActionButton("確定", Color(0xFF5BA552), Modifier.weight(1.8f)) { onConfirm(selection.toList()) }
                DialogActionButton("清空", Color(0xFF8E4BAE), Modifier.weight(1f)) { selection.clear() }
                DialogActionButton("取消", Color(0xFF555555), Modifier.weight(1f), onDismiss)
            }
        }
    }
}

/** ToDo 對話框中的方格：選取時白框 + 光暈 + 右上角紅色序號；已做時變淡並加 ✓。 */
@Composable
private fun TodoTile(item: TodoItem, order: Int?, done: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val selected = order != null
    val fade = if (done) 0.45f else 1f
    Box(Modifier.size(TODO_TILE_SIZE)) {
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (selected) {
                        Modifier.shadow(10.dp, shape, ambientColor = Color.White, spotColor = Color.White)
                    } else {
                        Modifier
                    }
                )
                .clip(shape)
                .background(Brush.linearGradient(todoColors(item).map { it.fade(fade) }))
                .then(if (selected) Modifier.border(3.dp, Color.White, shape) else Modifier)
                .clickable(onClick = withClick(onClick)),
            contentAlignment = Alignment.Center
        ) {
            Text(item.full, color = Color.White.fade(fade), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        if (done) DoneBadge(Modifier.align(Alignment.BottomEnd).padding(6.dp).size(20.dp))
        if (order != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 9.dp, y = (-9).dp)
                    .size(28.dp)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(OrderBadgeRed)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("$order", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DialogActionButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = withClick(onClick),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(54.dp),
    ) {
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SmallButton(
    text: String,
    enabled: Boolean,
    colors: ButtonColors,
    weight: Float = 1f,
    onClick: () -> Unit,
) {
    Button(
        onClick = withClick(onClick),
        enabled = enabled,
        colors = colors,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.weight(weight).fillMaxHeight().padding(2.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** 所有對話框共用的標題樣式：置中、發光文字，下方一條分隔線。 */
@Composable
private fun ModalTitle(text: String) {
    // 所有對話框都使用此標題，順便在此保持對話框視窗的全螢幕狀態
    KeepDialogImmersive()
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text,
            textAlign = TextAlign.Center,
            style = TextStyle(
                color = Color(0xFFF0F8FF),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                shadow = Shadow(color = Color(0x99AEE6FF), blurRadius = 18f),
            ),
        )
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
    }
}

/** 標籤選擇：計時開始後必須 8 選 1，不提供「稍後」，也不可點外部或返回鍵關閉。 */
@Composable
private fun LabelDialog(room: Int, current: TimerLabel?, onSelect: (TimerLabel) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { ModalTitle("Room $room Timer Type") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimerLabel.entries.chunked(4).forEach { rowLabels ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowLabels.forEach { label ->
                            val selected = label == current
                            val colors = LabelColors[label] ?: listOf(LabelChipColor, LabelChipColor)
                            Button(
                                onClick = withClick { onSelect(label) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.Transparent,
                                    contentColor = Color.White,
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.width(120.dp).height(64.dp),
                                contentPadding = PaddingValues(0.dp),
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(Brush.linearGradient(colors))
                                        .then(
                                            if (selected) {
                                                Modifier.border(
                                                    width = 3.dp,
                                                    color = Color.White,
                                                    shape = RoundedCornerShape(10.dp)
                                                )
                                            } else {
                                                Modifier
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(label.full, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { ModalTitle(title) },
        text = { Text(text, fontSize = 20.sp) },
        confirmButton = { Button(onClick = withClick(onConfirm)) { Text("確定", fontSize = 18.sp) } },
        dismissButton = { TextButton(onClick = withClick(onDismiss)) { Text("取消", fontSize = 18.sp) } },
    )
}

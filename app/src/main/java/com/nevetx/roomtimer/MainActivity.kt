package com.nevetx.roomtimer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

private val BgColor = Color(0xFF15181D)
private val IdleColor = Color(0xFF2B2F36)
private val RunningColor = Color(0xFF1E3A5F)
private val ExpiredColor = Color(0xFFD32F2F)
private val LabelChipColor = Color(0xFFFFC107)

private val ADJUSTMENTS = listOf("-1m" to -60, "+1m" to 60, "-10s" to -10, "+10s" to 10)
private val PRESETS = listOf(1, 3, 5, 10, 15)

private sealed interface DialogState {
    data class Label(val room: Int) : DialogState
    data class ConfirmPreset(val room: Int, val minutes: Int) : DialogState
    data class ConfirmStop(val room: Int) : DialogState
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
}

@Composable
private fun TimerScreen() {
    val rooms by TimerController.rooms.collectAsState()
    val queue by TimerController.announceQueue.collectAsState()
    var dialog by remember { mutableStateOf<DialogState?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(6.dp)
    ) {
        TopBar(queue)
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
                                dialog = DialogState.Label(state.room)
                            }
                        },
                        onLabel = { dialog = DialogState.Label(state.room) },
                        onStop = { dialog = DialogState.ConfirmStop(state.room) },
                    )
                }
            }
        }
    }

    when (val d = dialog) {
        is DialogState.Label -> LabelDialog(
            room = d.room,
            current = rooms[d.room - 1].label,
            onSelect = { TimerController.setLabel(d.room, it); dialog = null },
            onDismiss = { dialog = null },
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
        null -> {}
    }
}

@Composable
private fun TopBar(queue: List<Int>) {
    var ips by remember { mutableStateOf(StatusHttpServer.localIpv4Addresses()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            ips = StatusHttpServer.localIpv4Addresses()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Room 計時器", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(24.dp))
        val url = if (ips.isEmpty()) "未連接網絡" else ips.joinToString("  ") { "http://$it:${RoomTimerApp.HTTP_PORT}" }
        Text("PC 檢視: $url", color = Color(0xFFB0BEC5), fontSize = 15.sp)
        Spacer(Modifier.weight(1f))
        if (queue.isNotEmpty()) {
            Text("時間到: Room ${queue.joinToString(", ")}", color = Color(0xFFFF8A80), fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
) {
    val bg = when (state.phase) {
        Phase.IDLE -> IdleColor
        Phase.RUNNING -> RunningColor
        Phase.EXPIRED -> ExpiredColor
    }
    val active = state.phase != Phase.IDLE
    val running = state.phase == Phase.RUNNING
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

    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(34.dp)) {
            Text("Room ${state.room}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            state.label?.let {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(LabelChipColor)
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text(it.short, color = Color.Black, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                state.display,
                color = if (state.phase == Phase.IDLE) Color(0xFF777777) else Color.White,
                fontSize = 60.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        Row(Modifier.fillMaxWidth()) {
            ADJUSTMENTS.forEach { (text, sec) ->
                SmallButton(text, active, btnColors) { onAdjust(sec) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            PRESETS.forEach { min ->
                SmallButton("${min}m", true, presetColors) { onPreset(min) }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            SmallButton(state.label?.let { "標籤: ${it.full}" } ?: "標籤", running, btnColors, weight = 3f) { onLabel() }
            SmallButton("STOP", active, stopColors, weight = 2f) { onStop() }
        }
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
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.weight(weight).height(46.dp).padding(2.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun LabelDialog(room: Int, current: TimerLabel?, onSelect: (TimerLabel) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Room $room 計時標籤") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TimerLabel.entries.chunked(4).forEach { rowLabels ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowLabels.forEach { label ->
                            val selected = label == current
                            Button(
                                onClick = { onSelect(label) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selected) LabelChipColor else Color(0xFF455A64),
                                    contentColor = if (selected) Color.Black else Color.White,
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.width(120.dp).height(64.dp),
                            ) {
                                Text(label.full, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("稍後", fontSize = 18.sp) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text, fontSize = 20.sp) },
        confirmButton = { Button(onClick = onConfirm) { Text("確定", fontSize = 18.sp) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", fontSize = 18.sp) } },
    )
}

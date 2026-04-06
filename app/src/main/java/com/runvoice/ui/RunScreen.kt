package com.runvoice.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runvoice.model.RunData
import com.runvoice.share.RunSummaryImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor = Color(0xFF1A1A2E)
private val CardColor = Color(0xFF16213E)
private val AccentGreen = Color(0xFF00E676)
private val AccentYellow = Color(0xFFFFD600)
private val AccentRed = Color(0xFFFF5252)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0BEC5)
private val TextMuted = Color(0xFF7F8C99)

@Composable
fun RunScreen(
    runData: RunData,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onSaveAndStop: () -> Unit,
    onDiscardAndStop: () -> Unit,
    onOpenHrSettings: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onToggleMetronome: () -> Unit = {},
    onBpmChange: (Int) -> Unit = {},
    hrConnected: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageSaver = remember(context) { RunSummaryImageSaver(context) }
    var showStopConfirm by remember { mutableStateOf(false) }
    var stopConfirmAtMillis by remember { mutableStateOf(0L) }

    if (showStopConfirm) {
        StopRunConfirmScreen(
            runData = runData,
            finishedAtMillis = stopConfirmAtMillis.takeIf { it > 0L } ?: System.currentTimeMillis(),
            onSaveAndStop = {
                showStopConfirm = false
                onSaveAndStop()
            },
            onDiscardAndStop = {
                showStopConfirm = false
                onDiscardAndStop()
            },
            onSaveSnapshot = { finishedAtMillis ->
                scope.launch {
                    val message = withContext(Dispatchers.IO) {
                        imageSaver.saveSummary(runData, finishedAtMillis)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        HeaderRow(
            hrConnected = hrConnected,
            onOpenAbout = onOpenAbout,
            onOpenHrSettings = onOpenHrSettings
        )

        HeroRunCard(
            distance = "${runData.distanceFormatted} km",
            heartRate = if (runData.heartRate > 0) "${runData.heartRate}" else "--",
            maxHeartRate = if (runData.maxHeartRate > 0) "${runData.maxHeartRate}" else "--",
            status = when {
                runData.isPaused -> "已暂停"
                runData.isRunning -> "跑步进行中"
                else -> "准备开始"
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "时间",
                value = runData.timeFormatted,
                unit = "",
                valueColor = AccentYellow
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "配速",
                value = runData.paceFormatted,
                unit = "/km",
                valueColor = if (runData.paceSecondsPerKm > 0) AccentYellow else TextMuted
            )
        }

        MetronomeControl(
            bpm = runData.metronomeBpm,
            isPlaying = runData.metronomeActive,
            onToggle = onToggleMetronome,
            onBpmChange = onBpmChange
        )

        Text(
            text = if (hrConnected) {
                "跑步时优先听耳机播报，手机只负责大号读数和快速操作。"
            } else {
                "当前未连接心率监控设备，仍可记录时间、距离和配速。"
            },
            color = TextSecondary,
            fontSize = 14.sp,
            lineHeight = 20.sp
        )

        Spacer(modifier = Modifier.weight(1f))

        when {
            !runData.isRunning -> {
                Button(
                    onClick = onStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("▶  开始跑步", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = BgColor)
                }
            }
            runData.isPaused -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = {
                            stopConfirmAtMillis = System.currentTimeMillis()
                            showStopConfirm = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            "⏹  结束本次",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onResume,
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            "▶  继续跑步",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = BgColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            else -> {
                Button(
                    onClick = onPause,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("⏸  暂停跑步", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun StopRunConfirmScreen(
    runData: RunData,
    finishedAtMillis: Long,
    onSaveAndStop: () -> Unit,
    onDiscardAndStop: () -> Unit,
    onSaveSnapshot: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = formatFinishedAt(finishedAtMillis),
            color = TextPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        StopRunSummaryCard(runData = runData)

        StopRunHintCard(
            title = "请选择如何处理本次记录",
            body = "保存后会保留本次跑步数据。放弃后，本次 GPS 轨迹文件也会一起删除。也可以先把当前摘要保存为本地截图。"
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onSaveAndStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("保存数据", fontSize = 20.sp, color = BgColor, fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onDiscardAndStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentRed),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("放弃本次", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        FilledTonalButton(
            onClick = { onSaveSnapshot(finishedAtMillis) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = CardColor,
                contentColor = AccentYellow
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text("保存截图", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StopRunSummaryCard(runData: RunData) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("当前记录", color = TextSecondary, fontSize = 14.sp)
            Surface(
                color = BgColor,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = "已暂停",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Text(
            text = "${runData.distanceFormatted} km",
            color = AccentYellow,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "时间",
                value = runData.timeFormatted,
                unit = "",
                valueColor = AccentYellow
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                label = "平均配速",
                value = averagePaceFormatted(runData),
                unit = "/km",
                valueColor = if (runData.distanceMeters > 0f) AccentYellow else TextMuted
            )
        }

        if (runData.maxHeartRate > 0) {
            Surface(
                color = BgColor,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("最大心率", color = TextSecondary, fontSize = 14.sp)
                    Text(
                        text = "${runData.maxHeartRate} bpm",
                        color = AccentRed,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun StopRunHintCard(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(16.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(body, color = TextSecondary, fontSize = 15.sp, lineHeight = 22.sp)
    }
}

private fun averagePaceFormatted(runData: RunData): String {
    if (runData.distanceMeters <= 0f || runData.elapsedSeconds <= 0L) return "--'--\""
    val secondsPerKm = ((runData.elapsedSeconds * 1000f) / runData.distanceMeters).toInt()
    val minutes = secondsPerKm / 60
    val seconds = secondsPerKm % 60
    return "%d'%02d\"".format(minutes, seconds)
}

private fun formatFinishedAt(finishedAtMillis: Long): String {
    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(finishedAtMillis))
}

@Composable
private fun HeaderRow(
    hrConnected: Boolean,
    onOpenAbout: () -> Unit,
    onOpenHrSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onOpenAbout) {
            Text(
                text = "RunVoice",
                color = AccentGreen,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        FilledTonalButton(
            onClick = onOpenHrSettings,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = CardColor,
                contentColor = AccentGreen
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = if (hrConnected) "心率监控已连接" else "心率监控未连接",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun HeroRunCard(
    distance: String,
    heartRate: String,
    maxHeartRate: String,
    status: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "距离", color = TextSecondary, fontSize = 16.sp)
            Surface(
                color = BgColor,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = status,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        Text(
            text = distance,
            color = AccentYellow,
            fontSize = 54.sp,
            fontWeight = FontWeight.Bold
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "当前心率", color = TextSecondary, fontSize = 14.sp)
                Text(
                    text = "$heartRate bpm",
                    color = if (heartRate == "--") TextMuted else AccentYellow,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(text = "最大心率", color = TextSecondary, fontSize = 14.sp)
                Text(
                    text = "$maxHeartRate bpm",
                    color = if (maxHeartRate == "--") TextMuted else AccentRed,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
    valueColor: Color
) {
    Column(
        modifier = modifier
            .background(CardColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(text = label, color = TextSecondary, fontSize = 15.sp)
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                color = valueColor,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    color = TextSecondary,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun MetronomeControl(
    bpm: Int,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onBpmChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "节拍器", color = TextSecondary, fontSize = 16.sp)
            Text(
                text = if (isPlaying) "已开启" else "未开启",
                color = if (isPlaying) TextPrimary else TextMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilledTonalButton(
                onClick = { onBpmChange(bpm - 1) },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = BgColor,
                    contentColor = TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Text("▼", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                onClick = onToggle,
                color = if (isPlaying) AccentRed else AccentGreen,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "$bpm BPM",
                        color = if (isPlaying) TextPrimary else BgColor,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            FilledTonalButton(
                onClick = { onBpmChange(bpm + 1) },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = BgColor,
                    contentColor = TextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
            ) {
                Text("▲", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

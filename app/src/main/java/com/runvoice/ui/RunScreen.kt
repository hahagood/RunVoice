package com.runvoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runvoice.model.RunData

private val BgColor = Color(0xFF1A1A2E)
private val CardColor = Color(0xFF16213E)
private val AccentGreen = Color(0xFF00E676)
private val AccentYellow = Color(0xFFFFD600)
private val AccentRed = Color(0xFFFF5252)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0BEC5)

@Composable
fun RunScreen(
    runData: RunData,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onOpenHrSettings: () -> Unit,
    onOpenAbout: () -> Unit = {},
    onToggleMetronome: () -> Unit = {},
    onBpmChange: (Int) -> Unit = {},
    hrConnected: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Title + HR status in one row
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
            TextButton(onClick = onOpenHrSettings) {
                Text(
                    text = if (hrConnected) "心率带: 已连接" else "心率带: 未连接",
                    color = if (hrConnected) AccentGreen else TextSecondary,
                    fontSize = 13.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Data display
        DataCard(label = "时间", value = runData.timeFormatted, color = TextPrimary)
        Spacer(modifier = Modifier.height(16.dp))
        DataCard(label = "心率", value = if (runData.heartRate > 0) "${runData.heartRate} bpm" else "-- bpm", color = AccentRed)
        Spacer(modifier = Modifier.height(16.dp))
        DataCard(label = "配速", value = "${runData.paceFormatted}/km", color = AccentYellow)
        Spacer(modifier = Modifier.height(16.dp))
        DataCard(label = "距离", value = "${runData.distanceFormatted} km", color = AccentGreen)

        Spacer(modifier = Modifier.height(16.dp))

        // Metronome control
        MetronomeControl(
            bpm = runData.metronomeBpm,
            isPlaying = runData.metronomeActive,
            onToggle = onToggleMetronome,
            onBpmChange = onBpmChange
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Buttons:
        when {
            !runData.isRunning -> {
                // Idle: single start button
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
                // Paused: finish on left (less accessible), resume on right (easy to tap)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = onStop,
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("⏹  结束跑步", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    }
                    Button(
                        onClick = onResume,
                        modifier = Modifier
                            .weight(1f)
                            .height(72.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentGreen),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("▶  继续跑步", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = BgColor)
                    }
                }
            }
            else -> {
                // Running: single stop button
                Button(
                    onClick = onPause,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("⏹  停止跑步", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun DataCard(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(12.dp))
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 16.sp
        )
        Text(
            text = value,
            color = color,
            fontSize = 42.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MetronomeControl(
    bpm: Int,
    isPlaying: Boolean,
    onToggle: () -> Unit,
    onBpmChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "节拍器",
            color = TextSecondary,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.weight(1f))
        IconButton(onClick = { onBpmChange(bpm - 5) }) {
            Text("◀", color = TextSecondary, fontSize = 18.sp)
        }
        TextButton(onClick = onToggle) {
            Text(
                text = "$bpm",
                color = if (isPlaying) AccentGreen else TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
        IconButton(onClick = { onBpmChange(bpm + 5) }) {
            Text("▶", color = TextSecondary, fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}

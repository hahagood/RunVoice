package com.runvoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.runvoice.recovery.RunCheckpoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RecoveryBackground = Color(0xFF1A1A2E)
private val RecoveryCard = Color(0xFF16213E)
private val RecoveryGreen = Color(0xFF00E676)
private val RecoveryRed = Color(0xFFFF5252)
private val RecoveryPrimary = Color.White
private val RecoverySecondary = Color(0xFFB0BEC5)

@Composable
fun RunRecoveryScreen(
    checkpoint: RunCheckpoint,
    recoveryInProgress: Boolean,
    errorMessage: String?,
    onContinue: () -> Unit,
    onStartNew: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RecoveryBackground)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "发现未完成的跑步",
            color = RecoveryPrimary,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(18.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(RecoveryCard, RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = formatRecoveryDate(checkpoint.updatedAtEpochMillis),
                color = RecoverySecondary,
                fontSize = 15.sp
            )
            Text(
                text = "距离  ${"%.2f".format(checkpoint.distanceMeters / 1_000f)} km",
                color = RecoveryPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "有效运动时间  ${formatRecoveryElapsed(checkpoint.elapsedSeconds)}",
                color = RecoveryPrimary,
                fontSize = 18.sp
            )
            Text(
                text = if (checkpoint.wasPaused) "上次状态  已暂停" else "上次状态  跑步中",
                color = RecoverySecondary,
                fontSize = 15.sp
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "继续后会追加写入原轨迹。断电或退出期间不计时间、不补距离，第一个新定位点只用于重新建立 GPS 锚点。",
            color = RecoverySecondary,
            fontSize = 15.sp,
            lineHeight = 22.sp
        )
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = errorMessage,
                color = RecoveryRed,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onContinue,
            enabled = !recoveryInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            colors = ButtonDefaults.buttonColors(containerColor = RecoveryGreen),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = if (recoveryInProgress) "正在恢复…" else "继续上次跑步",
                color = RecoveryBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedButton(
            onClick = onStartNew,
            enabled = !recoveryInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RecoveryRed),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(
                text = "放弃上次记录，开始新的跑步",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun formatRecoveryDate(epochMillis: Long): String =
    SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun formatRecoveryElapsed(seconds: Long): String {
    val hours = seconds / 3_600
    val minutes = (seconds % 3_600) / 60
    val remainingSeconds = seconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        "%02d:%02d".format(minutes, remainingSeconds)
    }
}

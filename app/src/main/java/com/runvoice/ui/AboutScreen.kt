package com.runvoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BgColor = Color(0xFF1A1A2E)
private val AccentGreen = Color(0xFF00E676)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0BEC5)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .padding(24.dp)
    ) {
        // Top bar with back button
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = AccentGreen, fontSize = 16.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "RunVoice",
            color = AccentGreen,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SectionTitle("功能简介")
            SectionBody(
                "RunVoice 是一款轻量级跑步语音播报应用。" +
                "它通过 GPS 实时追踪跑步数据，并定时以语音播报配速、距离、心率等关键指标，" +
                "帮助跑者在运动中无需看手机即可掌握训练状态。\n\n" +
                "主要功能：\n" +
                "• GPS 实时定位与距离/配速计算\n" +
                "• 蓝牙心率带连接（BLE）\n" +
                "• 语音播报（每公里自动播报）\n" +
                "• 节拍器辅助步频控制"
            )

            Spacer(modifier = Modifier.height(20.dp))

            SectionTitle("使用说明")
            SectionBody(
                "1. 首次使用请授予定位、蓝牙、通知权限\n" +
                "2. 点击右上角「心率带」可搜索并连接 BLE 心率设备\n" +
                "3. 点击「开始跑步」启动 GPS 追踪和语音播报\n" +
                "4. 跑步过程中可点击「停止跑步」暂停，再选择继续或结束\n" +
                "5. 节拍器可在跑步前或跑步中随时开关和调整 BPM"
            )

            Spacer(modifier = Modifier.height(20.dp))

            SectionTitle("免责声明")
            SectionBody(
                "使用 RunVoice 前请仔细阅读以下条款：\n\n" +
                "1. 健康风险：跑步是一项高强度有氧运动，可能对心血管系统造成负担。" +
                "请在开始任何运动计划前咨询医生，确认您的身体状况适合跑步训练。\n\n" +
                "2. 数据准确性：本应用的 GPS 定位、配速计算和心率数据仅供参考，" +
                "可能受到设备精度、信号环境、天气等因素影响，不保证数据完全准确。" +
                "请勿将本应用数据作为医疗诊断或专业训练的唯一依据。\n\n" +
                "3. 安全注意：跑步时请注意周围环境和交通安全。" +
                "使用语音播报时请确保仍能听到环境声音。" +
                "切勿在跑步过程中操作手机。\n\n" +
                "4. 责任限制：本应用按「现状」提供，开发者不对因使用本应用而导致的" +
                "任何直接或间接损失（包括但不限于人身伤害、财产损失、数据丢失）承担责任。\n\n" +
                "5. 使用即代表您已阅读、理解并同意以上全部条款。"
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        color = AccentGreen,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun SectionBody(text: String) {
    Text(
        text = text,
        color = TextSecondary,
        fontSize = 15.sp,
        lineHeight = 22.sp
    )
}

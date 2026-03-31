package com.runvoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BgColor = Color(0xFF1A1A2E)
private val CardColor = Color(0xFF16213E)
private val AccentGreen = Color(0xFF00E676)
private val AccentRed = Color(0xFFFF5252)
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0BEC5)
private val TextMuted = Color(0xFF7F8C99)

data class HrDeviceUiState(
    val available: Boolean = false,
    val scanning: Boolean = false,
    val devices: List<HrDeviceItem> = emptyList(),
    val connectedAddress: String? = null,
    val savedAddress: String? = null
)

data class HrDeviceItem(
    val name: String,
    val address: String,
    val rssi: Int
)

@Composable
fun HrDeviceScreen(
    state: HrDeviceUiState,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectDevice: (String) -> Unit,
    onDisconnect: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = AccentGreen, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("心率带设置", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        DeviceStatusCard(
            savedAddress = state.savedAddress,
            connected = state.connectedAddress != null,
            onDisconnect = onDisconnect
        )

        Button(
            onClick = { if (state.scanning) onStopScan() else onStartScan() },
            enabled = state.available,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.scanning) AccentRed else AccentGreen,
                disabledContainerColor = CardColor,
                disabledContentColor = TextSecondary
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            when {
                !state.available -> {
                    Text("心率模块初始化中", fontSize = 18.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                }
                state.scanning -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = TextPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("停止搜索", fontSize = 18.sp, color = TextPrimary, fontWeight = FontWeight.Bold)
                }
                else -> {
                    Text("搜索设备", fontSize = 18.sp, color = BgColor, fontWeight = FontWeight.Bold)
                }
            }
        }

        when {
            state.devices.isNotEmpty() -> {
                Text(
                    text = "点击设备即可连接。优先选择你平时固定使用的心率带。",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.devices) { device ->
                        val isConnected = device.address == state.connectedAddress
                        DeviceRow(
                            device = device,
                            isConnected = isConnected,
                            isSaved = device.address == state.savedAddress,
                            onSelect = { onSelectDevice(device.address) }
                        )
                    }
                }
            }
            state.scanning -> {
                EmptyStateCard(
                    title = "正在搜索附近设备",
                    body = "请先佩戴并唤醒心率带，保持它在手机附近。搜索结束后会自动显示结果。"
                )
            }
            !state.available -> {
                EmptyStateCard(
                    title = "心率模块正在准备",
                    body = "页面刚打开时可能会有短暂初始化，稍等片刻后就可以开始搜索设备。"
                )
            }
            else -> {
                EmptyStateCard(
                    title = "还没有开始搜索",
                    body = "建议先把常用心率带连好，之后就可以主要通过耳机语音掌握心率变化。"
                )
            }
        }
    }
}

@Composable
private fun DeviceStatusCard(
    savedAddress: String?,
    connected: Boolean,
    onDisconnect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("当前设备", color = TextSecondary, fontSize = 14.sp)
            Surface(
                color = BgColor,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = when {
                        connected -> "已连接"
                        savedAddress != null -> "已保存"
                        else -> "未配置"
                    },
                    color = when {
                        connected -> TextPrimary
                        savedAddress != null -> TextSecondary
                        else -> TextMuted
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }

        if (savedAddress == null) {
            Text(
                text = "还没有保存过心率带。连接成功后，下次会自动尝试重连。",
                color = TextSecondary,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
            return
        }

        Text(
            text = savedAddress,
            color = TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (connected) "跑步开始后会继续保持连接。" else "设备已保存，但当前尚未连接。",
            color = TextSecondary,
            fontSize = 14.sp
        )

        OutlinedButton(
            onClick = onDisconnect,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = AccentRed
            )
        ) {
            Text("断开并清除")
        }
    }
}

@Composable
private fun DeviceRow(
    device: HrDeviceItem,
    isConnected: Boolean,
    isSaved: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(14.dp))
            .clickable { onSelect() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = if (device.name.isBlank()) "未知设备" else device.name,
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )
            Text(device.address, color = TextSecondary, fontSize = 13.sp)
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("${device.rssi} dBm", color = TextSecondary, fontSize = 13.sp)
            if (isConnected || isSaved) {
                FilledTonalButton(
                    onClick = onSelect,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = BgColor,
                        contentColor = if (isConnected) TextPrimary else TextSecondary
                    )
                ) {
                    Text(if (isConnected) "已连接" else "已保存", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun EmptyStateCard(title: String, body: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(CardColor, RoundedCornerShape(16.dp))
            .padding(20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(body, color = TextSecondary, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

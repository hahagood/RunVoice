package com.runvoice.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
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
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFB0BEC5)

data class HrDeviceUiState(
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
            .padding(24.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("← 返回", color = AccentGreen, fontSize = 18.sp)
            }
            Spacer(modifier = Modifier.weight(1f))
            Text("心率带设置", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.width(64.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Connected device info
        if (state.savedAddress != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CardColor, RoundedCornerShape(12.dp))
                    .padding(16.dp)
            ) {
                Text("已保存设备", color = TextSecondary, fontSize = 14.sp)
                Text(state.savedAddress, color = TextPrimary, fontSize = 16.sp)
                Text(
                    if (state.connectedAddress != null) "已连接" else "未连接",
                    color = if (state.connectedAddress != null) AccentGreen else Color(0xFFFF5252),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                ) {
                    Text("断开并清除")
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Scan button
        Button(
            onClick = { if (state.scanning) onStopScan() else onStartScan() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (state.scanning) Color(0xFFFF5252) else AccentGreen
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (state.scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = TextPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("停止扫描", fontSize = 18.sp, color = TextPrimary)
            } else {
                Text("扫描心率设备", fontSize = 18.sp, color = BgColor, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Device list
        if (state.devices.isEmpty() && state.scanning) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("正在搜索附近心率设备...", color = TextSecondary, fontSize = 16.sp)
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.devices) { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(CardColor, RoundedCornerShape(12.dp))
                        .clickable { onSelectDevice(device.address) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                        Text(device.address, color = TextSecondary, fontSize = 13.sp)
                    }
                    Text("${device.rssi} dBm", color = TextSecondary, fontSize = 14.sp)
                }
            }
        }
    }
}

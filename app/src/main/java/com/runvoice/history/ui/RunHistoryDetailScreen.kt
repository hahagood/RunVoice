package com.runvoice.history.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runvoice.history.data.RunHistoryRepository
import com.runvoice.history.model.RunArchiveStatus
import com.runvoice.history.model.RunRecord

@Composable
fun RunHistoryDetailRoute(
    recordId: String,
    repository: RunHistoryRepository,
    onBack: () -> Unit,
    onDeleted: () -> Unit
) {
    val detailViewModel: HistoryDetailViewModel = viewModel(
        key = recordId,
        factory = HistoryDetailViewModel.Factory(recordId, repository)
    )
    val record by detailViewModel.record.collectAsStateWithLifecycle()
    val deleting by detailViewModel.deleting.collectAsStateWithLifecycle()
    val errorMessage by detailViewModel.errorMessage.collectAsStateWithLifecycle()
    RunHistoryDetailScreen(
        record = record,
        deleting = deleting,
        errorMessage = errorMessage,
        onBack = onBack,
        onDelete = { detailViewModel.delete(onDeleted) }
    )
}

@Composable
fun RunHistoryDetailScreen(
    record: RunRecord?,
    deleting: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onDelete: () -> Unit
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HistoryBackground)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TextButton(onClick = onBack) { Text("‹ 返回历史记录", color = HistoryGreen) }
        if (record == null) {
            Text("记录不存在或正在读取。", color = HistorySecondary, fontSize = 16.sp)
            return@Column
        }
        Text(
            text = formatHistoryFullDate(record.finishedAtEpochMillis),
            color = HistoryPrimary,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        DetailMetrics(record)
        ArtifactStatusCard(record)
        errorMessage?.let { Text(it, color = HistoryRed, fontSize = 15.sp) }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = { confirmDelete = true },
            enabled = !deleting,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = HistoryRed),
            shape = RoundedCornerShape(14.dp)
        ) {
            Text(if (deleting) "正在删除…" else "删除历史记录", fontWeight = FontWeight.Bold)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("删除这条历史记录？") },
            text = {
                Text("会同时删除应用内轨迹和应用内海报；已导出到 Documents 或系统相册的公共副本仍会保留。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("删除", color = HistoryRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DetailMetrics(record: RunRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HistoryCard, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        DetailRow("距离", formatHistoryDistance(record.distanceMeters), HistoryGreen)
        DetailRow("有效运动时间", formatHistoryDuration(record.elapsedSeconds), HistoryYellow)
        DetailRow("平均配速", formatHistoryPace(record.averagePaceSecondsPerKm), HistoryYellow)
        DetailRow(
            "最大心率",
            if (record.maxHeartRateBpm > 0) "${record.maxHeartRateBpm} bpm" else "--",
            if (record.maxHeartRateBpm > 0) HistoryRed else HistoryMuted
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = HistorySecondary, fontSize = 14.sp)
        Spacer(modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ArtifactStatusCard(record: RunRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HistoryCard, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Text("保存状态", color = HistoryPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text(
            if (record.archiveStatus == RunArchiveStatus.Complete) "完整保存" else "部分保存",
            color = if (record.archiveStatus == RunArchiveStatus.Complete) HistoryGreen else HistoryRed
        )
        Text(if (record.posterReference != null) "摘要海报：已保存" else "摘要海报：未保存", color = HistorySecondary)
        Text(if (record.traceLocalPath != null) "应用内轨迹：已保留" else "应用内轨迹：未保留", color = HistorySecondary)
        Text(if (record.tracePublicReference != null) "公共轨迹：已导出" else "公共轨迹：未导出", color = HistorySecondary)
        Text(
            "轨迹缩略图将在后续版本中使用现有海报或轨迹引用生成。",
            color = HistoryMuted,
            fontSize = 13.sp
        )
    }
}

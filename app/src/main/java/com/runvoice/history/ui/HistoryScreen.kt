package com.runvoice.history.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.runvoice.history.model.RunMonthSummary
import com.runvoice.history.model.RunRecord
import java.time.YearMonth

internal val HistoryBackground = Color(0xFF1A1A2E)
internal val HistoryCard = Color(0xFF16213E)
internal val HistoryGreen = Color(0xFF00E676)
internal val HistoryYellow = Color(0xFFFFD600)
internal val HistoryRed = Color(0xFFFF5252)
internal val HistoryPrimary = Color.White
internal val HistorySecondary = Color(0xFFB0BEC5)
internal val HistoryMuted = Color(0xFF7F8C99)

@Composable
fun HistoryRoute(
    repository: RunHistoryRepository,
    onBack: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    val historyViewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModel.Factory(repository)
    )
    val state by historyViewModel.uiState.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        onBack = onBack,
        onPreviousMonth = historyViewModel::previousMonth,
        onNextMonth = historyViewModel::nextMonth,
        onOpenRecord = onOpenRecord
    )
}

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onBack: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenRecord: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HistoryBackground)
            .safeDrawingPadding()
    ) {
        HistoryHeader(
            month = state.selectedMonth,
            onBack = onBack,
            onPreviousMonth = onPreviousMonth,
            onNextMonth = onNextMonth
        )

        when {
            state.loading -> {
                Spacer(modifier = Modifier.weight(1f))
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    color = HistoryGreen
                )
                Spacer(modifier = Modifier.weight(1f))
            }
            state.errorMessage != null -> {
                HistoryMessage(state.errorMessage, HistoryRed)
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 20.dp,
                        end = 20.dp,
                        top = 4.dp,
                        bottom = 28.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { MonthSummaryCard(state.summary) }
                    if (state.records.isEmpty()) {
                        item {
                            HistoryMessage(
                                "这个月还没有保存的跑步记录。完成跑步后选择“保存海报和轨迹”，记录会出现在这里。",
                                HistorySecondary
                            )
                        }
                    } else {
                        items(state.records, key = RunRecord::id) { record ->
                            HistoryRecordCard(record, onClick = { onOpenRecord(record.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryHeader(
    month: YearMonth,
    onBack: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("‹ 返回", color = HistoryGreen) }
            Text(
                text = "历史记录",
                color = HistoryPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onPreviousMonth) { Text("‹", color = HistorySecondary, fontSize = 28.sp) }
            Text(
                text = formatHistoryMonth(month),
                color = HistoryPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onNextMonth) { Text("›", color = HistorySecondary, fontSize = 28.sp) }
        }
    }
}

@Composable
private fun MonthSummaryCard(summary: RunMonthSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(HistoryCard, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("本月汇总", color = HistoryPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryMetric("总距离", formatHistoryDistance(summary.totalDistanceMeters), Modifier.weight(1f))
            SummaryMetric("次数", "${summary.runCount} 次", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryMetric("总时长", formatHistoryDuration(summary.totalElapsedSeconds), Modifier.weight(1f))
            SummaryMetric("最长", formatHistoryDistance(summary.longestDistanceMeters), Modifier.weight(1f))
        }
        Text(
            text = "最快平均配速  ${formatHistoryPace(summary.fastestAveragePaceSecondsPerKm)}（仅统计 ≥ 1 km）",
            color = HistorySecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun SummaryMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, color = HistorySecondary, fontSize = 13.sp)
        Text(value, color = HistoryYellow, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HistoryRecordCard(record: RunRecord, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = HistoryCard,
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatHistoryDate(record.finishedAtEpochMillis),
                    color = HistoryPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (record.archiveStatus == RunArchiveStatus.Partial) {
                    Text("部分保存", color = HistoryRed, fontSize = 12.sp)
                }
            }
            Text(
                text = formatHistoryDistance(record.distanceMeters),
                color = HistoryGreen,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(formatHistoryDuration(record.elapsedSeconds), color = HistorySecondary, fontSize = 14.sp)
                Text(formatHistoryPace(record.averagePaceSecondsPerKm), color = HistorySecondary, fontSize = 14.sp)
                Text(
                    if (record.maxHeartRateBpm > 0) "最高 ${record.maxHeartRateBpm}" else "心率 --",
                    color = if (record.maxHeartRateBpm > 0) HistoryRed else HistoryMuted,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun HistoryMessage(message: String, color: Color) {
    Text(
        text = message,
        modifier = Modifier.padding(24.dp),
        color = color,
        fontSize = 15.sp,
        lineHeight = 22.sp
    )
}

package com.runvoice.history.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runvoice.history.data.RunHistoryRepository
import com.runvoice.history.model.RunMonthSummary
import com.runvoice.history.model.RunRecord
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.ExperimentalCoroutinesApi

data class HistoryUiState(
    val selectedMonth: YearMonth,
    val records: List<RunRecord> = emptyList(),
    val summary: RunMonthSummary = RunHistoryRepository.summarize(emptyList()),
    val loading: Boolean = true,
    val errorMessage: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val repository: RunHistoryRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
    initialMonth: YearMonth = YearMonth.now(zoneId)
) : ViewModel() {
    private val selectedMonth = MutableStateFlow(initialMonth)

    val uiState = selectedMonth.flatMapLatest { month ->
        val start = month.atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        repository.observeBetween(start, end).map { records ->
            HistoryUiState(
                selectedMonth = month,
                records = records,
                summary = RunHistoryRepository.summarize(records),
                loading = false
            )
        }
    }.catch { failure ->
        emit(
            HistoryUiState(
                selectedMonth = selectedMonth.value,
                loading = false,
                errorMessage = failure.message ?: "历史记录读取失败"
            )
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000L),
        HistoryUiState(selectedMonth = initialMonth)
    )

    fun previousMonth() {
        selectedMonth.value = selectedMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        val currentMonth = YearMonth.now(zoneId)
        if (selectedMonth.value < currentMonth) {
            selectedMonth.value = selectedMonth.value.plusMonths(1)
        }
    }

    class Factory(private val repository: RunHistoryRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HistoryViewModel::class.java))
            return HistoryViewModel(repository) as T
        }
    }
}

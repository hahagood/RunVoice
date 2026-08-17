package com.runvoice.history.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runvoice.history.archive.RunArchiveCoordinator
import com.runvoice.history.archive.RunArchiveResult
import com.runvoice.history.model.CompletedRunSnapshot
import com.runvoice.tracker.TraceSaveResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RunArchiveUiState(
    val saving: Boolean = false,
    val result: RunArchiveResult? = null,
    val errorMessage: String? = null
)

class RunArchiveViewModel(
    private val coordinator: RunArchiveCoordinator
) : ViewModel() {
    private val _uiState = MutableStateFlow(RunArchiveUiState())
    val uiState = _uiState.asStateFlow()

    fun archive(
        snapshot: CompletedRunSnapshot,
        finalizeTrace: suspend () -> TraceSaveResult
    ) {
        val current = _uiState.value
        if (current.saving || current.result != null) return
        _uiState.value = RunArchiveUiState(saving = true)
        viewModelScope.launch {
            try {
                _uiState.value = RunArchiveUiState(
                    result = coordinator.archive(snapshot, finalizeTrace)
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                _uiState.value = RunArchiveUiState(
                    errorMessage = failure.message ?: "保存过程发生未知错误"
                )
            }
        }
    }

    fun clear() {
        if (!_uiState.value.saving) {
            _uiState.value = RunArchiveUiState()
        }
    }

    class Factory(
        private val coordinator: RunArchiveCoordinator
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(RunArchiveViewModel::class.java))
            return RunArchiveViewModel(coordinator) as T
        }
    }
}

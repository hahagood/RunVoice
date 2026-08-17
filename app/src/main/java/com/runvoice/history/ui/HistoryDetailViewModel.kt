package com.runvoice.history.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.runvoice.history.data.RunHistoryRepository
import com.runvoice.history.model.RunRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryDetailViewModel(
    private val recordId: String,
    private val repository: RunHistoryRepository
) : ViewModel() {
    val record: StateFlow<RunRecord?> = repository.observeById(recordId)
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    private val _deleting = MutableStateFlow(false)
    val deleting = _deleting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    fun delete(onDeleted: () -> Unit) {
        if (_deleting.value) return
        viewModelScope.launch {
            _deleting.value = true
            _errorMessage.value = null
            repository.deleteById(recordId).fold(
                onSuccess = { onDeleted() },
                onFailure = { _errorMessage.value = it.message ?: "历史记录删除失败" }
            )
            _deleting.value = false
        }
    }

    class Factory(
        private val recordId: String,
        private val repository: RunHistoryRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(HistoryDetailViewModel::class.java))
            return HistoryDetailViewModel(recordId, repository) as T
        }
    }
}

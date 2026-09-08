package com.jjw.easygallery.feature.uploads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.remote.RemoteAccountRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.domain.model.UploadSummary
import com.jjw.easygallery.core.domain.model.UploadTask
import com.jjw.easygallery.core.domain.usecase.ManageUploadQueueUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UploadQueueViewModel @Inject constructor(
    queue: UploadQueueRepository,
    accounts: RemoteAccountRepository,
    private val manageQueue: ManageUploadQueueUseCase,
) : ViewModel() {

    val uiState: StateFlow<UploadQueueUiState> = combine(
        queue.observeTasks(),
        queue.observeSummary(),
        accounts.observeAccounts(),
    ) { tasks, summary, accountList ->
        UploadQueueUiState(
            tasks = tasks,
            summary = summary,
            accountNames = accountList.associate { it.id to it.displayName },
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), UploadQueueUiState())

    fun retryFailed() = viewModelScope.launch { manageQueue.retryFailed() }

    fun clearCompleted() = viewModelScope.launch { manageQueue.clearCompleted() }

    fun cancelAll() = viewModelScope.launch { manageQueue.cancelAll() }

    fun remove(taskId: Long) = viewModelScope.launch { manageQueue.remove(taskId) }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

data class UploadQueueUiState(
    val tasks: List<UploadTask> = emptyList(),
    val summary: UploadSummary = UploadSummary(),
    /** 연결된 저장소 id → 표시 이름. 행 부제에 "계정 · 폴더" 로 쓴다(Drive 는 accountId null 이라 생략) */
    val accountNames: Map<String, String> = emptyMap(),
)

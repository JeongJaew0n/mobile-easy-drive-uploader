package com.jjw.easygallery.core.data.media

import android.content.IntentSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

sealed interface MediaActionEvent {
    /** UI 가 IntentSender 를 실행하고 결과를 [MediaActionController.onConsentResult] 로 돌려준다 */
    data class LaunchConsent(val intentSender: IntentSender) : MediaActionEvent
    data class Done(val action: MediaAction, val affected: Int) : MediaActionEvent
    data object Cancelled : MediaActionEvent
    data class Failed(val message: String) : MediaActionEvent
}

/**
 * "액션 실행 → 시스템 동의 → 이어서 처리" 상태를 들고 있는 ViewModel 공용 부품.
 * 갤러리·휴지통·상세보기가 각자 같은 코드를 갖지 않도록 분리했다. ViewModel 마다 새 인스턴스.
 */
class MediaActionController @Inject constructor(
    private val runner: MediaActionRunner,
) {
    private val _isMutating = MutableStateFlow(false)
    val isMutating: StateFlow<Boolean> = _isMutating.asStateFlow()

    private val _events = Channel<MediaActionEvent>(Channel.BUFFERED)
    val events: Flow<MediaActionEvent> = _events.receiveAsFlow()

    private var pendingAction: MediaAction? = null

    fun perform(scope: CoroutineScope, action: MediaAction) {
        if (action.items.isEmpty() || _isMutating.value) return
        scope.launch { runGuarded { runner.run(action) } }
    }

    fun onConsentResult(scope: CoroutineScope, granted: Boolean) {
        val action = pendingAction ?: return
        pendingAction = null
        if (!granted) {
            _isMutating.value = false
            scope.launch { _events.send(MediaActionEvent.Cancelled) }
            return
        }
        scope.launch { runGuarded { runner.afterConsent(action) } }
    }

    @Suppress("TooGenericExceptionCaught") // UI 경계: 어떤 실패든 메시지로 전달
    private suspend fun runGuarded(block: suspend () -> ActionOutcome) {
        _isMutating.value = true
        try {
            when (val outcome = block()) {
                is ActionOutcome.NeedsConsent -> {
                    pendingAction = outcome.action
                    _events.send(MediaActionEvent.LaunchConsent(outcome.intentSender))
                    // 동의 결과가 올 때까지 isMutating 유지
                    return
                }
                is ActionOutcome.Done -> _events.send(MediaActionEvent.Done(outcome.action, outcome.affected))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "media action failed")
            _events.send(MediaActionEvent.Failed(e.message ?: e.toString()))
        }
        _isMutating.value = false
    }
}

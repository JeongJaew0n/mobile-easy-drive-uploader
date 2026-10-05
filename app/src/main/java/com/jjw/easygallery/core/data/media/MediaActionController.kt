package com.jjw.easygallery.core.data.media

import android.content.IntentSender
import com.jjw.easygallery.core.domain.model.MediaItem
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
    data class Failed(val error: Throwable) : MediaActionEvent
}

/**
 * "액션 실행 → 시스템 동의 → 이어서 처리" 상태를 들고 있는 ViewModel 공용 부품.
 * 갤러리·휴지통·상세보기가 각자 같은 코드를 갖지 않도록 분리했다. ViewModel 마다 새 인스턴스.
 *
 * **큰 묶음은 나눠서 차례로 처리한다.** MediaStore 요청 하나에 URI 는 2,000개까지다([MAX_URIS_PER_REQUEST]).
 * 조각마다 동의를 받고(미디어 관리 권한이 있으면 창 없이 지나간다), 다 끝나면 완료를 한 번만 알린다.
 * 중간에 취소하면 그때까지 처리한 만큼을 완료로 알린다 — 이미 지운 것을 "취소했습니다" 로 말하면 거짓이다.
 */
class MediaActionController @Inject constructor(
    private val runner: MediaActionRunner,
) {
    private val _isMutating = MutableStateFlow(false)
    val isMutating: StateFlow<Boolean> = _isMutating.asStateFlow()

    private val _events = Channel<MediaActionEvent>(Channel.BUFFERED)
    val events: Flow<MediaActionEvent> = _events.receiveAsFlow()

    /** 동의를 기다리는 조각 */
    private var pendingAction: MediaAction? = null

    /** 사용자가 내린 명령 전체와, 아직 남은 조각·끝난 항목 */
    private var original: MediaAction? = null
    private val remaining = ArrayDeque<MediaAction>()
    private val processed = ArrayList<MediaItem>()
    private var affected = 0

    fun perform(scope: CoroutineScope, action: MediaAction) {
        if (action.items.isEmpty() || _isMutating.value) return
        original = action
        remaining.clear()
        remaining.addAll(action.chunked())
        processed.clear()
        affected = 0
        scope.launch { drain(consented = null) }
    }

    fun onConsentResult(scope: CoroutineScope, granted: Boolean) {
        val action = pendingAction ?: return
        pendingAction = null
        if (!granted) {
            scope.launch { finish(cancelled = true) }
            return
        }
        scope.launch { drain(consented = action) }
    }

    /**
     * 남은 조각을 차례로 돌린다. 동의가 필요하면 멈추고 화면에 넘긴다 — 결과가 오면 [onConsentResult] 가 이어 간다.
     * [consented] 는 방금 동의를 받은 조각(그 뒤처리부터 한다).
     */
    @Suppress("TooGenericExceptionCaught") // UI 경계: 어떤 실패든 메시지로 전달
    private suspend fun drain(consented: MediaAction?) {
        _isMutating.value = true
        try {
            var outcome: ActionOutcome? = consented?.let { runner.afterConsent(it) }
            while (true) {
                val current = outcome ?: remaining.removeFirstOrNull()?.let { runner.run(it) } ?: break
                outcome = null
                when (current) {
                    is ActionOutcome.NeedsConsent -> {
                        pendingAction = current.action
                        _events.send(MediaActionEvent.LaunchConsent(current.intentSender))
                        // 동의 결과가 올 때까지 isMutating 유지
                        return
                    }
                    is ActionOutcome.Done -> {
                        affected += current.affected
                        processed.addAll(current.action.items)
                    }
                }
            }
            finish(cancelled = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "media action failed")
            reset()
            _events.send(MediaActionEvent.Failed(e))
        }
    }

    private suspend fun finish(cancelled: Boolean) {
        val action = original
        val done = processed.toList()
        val count = affected
        reset()
        when {
            action == null -> Unit
            // 일부라도 처리했으면 그만큼은 끝난 일이다
            !cancelled || count > 0 -> _events.send(MediaActionEvent.Done(action.withItems(done), count))
            else -> _events.send(MediaActionEvent.Cancelled)
        }
    }

    private fun reset() {
        original = null
        pendingAction = null
        remaining.clear()
        processed.clear()
        affected = 0
        _isMutating.value = false
    }
}

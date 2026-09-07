package com.jjw.easygallery.core.data.media

import android.content.IntentSender
import com.jjw.easygallery.core.domain.model.MediaItem
import javax.inject.Inject

/** 사용자가 선택 항목에 내린 편집 명령. */
sealed interface MediaAction {
    val items: List<MediaItem>

    data class Delete(override val items: List<MediaItem>) : MediaAction
    data class Trash(override val items: List<MediaItem>, val trashed: Boolean) : MediaAction
    data class Favorite(override val items: List<MediaItem>, val favorite: Boolean) : MediaAction
    data class Rename(
        val item: MediaItem,
        val newDisplayName: String,
        val writeGranted: Boolean = false,
    ) : MediaAction {
        override val items: List<MediaItem> get() = listOf(item)
    }
    data class Move(
        override val items: List<MediaItem>,
        val relativePath: String,
        val writeGranted: Boolean = false,
    ) : MediaAction
}

sealed interface ActionOutcome {
    /** UI 가 [intentSender] 를 실행하고, 결과가 OK 면 [MediaActionRunner.afterConsent] 에 [action] 을 넘긴다 */
    data class NeedsConsent(val intentSender: IntentSender, val action: MediaAction) : ActionOutcome
    data class Done(val action: MediaAction, val affected: Int) : ActionOutcome
}

/**
 * "실행 → 동의 필요 → 동의 후 이어서" 흐름을 한곳에 모은다.
 * - API 30+: 삭제/휴지통/즐겨찾기는 동의 확인 시 시스템이 변경까지 끝냄. 이름 변경/이동은 쓰기 동의 후 update 수행.
 * - API 29: 동의 후 같은 요청을 다시 시도.
 */
class MediaActionRunner @Inject constructor(
    private val repository: MediaRepository,
) {
    suspend fun run(action: MediaAction): ActionOutcome = when (action) {
        is MediaAction.Delete -> repository.requestDelete(action.items).toOutcome(action)
        is MediaAction.Trash -> repository.requestTrash(action.items, action.trashed).toOutcome(action)
        is MediaAction.Favorite -> repository.requestFavorite(action.items, action.favorite).toOutcome(action)
        is MediaAction.Rename -> withWriteConsent(action, action.writeGranted) {
            repository.rename(action.item, action.newDisplayName)
        }
        is MediaAction.Move -> withWriteConsent(action, action.writeGranted) {
            repository.move(action.items, action.relativePath)
        }
    }

    /** 동의 다이얼로그가 OK 로 끝난 뒤 호출. */
    suspend fun afterConsent(action: MediaAction): ActionOutcome = when {
        action is MediaAction.Rename -> run(action.copy(writeGranted = true))
        action is MediaAction.Move -> run(action.copy(writeGranted = true))
        repository.mutationsCompleteOnConsent -> ActionOutcome.Done(action, action.items.size)
        else -> run(action)
    }

    private suspend inline fun withWriteConsent(
        action: MediaAction,
        granted: Boolean,
        mutate: () -> MediaMutation,
    ): ActionOutcome {
        if (!granted) {
            val consent = repository.requestWrite(action.items)
            if (consent is MediaMutation.NeedsConsent) return consent.toOutcome(action)
        }
        return mutate().toOutcome(action)
    }

    private fun MediaMutation.toOutcome(action: MediaAction): ActionOutcome = when (this) {
        is MediaMutation.NeedsConsent -> ActionOutcome.NeedsConsent(intentSender, action)
        is MediaMutation.Done -> ActionOutcome.Done(action, affected)
    }
}

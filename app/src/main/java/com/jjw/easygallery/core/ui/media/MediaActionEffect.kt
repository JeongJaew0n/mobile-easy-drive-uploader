package com.jjw.easygallery.core.ui.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.jjw.easygallery.R
import com.jjw.easygallery.core.common.text.displayMessage
import com.jjw.easygallery.core.data.media.MediaAction
import com.jjw.easygallery.core.data.media.MediaActionEvent
import com.jjw.easygallery.feature.gallery.actionDoneMessage
import kotlinx.coroutines.flow.Flow

/**
 * 휴지통으로 옮긴 뒤 무엇을 더 할지(`docs/plans/ux-round2/spec.md` §4). 갤러리·상세보기만 건다 —
 * 휴지통 화면(복원)과 백업 칸(대량)에는 걸지 않는다.
 */
data class TrashFollowUp(
    /** 미디어 관리 권한이 있을 때 "실행 취소" 를 누르면 */
    val onUndo: (MediaAction.Trash) -> Unit,
    /** 권한이 없고 아직 한 번도 권한을 제안하지 않았다 */
    val offerManageMedia: Boolean,
    /** 제안을 보였다 — 다시 보이지 않게 기록한다 */
    val onManageMediaOffered: () -> Unit,
)

/** 휴지통 이동이 끝난 뒤의 스낵바 종류(순수 함수 — 테스트로 굳힌다) */
enum class TrashFollowUpKind { NONE, UNDO, OFFER_MANAGE_MEDIA }

/**
 * 권한이 있으면 실행 취소 — 되돌릴 때 확인 창이 없다. 없으면 실행 취소가 시스템 확인 창을 한 번 더 띄우므로 두지 않고,
 * 대신 한 번만 권한을 제안한다. 휴지통으로 **옮긴** 것만 해당한다(되돌리기·복원은 아님).
 */
fun trashFollowUpKind(action: MediaAction, canManageMedia: Boolean, offerManageMedia: Boolean): TrashFollowUpKind =
    when {
        action !is MediaAction.Trash || !action.trashed -> TrashFollowUpKind.NONE
        canManageMedia -> TrashFollowUpKind.UNDO
        offerManageMedia -> TrashFollowUpKind.OFFER_MANAGE_MEDIA
        else -> TrashFollowUpKind.NONE
    }

/**
 * 편집 액션 이벤트를 처리한다: 동의 다이얼로그 실행, 완료·취소·실패 스낵바.
 * 갤러리·휴지통·상세보기가 공유한다.
 */
@Composable
fun MediaActionEffect(
    events: Flow<MediaActionEvent>,
    snackbarHostState: SnackbarHostState,
    onConsentResult: (Boolean) -> Unit,
    onActionDone: (MediaActionEvent.Done) -> Unit = {},
    trashFollowUp: TrashFollowUp? = null,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val followUp by rememberUpdatedState(trashFollowUp)
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> onConsentResult(result.resultCode == android.app.Activity.RESULT_OK) }

    LaunchedEffect(events) {
        events.collect { event ->
            when (event) {
                is MediaActionEvent.LaunchConsent ->
                    consentLauncher.launch(IntentSenderRequest.Builder(event.intentSender).build())
                is MediaActionEvent.Done -> {
                    onActionDone(event)
                    val message = actionDoneMessage(resources, event.action, event.affected)
                    val current = followUp
                    val kind = if (current == null) {
                        TrashFollowUpKind.NONE
                    } else {
                        trashFollowUpKind(event.action, canManageMedia(context), current.offerManageMedia)
                    }
                    when (kind) {
                        TrashFollowUpKind.NONE -> snackbarHostState.showSnackbar(message)
                        TrashFollowUpKind.UNDO -> {
                            val result = snackbarHostState.showSnackbar(
                                message = message,
                                actionLabel = resources.getString(R.string.action_undo),
                            )
                            val trash = event.action as MediaAction.Trash
                            if (result == SnackbarResult.ActionPerformed) current?.onUndo?.invoke(trash)
                        }
                        TrashFollowUpKind.OFFER_MANAGE_MEDIA -> {
                            current?.onManageMediaOffered?.invoke()
                            val result = snackbarHostState.showSnackbar(
                                message = resources.getString(R.string.trash_manage_media_hint),
                                actionLabel = resources.getString(R.string.trash_manage_media_allow),
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed) openManageMediaSettings(context)
                        }
                    }
                }
                MediaActionEvent.Cancelled ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.gallery_action_cancelled))
                is MediaActionEvent.Failed -> snackbarHostState.showSnackbar(event.error.displayMessage(resources))
            }
        }
    }
}

/** 미디어 관리 권한(API 31+). 있으면 휴지통 넣기·빼기에 확인 창이 없다 */
private fun canManageMedia(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaStore.canManageMedia(context) else false

private fun openManageMediaSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.fromParts("package", context.packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

package com.jjw.easygallery.core.ui.media

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalResources
import com.jjw.easygallery.R
import com.jjw.easygallery.core.data.media.MediaActionEvent
import com.jjw.easygallery.feature.gallery.actionDoneMessage
import kotlinx.coroutines.flow.Flow

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
) {
    val resources = LocalResources.current
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
                    snackbarHostState.showSnackbar(actionDoneMessage(resources, event.action, event.affected))
                }
                MediaActionEvent.Cancelled ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.gallery_action_cancelled))
                is MediaActionEvent.Failed -> snackbarHostState.showSnackbar(event.message)
            }
        }
    }
}

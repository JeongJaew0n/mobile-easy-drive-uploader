package com.jjw.easygallery.feature.gallery

import android.content.res.Resources
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R

/**
 * "다른 계정 업로드" 에 필요한 것을 한 묶음으로 화면에 넘긴다(`docs/plans/guest-account-upload/spec.md`).
 * 갤러리 화면의 파라미터가 이미 많아 하나씩 늘리지 않는다.
 */
internal data class GuestUploadUi(
    /** 주 계정이 연결돼 있을 때만 보인다 — 공유할 상대가 있어야 "다른" 계정이다 */
    val available: Boolean = false,
    /** 끝났는데 아직 "기기에서 지우라" 를 닫지 않은 계정 */
    val cleanupEmail: String? = null,
    val onStart: () -> Unit = {},
    val onOpenAccountSettings: () -> Unit = {},
    val onDismissCleanup: () -> Unit = {},
)

/**
 * 다 올렸으니 B 를 기기에서 지우라는 배너. 앱은 기기 계정을 지울 수 없어서(spec §2) 계정 목록으로 보낸다.
 * 알림을 꺼 둔 사용자도 이 말을 들어야 해서 알림과 별도로 둔다.
 */
@Composable
internal fun GuestCleanupBanner(
    email: String,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.guest_cleanup_banner, email),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.guest_cleanup_open_settings)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    }
}

/** 다른 계정 업로드 이벤트의 한 줄. 선택 창을 띄우는 것은 스낵바가 아니라 호출 쪽이 한다 */
internal fun guestEventMessage(event: GalleryEvent, resources: Resources): String? = when (event) {
    is GalleryEvent.GuestStarted -> {
        val r = event.result
        when {
            r.added == 0 -> resources.getString(R.string.guest_upload_nothing, r.email)
            r.sharedWithPrimary -> resources.getString(R.string.guest_upload_started_shared, r.email, r.added)
            else -> resources.getString(R.string.guest_upload_started, r.email, r.added)
        }
    }
    GalleryEvent.GuestIsPrimary -> resources.getString(R.string.guest_is_primary)
    else -> null
}

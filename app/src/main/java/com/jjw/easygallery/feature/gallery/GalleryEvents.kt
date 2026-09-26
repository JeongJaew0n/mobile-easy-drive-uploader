package com.jjw.easygallery.feature.gallery

import android.app.PendingIntent
import android.content.res.Resources
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import com.jjw.easygallery.R

/** ViewModel 이벤트 → 스낵바·화면 이동. Route 에서 떼어내 복잡도를 낮춘다(Drive 화면의 DriveBrowserEvents 와 같다) */
internal suspend fun showGalleryEvent(
    event: GalleryEvent,
    snackbarHostState: SnackbarHostState,
    resources: Resources,
    onSettingsClick: () -> Unit,
    onGuestChooser: (PendingIntent) -> Unit,
) {
    when (event) {
        GalleryEvent.SignInRequired -> {
            val result = snackbarHostState.showSnackbar(
                message = resources.getString(R.string.gallery_sign_in_required),
                actionLabel = resources.getString(R.string.action_settings),
            )
            if (result == SnackbarResult.ActionPerformed) onSettingsClick()
        }
        // 하나도 안 들어갔으면 "0개 추가" 라고 말하지 않는다 — 왜 아무 일도 없는지를 알려준다
        is GalleryEvent.Enqueued -> snackbarHostState.showSnackbar(
            when {
                event.added == 0 ->
                    resources.getString(R.string.gallery_upload_all_skipped, event.skipped)
                event.skipped == 0 ->
                    resources.getQuantityString(R.plurals.gallery_upload_enqueued, event.added, event.added)
                else ->
                    resources.getString(R.string.gallery_upload_enqueued_skipped, event.added, event.skipped)
            },
        )
        GalleryEvent.NoUploadedToTrash ->
            snackbarHostState.showSnackbar(resources.getString(R.string.gallery_no_uploaded_to_trash))
        is GalleryEvent.CategoriesAssigned -> snackbarHostState.showSnackbar(
            resources.getQuantityString(R.plurals.category_assigned, event.count, event.count),
        )
        is GalleryEvent.Hidden -> snackbarHostState.showSnackbar(
            resources.getQuantityString(R.plurals.gallery_hidden_done, event.count, event.count),
        )
        is GalleryEvent.Error -> snackbarHostState.showSnackbar(event.message)
        is GalleryEvent.GuestChooser -> onGuestChooser(event.pendingIntent)
        is GalleryEvent.GuestStarted, GalleryEvent.GuestIsPrimary ->
            guestEventMessage(event, resources)?.let { snackbarHostState.showSnackbar(it) }
    }
}

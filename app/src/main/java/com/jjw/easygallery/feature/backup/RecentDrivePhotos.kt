package com.jjw.easygallery.feature.backup

import coil3.ImageLoader
import com.jjw.easygallery.core.data.drive.DriveImages
import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.domain.model.DriveMediaScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject

/**
 * 백업 칸 "Google Drive 사진" 카드의 최근 사진(`docs/plans/drive-photos/spec.md` §3.1).
 * 계정이 없으면 null(카드가 없다). 못 읽으면 빈 띠로 둔다 — 카드를 눌러 들어가면 화면이 오류를 보인다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentDrivePhotos @Inject constructor(
    private val drive: DriveRepository,
    private val prefs: UserPreferencesRepository,
    /** 썸네일을 받는 로더 — 인증이 붙어 있다 */
    @param:DriveImages val imageLoader: ImageLoader,
) {
    fun observe(): Flow<DrivePhotosPreview?> = prefs.preferences
        .map { it.accountEmail to it.driveViewScopeGranted }
        .distinctUntilChanged()
        .flatMapLatest { (email, viewScope) ->
            if (email == null) flowOf(null) else load(viewScope)
        }

    private fun load(viewScope: Boolean): Flow<DrivePhotosPreview> = flow {
        emit(DrivePhotosPreview(isLoading = true))
        val entries = runCatching {
            drive.listMedia(
                scope = DriveMediaScope.AppUploads,
                videosOnly = false,
                viewScopeGranted = viewScope,
                pageSize = STRIP_SIZE,
            ).entries
        }.onFailure { e ->
            if (e is CancellationException) throw e
            Timber.w(e, "recent drive photos unavailable")
        }.getOrDefault(emptyList())
        emit(DrivePhotosPreview(entries = entries, isLoading = false))
    }

    private companion object {
        const val STRIP_SIZE = 8
    }
}

package com.jjw.easygallery.core.domain.usecase

import com.jjw.easygallery.core.data.auth.NotSignedInException
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.UploadQueueRepository
import com.jjw.easygallery.core.data.upload.work.UploadScheduler
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.MediaItem
import javax.inject.Inject

/** 선택 항목을 큐에 넣고 워커를 예약한다. 네트워크 없이도 동작(폴더 해석은 워커가 한다). */
class EnqueueUploadsUseCase @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val queue: UploadQueueRepository,
    private val scheduler: UploadScheduler,
) {
    /** @return 실제로 추가된 개수 (이미 대기 중인 항목은 제외) */
    suspend operator fun invoke(items: List<MediaItem>): Int {
        val p = prefs.current()
        // Google Drive 대상일 때만 로그인이 필요하다. 다른 저장소는 저장된 계정 정보로 올린다
        if (p.uploadAccountId == null && !p.isSignedIn) throw NotSignedInException()
        val folder = p.uploadFolderId?.let { DriveFolder(it, p.uploadFolderName ?: "") }
        // 새 배치를 시작하면 지난 배치의 완료 항목은 정리해 진행률 분모를 현재 배치로 맞춘다
        queue.deleteCompleted()
        val added = queue.enqueue(items, folder, p.uploadAccountId)
        if (added > 0) scheduler.schedule()
        return added
    }
}

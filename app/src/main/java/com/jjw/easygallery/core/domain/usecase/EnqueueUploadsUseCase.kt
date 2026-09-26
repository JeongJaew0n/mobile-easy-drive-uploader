package com.jjw.easygallery.core.domain.usecase

import com.jjw.easygallery.core.data.auth.NotSignedInException
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.UploadLedgerRepository
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
    private val ledger: UploadLedgerRepository,
) {
    /** 설정의 기본 대상(계정 + 폴더)으로. @return 실제로 추가된 개수 (이미 대기 중인 항목은 제외) */
    suspend operator fun invoke(items: List<MediaItem>): Int {
        val p = prefs.current()
        val folder = p.uploadFolderId?.let { DriveFolder(it, p.uploadFolderName ?: "") }
        return enqueue(items, p.uploadAccountId, folder, p.isSignedIn)
    }

    /**
     * 이번 한 번만 [accountId](null = Google Drive) 로. 기본 대상과 같은 계정이면 설정 폴더, 아니면 루트
     * (Drive 는 워커가 앱 폴더를 만든다). `docs/MULTI_CLOUD.md` §5 "다른 저장소로 업로드"
     */
    suspend fun toAccount(items: List<MediaItem>, accountId: String?): Int {
        val p = prefs.current()
        val folder = if (accountId == p.uploadAccountId) {
            p.uploadFolderId?.let { DriveFolder(it, p.uploadFolderName ?: "") }
        } else {
            null
        }
        return enqueue(items, accountId, folder, p.isSignedIn)
    }

    /**
     * 정해진 [folder] 로 [accountId] 에 올린다. "다른 계정 업로드" 가 B 의 폴더를 먼저 찾아 공유한 뒤
     * 그 폴더로 넣을 때 쓴다(`docs/plans/guest-account-upload/spec.md` §3 ⑤). 로그인 확인은 호출 쪽이 이미 했다.
     */
    suspend fun toFolder(items: List<MediaItem>, accountId: String, folder: DriveFolder): Int =
        enqueue(items, accountId, folder, signedIn = true)

    private suspend fun enqueue(
        items: List<MediaItem>,
        accountId: String?,
        folder: DriveFolder?,
        signedIn: Boolean,
    ): Int {
        // Google Drive 대상일 때만 로그인이 필요하다. 다른 저장소는 저장된 계정 정보로 올린다
        if (accountId == null && !signedIn) throw NotSignedInException()
        // 새 배치를 시작하면 지난 배치의 완료 항목은 정리해 진행률 분모를 현재 배치로 맞춘다
        queue.deleteCompleted()
        // 이 계정에 이미 올린 것은 뺀다. 같은 이름으로 한 벌 더 생기는 것을 사용자는 원하지 않는다
        val uploaded = ledger.uploadedAmong(items.map { it.id }, accountId)
        val added = queue.enqueue(items, folder, accountId, uploaded)
        if (added > 0) scheduler.schedule()
        return added
    }
}

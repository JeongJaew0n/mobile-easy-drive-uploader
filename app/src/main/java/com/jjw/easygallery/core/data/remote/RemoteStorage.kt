package com.jjw.easygallery.core.data.remote

import com.jjw.easygallery.core.data.upload.SessionStatus
import com.jjw.easygallery.core.data.upload.UploadEvent
import com.jjw.easygallery.core.data.upload.UploadSource
import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.DrivePage
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountInfo
import kotlinx.coroutines.flow.Flow

// Drive 에서 시작한 모델을 그대로 쓴다. 의미는 제공자마다 다르다(Drive: 파일 ID, S3: 오브젝트 키, WebDAV: 경로).
typealias RemoteEntry = DriveEntry
typealias RemoteFolder = DriveFolder
typealias RemotePage = DrivePage

/**
 * 원격 저장소 제공자(`docs/MULTI_CLOUD.md` §2). Google Drive·S3 호환·WebDAV 가 같은 표면을 갖고,
 * 탐색 화면과 업로드 워커는 `accountId` 로 고른 구현만 다룬다.
 */
interface RemoteStorage {
    val account: RemoteAccount
    val capabilities: Set<Capability>

    suspend fun about(): RemoteAccountInfo

    /** [parentId] 바로 아래. 폴더 먼저·이름순. 루트는 [rootId] */
    suspend fun listChildren(parentId: String, pageToken: String? = null, foldersOnly: Boolean = false): RemotePage

    suspend fun createFolder(name: String, parentId: String): RemoteFolder

    suspend fun rename(entryId: String, name: String): RemoteEntry

    suspend fun move(entryId: String, fromParentId: String, toParentId: String): RemoteEntry

    /** [Capability.TRASH] 가 있으면 휴지통으로, 없으면 영구 삭제(UI 가 먼저 확인한다) */
    suspend fun delete(entryId: String)

    /** [Capability.TRASH] 가 있을 때만 */
    suspend fun restore(entryId: String)

    fun uploader(): RemoteUploader

    val rootId: String get() = "root"
}

/**
 * 업로드 3단계: 세션 시작 → (중단 시) 상태 조회 → 이어 올리기. `UploadWorker` 가 제공자를 몰라도 되게 하는 표면.
 * 재개를 못 하는 제공자는 [queryStatus] 에서 항상 [SessionStatus.Expired] 를 돌려 처음부터 다시 올리게 한다.
 */
interface RemoteUploader {
    suspend fun resolveLength(source: UploadSource): Long

    /** @return 세션 식별자(Drive: 세션 URI, S3: uploadId, WebDAV: 대상 경로) */
    suspend fun startSession(source: UploadSource, folderId: String, length: Long): String

    suspend fun queryStatus(sessionUri: String, length: Long): SessionStatus

    fun upload(source: UploadSource, sessionUri: String, offset: Long, length: Long): Flow<UploadEvent>

    /** 영구 실패로 버리는 세션의 서버 쪽 잔재 정리(S3 미완료 멀티파트 등). 기본은 할 일 없음 */
    suspend fun abort(sessionUri: String) = Unit
}

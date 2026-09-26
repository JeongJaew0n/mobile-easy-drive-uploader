package com.jjw.easygallery.core.data.upload

import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.db.UNCLAIMED_DRIVE_DESTINATION
import com.jjw.easygallery.core.data.upload.db.UploadedMediaDao
import com.jjw.easygallery.core.data.upload.db.UploadedMediaEntity
import com.jjw.easygallery.core.data.upload.db.destinationFor
import com.jjw.easygallery.core.domain.model.RemoteAccount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 올라간 항목의 영구 원장.
 *
 * 호출하는 쪽은 지금처럼 `accountId`(null = Google Drive)로 묻는다. **Drive 기록은 지금 연결된
 * Google 계정 기준**이라는 규칙은 여기에만 있다 — 그 계정의 이메일을 읽어 `destination` 으로
 * 바꾼다. A 로 올린 뒤 B 로 바꾸면 B 에는 아무것도 올라가지 않은 것으로 보인다
 * (`docs/plans/ledger-per-account/spec.md`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class UploadLedgerRepository @Inject constructor(
    private val dao: UploadedMediaDao,
    private val prefs: UserPreferencesRepository,
) {
    /** 옛 기록을 이미 넘겨준 계정. 프로세스마다 한 번만 UPDATE 를 보내려고 기억한다 */
    @Volatile
    private var claimedFor: String? = null

    suspend fun record(mediaId: Long, driveFileId: String, folderId: String?, accountId: String? = null) {
        val email = prefs.current().accountEmail
        email?.let { claimLegacy(it) }
        // Drive 인데 연결 계정을 모르면(거의 없다) 주인 미정으로 적어 두고 다음에 보이는 계정이 가져간다
        val destination = destinationOf(accountId, email) ?: UNCLAIMED_DRIVE_DESTINATION
        dao.upsert(
            UploadedMediaEntity(
                mediaId = mediaId,
                destination = destination,
                driveFileId = driveFileId,
                folderId = folderId,
                uploadedAt = System.currentTimeMillis(),
                accountId = accountId,
            ),
        )
    }

    /** [mediaIds] 중 **어디든** 이미 올라간 것. SQLite 변수 한도(999) 아래로 잘라 조회한다. */
    suspend fun uploadedAmong(mediaIds: Collection<Long>): Set<Long> =
        mediaIds.chunked(QUERY_CHUNK).flatMapTo(HashSet()) { dao.uploadedAmong(it) }

    /** 모든 곳 합집합(중복 정리처럼 "어디든 백업됨" 이 기준일 때) */
    fun observeUploadedIds(): Flow<Set<Long>> = dao.observeUploadedIds().map { it.toSet() }

    /**
     * 특정 곳 기준(갤러리 배지·자동 백업 — `docs/MULTI_CLOUD.md` §5). null = Google Drive.
     * Drive 면 연결 계정이 바뀔 때 **다시 흐른다.** 연결돼 있지 않으면 빈 집합이다.
     */
    fun observeUploadedIds(accountId: String?): Flow<Set<Long>> =
        destinationFlow(accountId).flatMapLatest { destination ->
            if (destination == null) flowOf(emptySet()) else dao.observeUploadedIdsAt(destination).map { it.toSet() }
        }

    /** 그곳의 원격 파일 ID 집합(`DRIVE_FILE_CRUD.md` §6 "이 기기에서 올림") */
    fun observeRemoteIds(accountId: String?): Flow<Set<String>> =
        destinationFlow(accountId).flatMapLatest { destination ->
            if (destination == null) flowOf(emptySet()) else dao.observeRemoteIdsAt(destination).map { it.toSet() }
        }

    suspend fun uploadedAmong(mediaIds: Collection<Long>, accountId: String?): Set<Long> {
        val email = prefs.current().accountEmail
        email?.let { claimLegacy(it) }
        val destination = destinationOf(accountId, email) ?: return emptySet()
        return mediaIds.chunked(QUERY_CHUNK).flatMapTo(HashSet()) { dao.uploadedAmongAt(it, destination) }
    }

    suspend fun count(): Int = dao.count()

    suspend fun clear() = dao.clear()

    private fun destinationFlow(accountId: String?): Flow<String?> =
        if (isDrive(accountId)) {
            prefs.preferences
                .map { it.accountEmail }
                .distinctUntilChanged()
                .onEach { email -> email?.let { claimLegacy(it) } }
                .map { email -> destinationOf(accountId, email) }
        } else {
            flowOf(destinationOf(accountId, null))
        }

    /**
     * 스키마 11 이전의 Drive 기록에는 누가 올렸는지가 없다. **처음 보이는 연결 계정**에게 준다.
     * 앱은 지금까지 계정 하나만 써 왔으므로 대개 맞다(spec §2.3). 옮길 것이 없으면 0행이다.
     */
    private suspend fun claimLegacy(email: String) {
        if (claimedFor == email) return
        dao.claimUnclaimed(destination = "drive:$email")
        claimedFor = email
        Timber.i("ledger: legacy drive rows claimed")
    }

    private fun destinationOf(accountId: String?, email: String?): String? =
        destinationFor(accountId, email, RemoteAccount.GOOGLE_DRIVE_ID)

    private fun isDrive(accountId: String?): Boolean =
        accountId == null || accountId == RemoteAccount.GOOGLE_DRIVE_ID

    private companion object {
        const val QUERY_CHUNK = 900
    }
}

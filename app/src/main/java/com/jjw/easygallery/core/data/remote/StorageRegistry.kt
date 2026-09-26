package com.jjw.easygallery.core.data.remote

import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * `accountId` → [RemoteStorage]. null 또는 [RemoteAccount.GOOGLE_DRIVE_ID] 는 Google Drive.
 * 계정별 구현은 비밀을 읽어 만들고 캐시한다(계정을 지우면 캐시도 비운다).
 */
@Singleton
class StorageRegistry @Inject constructor(
    private val drive: Provider<GoogleDriveStorage>,
    private val accounts: RemoteAccountRepository,
    private val factories: Map<RemoteAccountKind, @JvmSuppressWildcards RemoteStorageFactory>,
    private val guests: GuestDriveFactory,
) {
    // UI(메인)와 워커 스레드가 함께 만진다 — 잠금 없이 쓰면 인스턴스가 중복 생성되거나 맵이 깨진다
    private val cache = ConcurrentHashMap<String, RemoteStorage>()
    private val guestCache = ConcurrentHashMap<String, GuestDrive>()
    private val buildLock = Mutex()

    suspend fun storage(accountId: String?): RemoteStorage {
        if (accountId == null || accountId == RemoteAccount.GOOGLE_DRIVE_ID) return drive.get()
        val guestEmail = RemoteAccount.guestEmailOf(accountId)
        return if (guestEmail != null) guestDrive(guestEmail).storage else registered(accountId)
    }

    private suspend fun registered(accountId: String): RemoteStorage = cache[accountId]
        ?: buildLock.withLock {
            // 잠금을 기다리는 동안 다른 쪽이 만들었을 수 있다
            cache[accountId] ?: build(accountId).also { cache[accountId] = it }
        }

    private suspend fun build(accountId: String): RemoteStorage {
        val account = accounts.get(accountId) ?: throw UnknownAccountException(accountId)
        val factory = requireNotNull(factories[account.kind]) { "지원하지 않는 저장소 종류: ${account.kind}" }
        val secret = requireNotNull(accounts.secretOf(account)) { "저장소 비밀 정보가 없습니다: $accountId" }
        return factory.create(account, secret)
    }

    /**
     * "다른 계정 업로드" 의 B 쪽 Drive(`docs/plans/guest-account-upload/spec.md` §4.2). 계정 행 없이
     * 이메일만으로 만든다 — 토큰은 그때그때 Play 서비스에서 받으니 비밀을 저장할 것이 없다.
     */
    fun guestDrive(email: String): GuestDrive =
        guestCache.getOrPut(RemoteAccount.guestDriveId(email)) { guests.create(email) }

    fun evict(accountId: String) {
        cache.remove(accountId)
        guestCache.remove(accountId)
    }
}

/** 종류별 구현 생성. Hilt 멀티바인딩(`@IntoMap`)으로 등록 */
fun interface RemoteStorageFactory {
    fun create(account: RemoteAccount, secret: String): RemoteStorage
}

class UnknownAccountException(id: String) : IllegalStateException("저장소 계정을 찾을 수 없습니다: $id")

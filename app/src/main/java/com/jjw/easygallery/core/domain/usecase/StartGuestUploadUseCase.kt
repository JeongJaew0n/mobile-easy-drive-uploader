package com.jjw.easygallery.core.domain.usecase

import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.GuestDriveFactory
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.ViewFolder
import timber.log.Timber
import javax.inject.Inject

/** 고른 계정이 이미 연결된 주 계정이다. 그건 "다른 계정" 업로드가 아니다 */
class GuestIsPrimaryException : IllegalArgumentException("이미 연결된 계정입니다. 다른 계정을 고르세요")

/** [StartGuestUploadUseCase] 결과 — 화면이 무엇을 말할지 정한다 */
data class GuestUploadStarted(
    val email: String,
    val added: Int,
    val skipped: Int,
    /** A 에게 공유했나. A 가 연결돼 있지 않으면 공유할 상대가 없다 */
    val sharedWithPrimary: Boolean,
    /** A 의 "볼 수 있는 폴더" 에 더했나. A 가 읽기 권한을 옵트인했을 때만 */
    val addedToViewFolders: Boolean,
)

/**
 * "다른 계정 업로드" 를 시작한다(`docs/plans/guest-account-upload/spec.md` §3 ②~⑤).
 *
 * 순서가 중요하다 — **공유를 올리기 전에** 한다. 폴더에 한 번 주면 안에 들어오는 파일이 모두 물려받으므로,
 * 올리는 도중에도 A 쪽에서 하나씩 보인다. 올린 뒤에 공유하면 끝날 때까지 A 는 아무것도 못 본다.
 *
 * 주 계정 A 의 연결 정보는 읽기만 한다. 바꾸는 것은 A 의 보기 폴더 목록에 한 줄 더하는 것뿐이다.
 */
class StartGuestUploadUseCase @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val guests: GuestDriveFactory,
    private val registry: StorageRegistry,
    private val enqueueUploads: EnqueueUploadsUseCase,
) {
    suspend operator fun invoke(items: List<MediaItem>, accessToken: String): GuestUploadStarted {
        val email = guests.emailOf(accessToken)
        val primary = prefs.current()
        if (email.equals(primary.accountEmail, ignoreCase = true)) throw GuestIsPrimaryException()

        val guest = registry.guestDrive(email)
        val folder = guest.drive.ensureAppRootFolder()
        val primaryEmail = primary.accountEmail
        if (primaryEmail != null) guest.drive.shareForReading(folder.id, primaryEmail)

        // A 의 루트에 이미 A 의 "Easy Gallery" 가 있다. 이름을 바꾸지 않고 소유자를 따로 적어
        // 화면이 부제로 가른다 — 이름에 붙이면 목록에서 잘린다(spec §6)
        val viewAdded = primaryEmail != null && primary.driveViewScopeGranted
        if (viewAdded) prefs.addViewFolder(ViewFolder(folder.id, folder.name, ownerEmail = email))

        val added = enqueueUploads.toFolder(items, RemoteAccount.guestDriveId(email), folder)
        Timber.i("guest upload started: added=%d shared=%s view=%s", added, primaryEmail != null, viewAdded)
        return GuestUploadStarted(
            email = email,
            added = added,
            skipped = items.size - added,
            sharedWithPrimary = primaryEmail != null,
            addedToViewFolders = viewAdded,
        )
    }
}

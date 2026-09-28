package com.jjw.easygallery.core.domain.usecase

import com.jjw.easygallery.R
import com.jjw.easygallery.core.common.text.LocalizedError
import com.jjw.easygallery.core.common.text.UiText
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.GuestDriveFactory
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.ViewFolder
import timber.log.Timber
import javax.inject.Inject

/** 고른 계정이 이미 연결된 주 계정이다. 그건 "다른 계정" 업로드가 아니다 */
class GuestIsPrimaryException :
    IllegalArgumentException("picked the primary account"), LocalizedError {
    override val uiText = UiText(R.string.error_guest_is_primary)
}

/** 누구로 올릴지 정해졌고, 이제 폴더를 고를 차례. [defaultFolder] 는 B 의 "Easy Gallery" */
data class GuestSession(val email: String, val defaultFolder: DriveFolder)

/** [StartGuestUploadUseCase.start] 결과 — 화면이 무엇을 말할지 정한다 */
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
 * "다른 계정 업로드"(`docs/plans/guest-account-upload/spec.md`). 세 단계다.
 *
 * 1. [prepare] — 누구인지 확인하고(주 계정이면 거절) B 의 "Easy Gallery" 를 보장한다
 * 2. [listFolders]·[createFolder] — 올릴 폴더를 고른다(§7). B 에게는 `drive.file` 만 있어 **앱이 만든 폴더만**
 *    보이고, 그게 곧 고를 수 있는 범위다 — 기존 폴더는 A 에게 공유할 수 없기 때문이다(§7.1)
 * 3. [start] — 그 폴더를 A 에게 공유하고, A 의 보기 폴더에 더하고, 큐에 넣는다
 *
 * 주 계정 A 의 연결 정보는 읽기만 한다. 바꾸는 것은 A 의 보기 폴더 목록에 한 줄 더하는 것뿐이다.
 */
class StartGuestUploadUseCase @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val guests: GuestDriveFactory,
    private val registry: StorageRegistry,
    private val enqueueUploads: EnqueueUploadsUseCase,
) {
    suspend fun prepare(accessToken: String): GuestSession {
        val email = guests.emailOf(accessToken)
        if (email.equals(prefs.current().accountEmail, ignoreCase = true)) throw GuestIsPrimaryException()
        val folder = registry.guestDrive(email).drive.ensureAppRootFolder()
        return GuestSession(email, folder)
    }

    /** B 의 [parentId] 아래 폴더들. 앱이 만든 것만 나온다(`drive.file`) */
    suspend fun listFolders(email: String, parentId: String): List<DriveFolder> {
        val drive = registry.guestDrive(email).drive
        val result = ArrayList<DriveFolder>()
        var token: String? = null
        do {
            val page = drive.listChildren(parentId, token, foldersOnly = true)
            result += page.entries.map(DriveEntry::toFolder)
            token = page.nextPageToken
        } while (token != null)
        return result
    }

    /** B 의 [parentId] 에 새 폴더. 앱이 만든 것이라 A 에게 공유할 수 있다 */
    suspend fun createFolder(email: String, name: String, parentId: String): DriveFolder =
        registry.guestDrive(email).drive.createFolder(name, parentId)

    /**
     * 순서가 중요하다 — **공유를 올리기 전에** 한다. 폴더에 한 번 주면 안에 들어오는 파일이 모두 물려받으므로,
     * 올리는 도중에도 A 쪽에서 하나씩 보인다. 올린 뒤에 공유하면 끝날 때까지 A 는 아무것도 못 본다.
     */
    suspend fun start(items: List<MediaItem>, email: String, folder: DriveFolder): GuestUploadStarted {
        val primary = prefs.current()
        val primaryEmail = primary.accountEmail
        val guest = registry.guestDrive(email)
        if (primaryEmail != null) guest.drive.shareForReading(folder.id, primaryEmail)

        // 이름을 바꾸지 않고 소유자를 따로 적는다 — 화면이 부제로 A 의 폴더와 가른다(spec §6)
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

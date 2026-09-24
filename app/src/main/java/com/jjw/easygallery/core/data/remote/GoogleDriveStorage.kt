package com.jjw.easygallery.core.data.remote

import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.upload.DriveUploader
import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountInfo
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import timber.log.Timber
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 기존 Drive 저장소·업로더를 [RemoteStorage] 표면으로 감싼 어댑터. Drive 코드는 건드리지 않는다 */
@Singleton
class GoogleDriveStorage @Inject constructor(
    private val drive: DriveRepository,
    private val driveUploader: DriveUploader,
    private val prefs: UserPreferencesRepository,
) : RemoteStorage {

    override val account: RemoteAccount = RemoteAccount(
        id = RemoteAccount.GOOGLE_DRIVE_ID,
        kind = RemoteAccountKind.GOOGLE_DRIVE,
        displayName = "Google Drive",
    )

    override val capabilities: Set<Capability> = setOf(
        Capability.SEARCH,
        Capability.DOWNLOAD,
        Capability.TRASH,
        Capability.RENAME,
        Capability.MOVE,
        Capability.FOLDER_MUTATION,
        Capability.RESUMABLE_UPLOAD,
        Capability.QUOTA,
        Capability.WEB_LINK,
    )

    override suspend fun about(): RemoteAccountInfo {
        val a = drive.getAccount()
        return RemoteAccountInfo(a.displayName ?: a.email, a.email, a.storageUsedBytes, a.storageLimitBytes)
    }

    override suspend fun listChildren(parentId: String, pageToken: String?, foldersOnly: Boolean): RemotePage =
        if (parentId == rootId) rootEntries() else drive.listChildren(parentId, pageToken, foldersOnly)

    /**
     * `drive.file` 에서 내 드라이브 루트는 빈 목록이다(SS-09). 그래서 루트 자리에
     * **앱이 올릴 수 있는 폴더들** 을 대신 보여준다 — 지정 폴더와 기본 폴더.
     * `docs/DRIVE_FILE_SCOPE.md` §2
     */
    private suspend fun rootEntries(): RemotePage {
        val picked = prefs.current().pickedFolders.map { folderEntry(it.id, it.alias) }
        val appRoot = runCatching { drive.ensureAppRootFolder() }
            .onFailure { Timber.w(it, "app root folder unavailable") }
            .getOrNull()
            ?.let { folderEntry(it.id, it.name) }
        return RemotePage(picked + listOfNotNull(appRoot), nextPageToken = null)
    }

    private fun folderEntry(id: String, name: String) = DriveEntry(
        id = id,
        name = name,
        mimeType = DriveEntry.FOLDER_MIME_TYPE,
        sizeBytes = null,
        modifiedTimeMillis = null,
        webViewLink = null,
    )

    override suspend fun createFolder(name: String, parentId: String): RemoteFolder = drive.createFolder(name, parentId)

    override suspend fun search(query: String, pageToken: String?): RemotePage = drive.search(query, pageToken)

    override suspend fun openDownload(entryId: String): InputStream = drive.download(entryId)

    override suspend fun rename(entryId: String, name: String): RemoteEntry = drive.rename(entryId, name)

    override suspend fun move(entryId: String, fromParentId: String, toParentId: String): RemoteEntry =
        drive.move(entryId, fromParentId, toParentId)

    override suspend fun delete(entryId: String) = drive.setTrashed(entryId, trashed = true)

    override suspend fun restore(entryId: String) = drive.setTrashed(entryId, trashed = false)

    override fun uploader(): RemoteUploader = driveUploader
}

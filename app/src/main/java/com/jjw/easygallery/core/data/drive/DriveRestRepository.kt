package com.jjw.easygallery.core.data.drive

import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.domain.model.DriveAccount
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.DrivePage
import retrofit2.HttpException
import timber.log.Timber
import java.io.InputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriveRestRepository @Inject constructor(
    private val api: DriveApi,
) : DriveRepository {

    override suspend fun getAccount(): DriveAccount {
        val about = api.about()
        return DriveAccount(
            email = about.user.emailAddress,
            displayName = about.user.displayName,
            storageUsedBytes = about.storageQuota?.usage?.toLongOrNull(),
            storageLimitBytes = about.storageQuota?.limit?.toLongOrNull(),
        )
    }

    override suspend fun listChildren(parentId: String, pageToken: String?, foldersOnly: Boolean): DrivePage {
        val folderClause = if (foldersOnly) " and mimeType = '${DriveApi.FOLDER_MIME_TYPE}'" else ""
        val page = api.listFiles(
            query = "'${escape(parentId)}' in parents and trashed = false$folderClause",
            pageToken = pageToken,
        )
        return DrivePage(entries = page.files.map { it.toEntry() }, nextPageToken = page.nextPageToken)
    }

    override suspend fun listSharedFolders(pageToken: String?): DrivePage {
        val page = api.listFiles(
            query = "sharedWithMe = true and mimeType = '${DriveApi.FOLDER_MIME_TYPE}' and trashed = false",
            pageToken = pageToken,
        )
        return DrivePage(entries = page.files.map { it.toEntry() }, nextPageToken = page.nextPageToken)
    }

    /**
     * Retrofit 은 비2xx 를 [HttpException](RuntimeException)으로 던진다. 그대로 두면 워커의
     * `IOException` 분기를 비켜가 5xx·429 가 재시도 없이 영구 실패한다 → 상태 코드를 살려 감싼다.
     */
    override suspend fun download(fileId: String): InputStream = try {
        api.download(fileId).byteStream()
    } catch (e: HttpException) {
        throw RemoteStorageException("다운로드 실패 (${e.code()})", httpCode = e.code(), cause = e)
    }

    override suspend fun search(query: String, pageToken: String?): DrivePage {
        val page = api.listFiles(
            query = "name contains '${escape(query.trim())}' and trashed = false",
            pageToken = pageToken,
        )
        return DrivePage(entries = page.files.map { it.toEntry() }, nextPageToken = page.nextPageToken)
    }

    override suspend fun rename(fileId: String, name: String): DriveEntry =
        api.updateFile(fileId, DriveFilePatch(name = name)).toEntry()

    override suspend fun move(fileId: String, fromParentId: String, toParentId: String): DriveEntry =
        api.updateFile(fileId, DriveFilePatch(), addParents = toParentId, removeParents = fromParentId).toEntry()

    override suspend fun setTrashed(fileId: String, trashed: Boolean) {
        api.updateFile(fileId, DriveFilePatch(trashed = trashed))
    }

    override suspend fun createFolder(name: String, parentId: String): DriveFolder =
        api.createFile(
            DriveFileMetadata(name = name, mimeType = DriveApi.FOLDER_MIME_TYPE, parents = listOf(parentId)),
        ).toFolder()

    override suspend fun ownerOf(fileId: String): String? =
        runCatching { api.getFile(fileId).ownerEmail() }
            .onFailure { Timber.w(it, "owner lookup failed") }
            .getOrNull()

    override suspend fun shareForReading(fileId: String, email: String) {
        api.createPermission(fileId, DrivePermissionRequest(type = "user", role = "reader", emailAddress = email))
    }

    /**
     * 표식이 붙은 앱 폴더를 찾고 없으면 만든다.
     *
     * **`'me' in owners` 가 빠지면 안 된다.** 읽기 권한(`drive.readonly`)을 옵트인하면 남이 공유한 파일도
     * 보이는데, 그 사람이 이 앱을 쓰고 있으면 **그 사람의 앱 폴더에도 같은 표식이 있다.** 2026-09-27 기기에서
     * 다른 계정 업로드로 B 의 "Easy Gallery" 가 A 에게 공유된 뒤, A 의 앱 폴더 자리에 B 의 폴더가 잡혔다 —
     * 화면에는 B 의 폴더가 "내 계정" 으로, A 의 진짜 폴더는 보기 전용으로 나왔다. 업로드 폴더가 지정돼 있지
     * 않았다면 A 의 업로드가 B 의 폴더로 향했을 것이다.
     */
    override suspend fun ensureAppRootFolder(): DriveFolder {
        val query = "mimeType = '${DriveApi.FOLDER_MIME_TYPE}' and trashed = false and 'me' in owners " +
            "and appProperties has { key = '$APP_ROOT_PROPERTY' and value = 'true' }"
        listAll(query).firstOrNull()?.let { return it.toFolder() }
        return api.createFile(
            DriveFileMetadata(
                name = APP_ROOT_FOLDER_NAME,
                mimeType = DriveApi.FOLDER_MIME_TYPE,
                parents = listOf("root"),
                appProperties = mapOf(APP_ROOT_PROPERTY to "true"),
            ),
        ).toFolder()
    }

    private suspend fun listAll(query: String): List<DriveFileDto> {
        val result = ArrayList<DriveFileDto>()
        var pageToken: String? = null
        do {
            val page = api.listFiles(query = query, pageToken = pageToken)
            result += page.files
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    private fun DriveFileDto.toFolder() = DriveFolder(id = id, name = name, ownerEmail = ownerEmail())

    private fun DriveFileDto.ownerEmail(): String? = owners?.firstNotNullOfOrNull { it.emailAddress }

    private fun DriveFileDto.toEntry() = DriveEntry(
        id = id,
        name = name,
        mimeType = mimeType ?: "",
        sizeBytes = size?.toLongOrNull(),
        modifiedTimeMillis = modifiedTime?.let { parseRfc3339(it) },
        webViewLink = webViewLink,
        ownerEmail = ownerEmail(),
    )

    private fun parseRfc3339(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    // Drive 쿼리 문자열 안의 작은따옴표/백슬래시 이스케이프
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    override suspend fun parentOf(fileId: String): String? =
        runCatching { api.getFile(fileId).parents?.firstOrNull() }
            .onFailure { Timber.w(it, "parentOf failed: %s", fileId) }
            .getOrNull()

    companion object {
        const val APP_ROOT_FOLDER_NAME = "Easy Gallery"
        const val APP_ROOT_PROPERTY = "easyGalleryRoot"
    }
}

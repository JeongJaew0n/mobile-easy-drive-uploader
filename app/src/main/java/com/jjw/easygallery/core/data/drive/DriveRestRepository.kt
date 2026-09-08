package com.jjw.easygallery.core.data.drive

import com.jjw.easygallery.core.domain.model.DriveAccount
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.DrivePage
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

    override suspend fun ensureAppRootFolder(): DriveFolder {
        val query = "mimeType = '${DriveApi.FOLDER_MIME_TYPE}' and trashed = false " +
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

    private fun DriveFileDto.toFolder() = DriveFolder(id = id, name = name)

    private fun DriveFileDto.toEntry() = DriveEntry(
        id = id,
        name = name,
        mimeType = mimeType ?: "",
        sizeBytes = size?.toLongOrNull(),
        modifiedTimeMillis = modifiedTime?.let { parseRfc3339(it) },
        webViewLink = webViewLink,
    )

    private fun parseRfc3339(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }

    // Drive 쿼리 문자열 안의 작은따옴표/백슬래시 이스케이프
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        const val APP_ROOT_FOLDER_NAME = "Easy Gallery"
        const val APP_ROOT_PROPERTY = "easyGalleryRoot"
    }
}

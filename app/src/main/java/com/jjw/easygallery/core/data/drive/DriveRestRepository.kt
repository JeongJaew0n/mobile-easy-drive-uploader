package com.jjw.easygallery.core.data.drive

import com.jjw.easygallery.core.domain.model.DriveAccount
import com.jjw.easygallery.core.domain.model.DriveFolder
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

    override suspend fun listFolders(parentId: String): List<DriveFolder> {
        val query = "mimeType = '${DriveApi.FOLDER_MIME_TYPE}' and '${escape(parentId)}' in parents and trashed = false"
        return listAll(query).map { it.toFolder() }
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

    // Drive 쿼리 문자열 안의 작은따옴표/백슬래시 이스케이프
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        const val APP_ROOT_FOLDER_NAME = "Easy Gallery"
        const val APP_ROOT_PROPERTY = "easyGalleryRoot"
    }
}

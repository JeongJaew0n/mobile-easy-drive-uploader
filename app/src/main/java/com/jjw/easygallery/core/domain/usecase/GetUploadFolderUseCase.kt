package com.jjw.easygallery.core.domain.usecase

import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.domain.model.DriveFolder
import javax.inject.Inject

/** 사용자가 고른 업로드 폴더. 없으면 앱 루트 폴더를 만들어 기본값으로 저장한다. */
class GetUploadFolderUseCase @Inject constructor(
    private val prefs: UserPreferencesRepository,
    private val drive: DriveRepository,
) {
    suspend operator fun invoke(): DriveFolder {
        val current = prefs.current()
        val id = current.uploadFolderId
        val name = current.uploadFolderName
        if (id != null && name != null) return DriveFolder(id, name)
        return drive.ensureAppRootFolder().also { prefs.setUploadFolder(it.id, it.name) }
    }
}

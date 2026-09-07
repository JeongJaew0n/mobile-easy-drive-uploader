package com.jjw.easygallery.core.data.drive

import com.jjw.easygallery.core.domain.model.DriveAccount
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.DrivePage

interface DriveRepository {
    suspend fun getAccount(): DriveAccount

    /** [parentId] 바로 아래의 폴더·파일 한 페이지. 폴더가 먼저, 이름순. `"root"` 가 내 드라이브. */
    suspend fun listChildren(parentId: String, pageToken: String? = null): DrivePage

    suspend fun createFolder(name: String, parentId: String): DriveFolder

    /** 앱 전용 루트 폴더("Easy Gallery"). 없으면 만든다. */
    suspend fun ensureAppRootFolder(): DriveFolder
}

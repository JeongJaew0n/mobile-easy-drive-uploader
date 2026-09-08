package com.jjw.easygallery.core.data.drive

import com.jjw.easygallery.core.domain.model.DriveAccount
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.DrivePage

interface DriveRepository {
    suspend fun getAccount(): DriveAccount

    /**
     * [parentId] 바로 아래의 폴더·파일 한 페이지. 폴더가 먼저, 이름순. `"root"` 가 내 드라이브.
     * [foldersOnly] 면 폴더만(이동 대상 선택기).
     */
    suspend fun listChildren(parentId: String, pageToken: String? = null, foldersOnly: Boolean = false): DrivePage

    suspend fun createFolder(name: String, parentId: String): DriveFolder

    /** 이름 부분 일치(대소문자 무시는 Drive 가 처리). 휴지통 제외, 폴더 먼저 */
    suspend fun search(query: String, pageToken: String? = null): DrivePage

    /** 이름 변경. 확장자는 사용자가 쓴 그대로 */
    suspend fun rename(fileId: String, name: String): DriveEntry

    /** 다른 폴더로 이동(부모 교체) */
    suspend fun move(fileId: String, fromParentId: String, toParentId: String): DriveEntry

    /** Drive 휴지통으로 / 복원. 완전 삭제는 제공하지 않는다(`docs/DRIVE_FILE_CRUD.md` §1) */
    suspend fun setTrashed(fileId: String, trashed: Boolean)

    /** 앱 전용 루트 폴더("Easy Gallery"). 없으면 만든다. */
    suspend fun ensureAppRootFolder(): DriveFolder
}

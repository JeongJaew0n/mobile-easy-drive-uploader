package com.jjw.easygallery.core.domain.model

import kotlinx.serialization.Serializable

/**
 * 사용자가 피커로 지정한 Drive 폴더.
 *
 * `drive.file` 에서는 이 폴더를 `files.get` 으로 읽을 수 없어 **이름을 알 수 없다**.
 * 그래서 [alias] 는 사용자가 직접 붙인 것이다. 자세한 것은 `docs/DRIVE_FILE_SCOPE.md`.
 */
@Serializable
data class PickedFolder(val id: String, val alias: String)

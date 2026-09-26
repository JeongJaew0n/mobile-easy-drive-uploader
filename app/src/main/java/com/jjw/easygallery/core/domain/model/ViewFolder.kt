package com.jjw.easygallery.core.domain.model

import kotlinx.serialization.Serializable

/**
 * 사용자가 **보기 전용**으로 추가한 Drive 폴더.
 *
 * [PickedFolder](업로드 대상)와 성격이 반대다 — 이쪽은 올릴 수 없는 대신 **안을 볼 수 있고**,
 * 읽기 권한이 있으니 이름도 Drive 에서 그대로 가져온다(별칭이 필요 없다).
 * `drive.readonly` 를 옵트인한 사용자만 가질 수 있다. `docs/DRIVE_FILE_SCOPE.md` §10.
 */
@Serializable
data class ViewFolder(val id: String, val name: String)

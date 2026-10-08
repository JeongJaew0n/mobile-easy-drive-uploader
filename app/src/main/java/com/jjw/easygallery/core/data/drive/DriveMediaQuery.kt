package com.jjw.easygallery.core.data.drive

import com.jjw.easygallery.core.domain.model.DriveMediaScope

/**
 * Drive 사진 화면의 `files.list` 조건(`docs/plans/drive-photos/spec.md` §4). 순수 함수라 테스트로 조건을 고정한다.
 *
 * "앱이 올린 것" 은 권한에 따라 조건이 다르다. `drive.file` 만 있으면 Drive 가 이미 앱 파일로 좁혀 주므로 조건이 필요 없다 —
 * 표식이 생기기 전에 올린 옛 파일도 보여야 한다. 읽기 권한(`drive.readonly`)을 옵트인했으면 Drive 전체가 보이므로
 * 업로드 표식으로 좁힌다.
 */
object DriveMediaQuery {
    /** 이 앱이 올린 파일에 붙이는 고정 표식(`DriveUploader.uploadProperties`) */
    const val APP_MARKER_KEY = "easyGallery"
    const val APP_MARKER_VALUE = "1"

    private const val MEDIA = "(mimeType contains 'image/' or mimeType contains 'video/') and trashed = false"
    private const val VIDEOS = "mimeType contains 'video/' and trashed = false"

    fun of(scope: DriveMediaScope, videosOnly: Boolean, viewScopeGranted: Boolean): String {
        val media = if (videosOnly) VIDEOS else MEDIA
        return when (scope) {
            DriveMediaScope.AppUploads -> buildString {
                append(media)
                append(" and 'me' in owners")
                if (viewScopeGranted) {
                    append(" and appProperties has { key = '$APP_MARKER_KEY' and value = '$APP_MARKER_VALUE' }")
                }
            }
            is DriveMediaScope.Folder -> "'${escape(scope.id)}' in parents and $media"
            DriveMediaScope.WholeDrive -> media
        }
    }

    /** Drive 쿼리 문자열 안의 작은따옴표/백슬래시 */
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")

    /** 사진 화면이 받는 필드 — 목록 화면의 것에 올린·찍은 시각과 영상 길이를 더했다 */
    const val FIELDS = "nextPageToken,files(id,name,mimeType,createdTime,modifiedTime,size,webViewLink," +
        "owners(emailAddress),thumbnailLink,thumbnailVersion,imageMediaMetadata(time)," +
        "videoMediaMetadata(durationMillis))"

    /** 파일 하나(`files.get`) — [FIELDS] 의 `files(...)` 안쪽과 같다 */
    const val FILE_FIELDS = "id,name,mimeType,createdTime,modifiedTime,size,webViewLink,owners(emailAddress)," +
        "thumbnailLink,thumbnailVersion,imageMediaMetadata(time),videoMediaMetadata(durationMillis)"

    const val ORDER_BY = "createdTime desc"
    const val PAGE_SIZE = 300

    /** 찍은 날짜순은 끝까지 읽어야 해서 한 번에 최대로 받는다(API 최대 1,000) */
    const val PAGE_SIZE_ALL = 1_000
}

package com.jjw.easygallery.core.data.media

import android.content.IntentSender
import com.jjw.easygallery.core.domain.model.MediaDetails
import com.jjw.easygallery.core.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow

enum class MediaFilter {
    All,

    /** IS_FAVORITE = 1 (API 30+) */
    Favorites,

    /** 휴지통에 있는 항목만 (API 30+) */
    Trashed,
}

/** 편집 요청의 결과. 사용자 동의가 필요하면 UI 가 [NeedsConsent.intentSender] 를 실행한다. */
sealed interface MediaMutation {
    data class NeedsConsent(val intentSender: IntentSender) : MediaMutation
    data class Done(val affected: Int) : MediaMutation
}

interface MediaRepository {
    /** API 30+ 에서만 휴지통·즐겨찾기 사용 가능 */
    val supportsTrashAndFavorites: Boolean

    /**
     * true 면 동의 다이얼로그 확인 시 시스템이 변경까지 수행한다(API 30+ createXxxRequest).
     * false(API 29) 면 동의 후 같은 요청을 다시 호출해야 한다.
     */
    val mutationsCompleteOnConsent: Boolean

    /** 기기 갤러리의 사진·영상을 최신순으로 관찰한다. MediaStore 변경 시 재발행. */
    fun observeMedia(filter: MediaFilter = MediaFilter.All): Flow<List<MediaItem>>

    /**
     * [relativePaths] 앨범에서 DATE_ADDED ≥ [sinceSeconds] 인 항목(1회 조회). 자동 백업 스캔용.
     * [relativePaths] 가 비어 있으면 빈 목록.
     */
    suspend fun queryAddedSince(sinceSeconds: Long, relativePaths: Set<String>, includeVideos: Boolean): List<MediaItem>

    /** EXIF 기반 상세 정보. 읽을 수 없으면 빈 [MediaDetails]. */
    suspend fun readDetails(item: MediaItem): MediaDetails

    suspend fun requestDelete(items: List<MediaItem>): MediaMutation

    suspend fun requestTrash(items: List<MediaItem>, trashed: Boolean): MediaMutation

    suspend fun requestFavorite(items: List<MediaItem>, favorite: Boolean): MediaMutation

    /** 이름 변경·이동 전에 쓰기 권한 동의를 받는다. API 29 는 즉시 Done(0). */
    suspend fun requestWrite(items: List<MediaItem>): MediaMutation

    suspend fun rename(item: MediaItem, newDisplayName: String): MediaMutation

    /** [relativePath] 예: "Pictures/여행/" */
    suspend fun move(items: List<MediaItem>, relativePath: String): MediaMutation
}

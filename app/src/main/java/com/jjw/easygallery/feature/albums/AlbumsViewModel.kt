package com.jjw.easygallery.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.hidden.HiddenMediaRepository
import com.jjw.easygallery.core.data.media.MediaFilter
import com.jjw.easygallery.core.data.media.MediaRepository
import com.jjw.easygallery.core.domain.model.Album
import com.jjw.easygallery.core.domain.model.albumsFrom
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import javax.inject.Inject

/**
 * 하단 "앨범" 칸(`docs/plans/bottom-navigation/spec.md`). 앨범 = MediaStore 의 폴더 — 삼성 갤러리에서 만든 앨범도
 * 실제 폴더라 그대로 나온다(`docs/SAMSUNG_GALLERY_INTEROP.md` §4.2).
 *
 * 숨긴 사진은 뺀다. 숨긴 사진만 있는 폴더가 목록에 뜨면 있다는 사실이 새어 나간다(갤러리 이동 대상과 같은 규칙).
 */
@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    hiddenMedia: HiddenMediaRepository,
) : ViewModel() {

    val uiState: StateFlow<AlbumsUiState> = combine(
        mediaRepository.observeMedia(MediaFilter.All),
        hiddenMedia.observeHiddenIds(),
    ) { items, hidden ->
        val visible = if (hidden.isEmpty()) items else items.filterNot { it.id in hidden }
        AlbumsUiState.Content(
            // 최근 사진이 있는 앨범부터 — 이름순이면 방금 찍은 앨범이 묻힌다
            albums = albumsFrom(visible).sortedByDescending { it.cover?.dateTakenMillis ?: Long.MIN_VALUE },
            favoriteCount = visible.count { it.isFavorite },
            supportsTrashAndFavorites = mediaRepository.supportsTrashAndFavorites,
        ) as AlbumsUiState
    }
        // 권한이 없으면 MediaStore 조회가 실패한다 — 빈 목록으로 두고 사진 칸이 권한을 묻는다
        .catch { e ->
            Timber.w(e, "albums unavailable")
            emit(AlbumsUiState.Content(supportsTrashAndFavorites = mediaRepository.supportsTrashAndFavorites))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), AlbumsUiState.Loading)

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

sealed interface AlbumsUiState {
    data object Loading : AlbumsUiState

    data class Content(
        val albums: List<Album> = emptyList(),
        val favoriteCount: Int = 0,
        /** 즐겨찾기·휴지통은 API 30+ 의 MediaStore 요청으로만 된다. 아니면 바로가기를 숨긴다 */
        val supportsTrashAndFavorites: Boolean = true,
    ) : AlbumsUiState
}

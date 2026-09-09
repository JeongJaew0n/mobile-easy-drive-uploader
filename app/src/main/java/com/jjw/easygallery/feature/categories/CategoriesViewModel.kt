package com.jjw.easygallery.feature.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jjw.easygallery.core.data.category.CategoryRepository
import com.jjw.easygallery.core.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 카테고리 관리 화면. 단순 CRUD 라 UseCase 층 없이 저장소를 직접 부른다(`docs/CATEGORIES.md` §5.4) */
@HiltViewModel
class CategoriesViewModel @Inject constructor(
    private val repository: CategoryRepository,
) : ViewModel() {

    val uiState: StateFlow<CategoriesUiState> = repository.observeCategories()
        .map<List<Category>, CategoriesUiState> { CategoriesUiState.Content(it) }
        .catch { emit(CategoriesUiState.Error(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CategoriesUiState.Loading)

    suspend fun create(name: String, colorIndex: Int): Result<Category> = repository.create(name, colorIndex)

    suspend fun rename(id: Long, name: String): Result<Unit> = repository.rename(id, name)

    fun recolor(id: Long, colorIndex: Int) {
        viewModelScope.launch { repository.recolor(id, colorIndex) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    /** 드래그 정렬이 끝났을 때 최종 순서를 저장한다 */
    fun reorder(orderedIds: List<Long>) {
        viewModelScope.launch { repository.reorder(orderedIds) }
    }

    /** 메뉴의 위/아래 한 칸 이동(접근성·정밀 조정용). 드래그는 [reorder] */
    fun move(id: Long, offset: Int) {
        val content = uiState.value as? CategoriesUiState.Content ?: return
        val ids = content.categories.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + offset
        if (from < 0 || to !in ids.indices) return
        ids.removeAt(from)
        ids.add(to, id)
        viewModelScope.launch { repository.reorder(ids) }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

sealed interface CategoriesUiState {
    data object Loading : CategoriesUiState
    data class Content(val categories: List<Category>) : CategoriesUiState
    data class Error(val throwable: Throwable) : CategoriesUiState
}

package com.jjw.easygallery.core.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** 앱의 모든 화면 키. Navigation 3 백스택에 저장되므로 @Serializable 필수. */
@Serializable
sealed interface AppNavKey : NavKey

@Serializable
data object GalleryKey : AppNavKey

@Serializable
data object SettingsKey : AppNavKey

/** [parentId] 가 null 이면 앱 루트 폴더부터 시작. 하위 폴더로 들어갈 때마다 새 키를 push 한다. */
@Serializable
data class FolderPickerKey(
    val parentId: String? = null,
    val parentName: String? = null,
) : AppNavKey

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

@Serializable
data object UploadQueueKey : AppNavKey

@Serializable
data object TrashKey : AppNavKey

/** Drive 탐색. 기본은 내 드라이브(`root`). 하위 폴더로 들어갈 때마다 새 키를 push 한다. */
@Serializable
data class DriveBrowserKey(
    val folderId: String = "root",
    val folderName: String? = null,
) : AppNavKey

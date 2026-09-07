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

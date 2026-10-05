package com.jjw.easygallery.feature.menu

/** 메뉴 칸에서 갈 수 있는 곳 */
data class MenuActions(
    val onHiddenClick: () -> Unit = {},
    val onDuplicatesClick: () -> Unit = {},
    val onCategoriesClick: () -> Unit = {},
    val onAutoTagClick: () -> Unit = {},
    val onSettingsClick: () -> Unit = {},
)

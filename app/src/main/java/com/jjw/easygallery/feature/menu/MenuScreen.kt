package com.jjw.easygallery.feature.menu

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme

/**
 * 하단 "메뉴" 칸(`docs/plans/bottom-navigation/spec.md`). 가끔 쓰는 도구와 설정. 상태가 없어 ViewModel 이 없다.
 * 숨긴 사진을 앨범 칸이 아니라 여기 둔 것은, 눈에 띄는 자리에 두지 않는 편이 낫기 때문이다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MenuScreen(actions: MenuActions, navigationBar: @Composable () -> Unit = {}) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.nav_menu)) }) },
        bottomBar = navigationBar,
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            MenuRow(painterResource(R.drawable.ic_visibility_off), R.string.hidden_title, actions.onHiddenClick)
            MenuRow(painterResource(R.drawable.ic_content_copy), R.string.duplicates_title, actions.onDuplicatesClick)
            MenuRow(painterResource(R.drawable.ic_label), R.string.settings_categories, actions.onCategoriesClick)
            MenuRow(painterResource(R.drawable.ic_auto_tag), R.string.auto_tag_settings_entry, actions.onAutoTagClick)
            MenuRow(rememberVectorPainter(Icons.Filled.Settings), R.string.settings_title, actions.onSettingsClick)
        }
    }
}

@Composable
private fun MenuRow(icon: Painter, @StringRes title: Int, onClick: () -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        HorizontalDivider()
    }
}

@Preview
@Composable
private fun MenuScreenPreview() {
    EasyGalleryTheme { MenuScreen(actions = MenuActions()) }
}

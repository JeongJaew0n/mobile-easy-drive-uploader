package com.jjw.easygallery.feature.settings

import android.app.LocaleManager
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jjw.easygallery.R

/**
 * 앱 언어(`docs/plans/i18n/spec.md`). 이름은 **그 언어로** 적는다(한국어 / English / 日本語) — 모르는 언어로 바뀐 뒤에도
 * 돌아올 자리를 찾을 수 있어야 한다.
 */
internal enum class AppLanguage(val tag: String?, @param:StringRes @get:StringRes val labelRes: Int) {
    SYSTEM(null, R.string.settings_language_system),
    KOREAN("ko", R.string.language_korean),
    ENGLISH("en", R.string.language_english),
    JAPANESE("ja", R.string.language_japanese),
    ;

    companion object {
        /** 앱별 언어가 없으면(빈 목록) 시스템을 따르는 중이다. 모르는 언어가 잡혀 있어도 시스템으로 본다 */
        fun of(locales: LocaleList): AppLanguage {
            if (locales.isEmpty) return SYSTEM
            val language = locales[0].language
            return entries.find { it.tag == language } ?: SYSTEM
        }
    }
}

/**
 * 설정의 "언어" 줄. Android 13+ 의 앱별 언어(`LocaleManager`)라 12 이하에서는 보이지 않는다 — 그때는 기기 언어를 따른다.
 * 값은 시스템이 저장하므로 ViewModel 을 거치지 않는다. 바꾸면 시스템이 화면을 새 언어로 다시 만든다.
 */
@Composable
internal fun LanguageRow() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) LanguageRowApi33()
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun LanguageRowApi33() {
    val context = LocalContext.current
    // 미리보기에는 시스템 서비스가 없다
    val manager = remember(context) { context.getSystemService(LocaleManager::class.java) } ?: return
    val current = remember(manager) { AppLanguage.of(manager.applicationLocales) }
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(current.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        HorizontalDivider()
    }
    if (open) {
        LanguageDialog(
            current = current,
            onPick = { language ->
                open = false
                if (language != current) {
                    manager.applicationLocales = language.tag?.let { LocaleList.forLanguageTags(it) }
                        ?: LocaleList.getEmptyLocaleList()
                }
            },
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun LanguageDialog(current: AppLanguage, onPick: (AppLanguage) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language_title)) },
        text = {
            Column {
                AppLanguage.entries.forEach { language ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(language) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = language == current, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(language.labelRes))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

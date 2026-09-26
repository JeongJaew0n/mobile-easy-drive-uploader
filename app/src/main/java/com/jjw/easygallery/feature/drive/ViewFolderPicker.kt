package com.jjw.easygallery.feature.drive

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.jjw.easygallery.R
import com.jjw.easygallery.core.domain.model.DriveEntry
import com.jjw.easygallery.core.domain.model.DriveFolder

/**
 * "볼 수 있는 폴더 추가" 의 폴더 고르기(`docs/DRIVE_FILE_SCOPE.md` §10.5).
 *
 * 내 드라이브를 훑는 것은 이동 대상 고르기와 같은 시트를 쓰고, 최상위에 **"공유 문서함"** 을
 * 하나 끼운다. 공유 받은 폴더는 내 루트 아래에 없어서 `'root' in parents` 로는 보이지 않는다
 * (`docs/plans/ledger-per-account/spec.md` §3).
 *
 * 내 드라이브 최상위와 공유 문서함은 폴더가 아니라 **목록**이라 고를 수 없다.
 */
@Composable
internal fun ViewFolderPicker(
    rootName: String,
    listFolders: suspend (parentId: String) -> List<DriveFolder>,
    listSharedFolders: suspend () -> List<DriveFolder>,
    onDismiss: () -> Unit,
    onPick: (DriveFolder) -> Unit,
) {
    val sharedWithMeName = stringResource(R.string.drive_shared_with_me)
    DriveFolderPickerSheet(
        start = DriveFolder(DriveEntry.ROOT_ID, rootName),
        excludeFolderId = null,
        currentParentId = DriveEntry.ROOT_ID,
        unpickableIds = setOf(SHARED_WITH_ME_ID),
        loadFolders = { parentId ->
            when (parentId) {
                DriveEntry.ROOT_ID -> listOf(DriveFolder(SHARED_WITH_ME_ID, sharedWithMeName)) + listFolders(parentId)
                SHARED_WITH_ME_ID -> listSharedFolders()
                else -> listFolders(parentId)
            }
        },
        onDismiss = onDismiss,
        onPick = onPick,
        titleRes = R.string.drive_add_view_folder,
        confirmRes = R.string.drive_add_view_folder_confirm,
    )
}

/**
 * "공유 문서함" 자리의 가짜 ID. Drive 에 실제로 있는 폴더가 아니라 목록이다.
 * Drive 파일 ID 는 영숫자·`-`·`_` 뿐이라 `:` 가 든 이 값과 겹칠 일이 없다.
 */
internal const val SHARED_WITH_ME_ID = "shared-with-me:"

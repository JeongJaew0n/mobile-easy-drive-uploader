package com.jjw.easygallery.feature.drive

import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.DriveEntry

/** 행 ⋮ 메뉴에 무엇을 보일지 — 저장소 능력과 항목 종류로 결정 */
internal data class EntryMenu(
    val open: Boolean,
    val download: Boolean,
    val rename: Boolean,
    val move: Boolean,
    val delete: Boolean,
    val deleteIsTrash: Boolean,
) {
    /** 보일 것이 하나도 없으면 ⋮ 버튼 자체를 내린다 — 빈 팝업이 뜨면 고장으로 보인다 */
    val hasAny: Boolean get() = open || download || rename || move || delete
}

/**
 * [isPickedRoot] 는 Drive 루트 자리 — 거기 놓인 것은 실제 자식이 아니라 **지정 폴더와 기본 폴더의 목록**이다.
 * 지정 폴더는 앱에 권한이 없어 이름 변경·이동·삭제가 모두 실패하므로 메뉴에서 뺀다(`docs/DRIVE_FILE_SCOPE.md` §2).
 */
internal fun entryMenu(
    entry: DriveEntry,
    capabilities: Set<Capability>,
    allowMove: Boolean = true,
    isPickedRoot: Boolean = false,
): EntryMenu {
    val folderOk = !entry.isFolder || Capability.FOLDER_MUTATION in capabilities
    return EntryMenu(
        open = !entry.isFolder && Capability.WEB_LINK in capabilities && entry.webViewLink != null,
        download = !entry.isFolder && Capability.DOWNLOAD in capabilities,
        rename = !isPickedRoot && Capability.RENAME in capabilities && folderOk,
        move = !isPickedRoot && allowMove && Capability.MOVE in capabilities && folderOk,
        delete = !isPickedRoot,
        deleteIsTrash = Capability.TRASH in capabilities,
    )
}

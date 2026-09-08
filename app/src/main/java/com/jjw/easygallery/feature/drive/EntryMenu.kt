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
)

internal fun entryMenu(entry: DriveEntry, capabilities: Set<Capability>, allowMove: Boolean = true): EntryMenu {
    val folderOk = !entry.isFolder || Capability.FOLDER_MUTATION in capabilities
    return EntryMenu(
        open = !entry.isFolder && Capability.WEB_LINK in capabilities && entry.webViewLink != null,
        download = !entry.isFolder && Capability.DOWNLOAD in capabilities,
        rename = Capability.RENAME in capabilities && folderOk,
        move = allowMove && Capability.MOVE in capabilities && folderOk,
        delete = true,
        deleteIsTrash = Capability.TRASH in capabilities,
    )
}

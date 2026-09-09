package com.jjw.easygallery.core.data.remote.smb

import com.jjw.easygallery.core.data.remote.RemotePaths

/**
 * SMB 경로 규칙. `entryId` 는 [RemotePaths] 와 같은 `/` 기반 상대 경로이고,
 * smbj 에는 `\` 구분자·끝 슬래시 없는 형태로 넘긴다. 루트는 빈 문자열.
 */
object SmbPaths {
    fun toSmb(entryId: String): String = entryId.trim('/').replace('/', '\\')

    fun child(parentId: String, name: String, isFolder: Boolean): String =
        RemotePaths.child(parentId, name, isFolder)

    fun parentOf(entryId: String): String = RemotePaths.parentOf(entryId)

    fun nameOf(entryId: String): String = RemotePaths.nameOf(entryId)

    /** `host` 또는 `host:port` → (host, port). 기본 445 */
    fun hostPort(endpoint: String): Pair<String, Int> = RemotePaths.hostPort(endpoint, DEFAULT_PORT)

    private const val DEFAULT_PORT = 445
}

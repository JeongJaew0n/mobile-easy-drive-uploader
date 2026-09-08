package com.jjw.easygallery.core.data.remote.smb

/**
 * SMB 경로 규칙. `entryId` 는 공유 기준 상대 경로를 `/` 로 쓰고 폴더는 `/` 로 끝난다(다른 제공자와 같음).
 * smbj 에는 `\\` 구분자·끝 슬래시 없는 형태로 넘긴다. 루트는 빈 문자열.
 */
object SmbPaths {
    fun toSmb(entryId: String): String = entryId.trim('/').replace('/', '\\')

    fun child(parentId: String, name: String, isFolder: Boolean): String {
        val parent = parentId.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return parent + name.trim().trim('/') + (if (isFolder) "/" else "")
    }

    fun parentOf(entryId: String): String {
        val trimmed = entryId.trim('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx < 0) "" else trimmed.substring(0, idx + 1)
    }

    fun nameOf(entryId: String): String = entryId.trim('/').substringAfterLast('/')

    /** `host` 또는 `host:port` → (host, port). 기본 445 */
    fun hostPort(endpoint: String): Pair<String, Int> {
        val cleaned = endpoint.removePrefix("smb://").trim('/')
        val host = cleaned.substringBefore(':')
        val port = cleaned.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT
        return host to port
    }

    private const val DEFAULT_PORT = 445
}

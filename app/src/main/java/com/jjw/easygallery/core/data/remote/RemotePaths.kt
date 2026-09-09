package com.jjw.easygallery.core.data.remote

/**
 * 경로 기반 제공자(SMB·SFTP·WebDAV)가 공유하는 `entryId` 규칙:
 * 루트 기준 상대 경로를 `/` 로 쓰고, 폴더는 `/` 로 끝난다. 루트는 빈 문자열.
 */
object RemotePaths {

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

    /** `host` 또는 `host:port` → (host, port). 스킴 접두어는 떼어 낸다 */
    fun hostPort(endpoint: String, defaultPort: Int): Pair<String, Int> {
        val cleaned = endpoint.substringAfter("://").trim('/')
        val host = cleaned.substringBefore(':')
        val port = cleaned.substringAfter(':', "").toIntOrNull() ?: defaultPort
        return host to port
    }
}

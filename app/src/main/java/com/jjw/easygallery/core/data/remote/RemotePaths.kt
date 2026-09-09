package com.jjw.easygallery.core.data.remote

/**
 * 경로 기반 제공자(SMB·SFTP·WebDAV)가 공유하는 `entryId` 규칙:
 * 루트 기준 상대 경로를 `/` 로 쓰고, 폴더는 `/` 로 끝난다. 루트는 빈 문자열.
 */
object RemotePaths {

    /**
     * [name] 의 공백은 다듬지 않는다 — Samba·SFTP 는 `" a.jpg"` 같은 이름을 허용하는데, 여기서 다듬으면
     * 목록에 보이는 이름과 서버의 실제 이름이 달라져 삭제·이름 변경이 "없는 파일" 로 실패한다.
     * 사용자 입력은 호출부(ViewModel)에서 이미 다듬는다.
     */
    fun child(parentId: String, name: String, isFolder: Boolean): String {
        val parent = parentId.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        return parent + name.trim('/') + (if (isFolder) "/" else "")
    }

    fun parentOf(entryId: String): String {
        val trimmed = entryId.trim('/')
        val idx = trimmed.lastIndexOf('/')
        return if (idx < 0) "" else trimmed.substring(0, idx + 1)
    }

    fun nameOf(entryId: String): String = entryId.trim('/').substringAfterLast('/')

    /**
     * `host`, `host:port`, `[IPv6]`, `[IPv6]:port` → (host, port). 스킴 접두어는 떼어 낸다.
     * 대괄호 없는 IPv6(`fe80::1`)는 포트를 붙일 수 없는 표기라 전체를 호스트로 본다.
     */
    fun hostPort(endpoint: String, defaultPort: Int): Pair<String, Int> {
        val cleaned = endpoint.substringAfter("://").trim('/')
        val close = cleaned.indexOf(']')
        if (cleaned.startsWith("[") && close > 0) {
            val port = cleaned.substring(close + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return cleaned.substring(1, close) to port
        }
        if (cleaned.count { it == ':' } > 1) return cleaned to defaultPort
        val host = cleaned.substringBefore(':')
        val port = cleaned.substringAfter(':', "").toIntOrNull() ?: defaultPort
        return host to port
    }
}

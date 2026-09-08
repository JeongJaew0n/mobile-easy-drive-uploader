package com.jjw.easygallery.core.data.remote

/**
 * 같은 이름이 이미 있으면 `IMG (1).jpg`, `IMG (2).jpg`… 로 비켜 간다.
 * 재개 가능한 세션이 아니면 업로드가 덮어쓰기가 되므로(WebDAV·S3 단일 PUT) 세션 시작 시 한 번 검사한다.
 */
object RemoteNames {
    suspend fun unique(displayName: String, exists: suspend (String) -> Boolean): String {
        if (!exists(displayName)) return displayName
        val dot = displayName.lastIndexOf('.')
        val stem = if (dot > 0) displayName.substring(0, dot) else displayName
        val ext = if (dot > 0) displayName.substring(dot) else ""
        for (n in 1..MAX_ATTEMPTS) {
            val candidate = "$stem ($n)$ext"
            if (!exists(candidate)) return candidate
        }
        return "$stem (${System.currentTimeMillis()})$ext"
    }

    private const val MAX_ATTEMPTS = 99
}

package com.jjw.easygallery.core.data.remote

/**
 * 같은 이름이 이미 있으면 `IMG (1).jpg`, `IMG (2).jpg`… 로 비켜 간다.
 * 재개 가능한 세션이 아니면 업로드가 덮어쓰기가 되므로(WebDAV·S3 단일 PUT) 세션 시작 시 한 번 검사한다.
 */
object RemoteNames {
    suspend fun unique(displayName: String, exists: suspend (String) -> Boolean): String {
        if (!exists(displayName)) return displayName
        for (candidate in candidates(displayName)) if (!exists(candidate)) return candidate
        return fallback(displayName)
    }

    /** 블로킹 I/O(SMB 등) 안에서 쓰는 동기 판 — 코루틴 밖에서 `runBlocking` 을 피한다 */
    fun uniqueBlocking(displayName: String, exists: (String) -> Boolean): String {
        if (!exists(displayName)) return displayName
        for (candidate in candidates(displayName)) if (!exists(candidate)) return candidate
        return fallback(displayName)
    }

    private fun candidates(displayName: String): Sequence<String> {
        val (stem, ext) = split(displayName)
        return (1..MAX_ATTEMPTS).asSequence().map { n -> "$stem ($n)$ext" }
    }

    private fun fallback(displayName: String): String {
        val (stem, ext) = split(displayName)
        return "$stem (${System.currentTimeMillis()})$ext"
    }

    private fun split(displayName: String): Pair<String, String> {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) displayName.substring(0, dot) to displayName.substring(dot) else displayName to ""
    }

    private const val MAX_ATTEMPTS = 99
}

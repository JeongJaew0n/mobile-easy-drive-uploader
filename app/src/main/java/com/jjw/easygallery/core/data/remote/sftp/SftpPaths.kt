package com.jjw.easygallery.core.data.remote.sftp

/** SFTP 는 서버의 절대 경로로 말한다. `entryId`(루트 기준 상대) 를 절대 경로로 바꾼다 */
object SftpPaths {

    /** [root] 는 끝 `/` 없는 절대 경로이거나 빈 문자열(서버 기본 = `/`) */
    fun absolute(root: String, entryId: String): String {
        val base = root.trimEnd('/')
        val relative = entryId.trim('/')
        return when {
            relative.isEmpty() -> base.ifEmpty { "/" }
            else -> "$base/$relative"
        }
    }
}

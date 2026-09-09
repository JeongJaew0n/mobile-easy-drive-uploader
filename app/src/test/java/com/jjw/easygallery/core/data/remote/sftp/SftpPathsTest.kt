package com.jjw.easygallery.core.data.remote.sftp

import com.jjw.easygallery.core.data.remote.RemotePaths
import org.junit.Assert.assertEquals
import org.junit.Test

class SftpPathsTest {

    @Test
    fun `absolute joins the account root with the relative entry id`() {
        assertEquals("/volume1/photo", SftpPaths.absolute("/volume1/photo", ""))
        assertEquals("/volume1/photo/2026/a.jpg", SftpPaths.absolute("/volume1/photo/", "2026/a.jpg"))
        assertEquals("/volume1/photo/2026", SftpPaths.absolute("/volume1/photo", "/2026/"))
    }

    @Test
    fun `an empty root means the server default directory`() {
        assertEquals("/", SftpPaths.absolute("", ""))
        assertEquals("/2026", SftpPaths.absolute("", "2026/"))
    }

    @Test
    fun `host and port default to 22 for sftp`() {
        assertEquals("nas.local" to 22, RemotePaths.hostPort("nas.local", 22))
        assertEquals("10.0.0.2" to 2222, RemotePaths.hostPort("sftp://10.0.0.2:2222/", 22))
    }
}

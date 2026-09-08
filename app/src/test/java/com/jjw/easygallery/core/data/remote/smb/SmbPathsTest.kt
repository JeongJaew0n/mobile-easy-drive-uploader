package com.jjw.easygallery.core.data.remote.smb

import org.junit.Assert.assertEquals
import org.junit.Test

class SmbPathsTest {

    @Test
    fun `entry ids use forward slashes and smb paths use backslashes without trailing separator`() {
        assertEquals("", SmbPaths.toSmb(""))
        assertEquals("2026\\09", SmbPaths.toSmb("/2026/09/"))
        assertEquals("2026\\a.jpg", SmbPaths.toSmb("2026/a.jpg"))
    }

    @Test
    fun `child parent and name helpers`() {
        assertEquals("여행/", SmbPaths.child("", "여행", isFolder = true))
        assertEquals("2026/09/a.jpg", SmbPaths.child("2026/09/", " a.jpg ", isFolder = false))
        assertEquals("2026/", SmbPaths.parentOf("2026/09/"))
        assertEquals("", SmbPaths.parentOf("a.jpg"))
        assertEquals("09", SmbPaths.nameOf("2026/09/"))
    }

    @Test
    fun `host and port parsing defaults to 445`() {
        assertEquals("nas.local" to 445, SmbPaths.hostPort("nas.local"))
        assertEquals("192.168.0.10" to 4455, SmbPaths.hostPort("smb://192.168.0.10:4455/"))
    }
}

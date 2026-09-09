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
        assertEquals("2026/09/a.jpg", SmbPaths.child("2026/09/", "a.jpg", isFolder = false))
        assertEquals("2026/", SmbPaths.parentOf("2026/09/"))
        assertEquals("", SmbPaths.parentOf("a.jpg"))
        assertEquals("09", SmbPaths.nameOf("2026/09/"))
    }

    @Test
    fun `host and port parsing defaults to 445`() {
        assertEquals("nas.local" to 445, SmbPaths.hostPort("nas.local"))
        assertEquals("192.168.0.10" to 4455, SmbPaths.hostPort("smb://192.168.0.10:4455/"))
    }

    @Test
    fun `ipv6 literals keep their address`() {
        assertEquals("fe80::1" to 445, SmbPaths.hostPort("[fe80::1]"))
        assertEquals("fe80::1" to 4455, SmbPaths.hostPort("[fe80::1]:4455"))
        assertEquals("fe80::1" to 445, SmbPaths.hostPort("fe80::1"))
    }

    @Test
    fun `names keep leading and trailing spaces because servers allow them`() {
        assertEquals("2026/ a.jpg", SmbPaths.child("2026/", " a.jpg", isFolder = false))
    }
}

class RemoteAccountLocationTest {
    @Test
    fun `location shows share for smb and bucket for s3`() {
        val smb = com.jjw.easygallery.core.domain.model.RemoteAccount(
            id = "a",
            kind = com.jjw.easygallery.core.domain.model.RemoteAccountKind.SMB,
            displayName = "NAS",
            endpoint = "nas.local",
            bucketOrRoot = "photo",
        )
        assertEquals("smb://nas.local/photo", smb.location)
        val s3 = smb.copy(kind = com.jjw.easygallery.core.domain.model.RemoteAccountKind.S3, endpoint = "https://s3")
        assertEquals("https://s3 · photo", s3.location)
    }
}

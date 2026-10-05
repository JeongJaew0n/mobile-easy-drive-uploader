package com.jjw.easygallery.core.data.upload

import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupDestinationsTest {

    private val nas = RemoteAccount(id = "nas1", kind = RemoteAccountKind.WEBDAV, displayName = "MyNAS")

    @Test
    fun `연결 계정의 Drive 는 주 Drive`() {
        val d = BackupDestinations.describe("drive:a@example.com", listOf(nas), primaryEmail = "A@example.com")
        assertTrue(d.isDrive)
        assertTrue(d.isPrimaryDrive)
    }

    @Test
    fun `다른 계정 업로드는 Drive 지만 주 Drive 가 아니다`() {
        val d = BackupDestinations.describe("drive:b@example.com", emptyList(), primaryEmail = "a@example.com")
        assertTrue(d.isDrive)
        assertFalse(d.isPrimaryDrive)
        assertEquals("b@example.com", d.driveEmail)
    }

    @Test
    fun `다른 저장소는 이름으로, 지웠으면 id 로`() {
        assertEquals("MyNAS", BackupDestinations.describe("remote:nas1", listOf(nas), null).remoteName)
        assertEquals("gone", BackupDestinations.describe("remote:gone", listOf(nas), null).remoteName)
    }

    @Test
    fun `주인 미정 옛 기록은 연결 계정 칸으로 합치고 순서는 주 Drive - 다른 계정 - 저장소`() {
        val ordered = BackupDestinations.ordered(
            listOf("remote:nas1", "drive:", "drive:b@example.com", "drive:a@example.com"),
            listOf(nas),
            primaryEmail = "a@example.com",
        )
        assertEquals(
            listOf("drive:a@example.com", "drive:b@example.com", "remote:nas1"),
            ordered.map { it.destination },
        )
    }
}

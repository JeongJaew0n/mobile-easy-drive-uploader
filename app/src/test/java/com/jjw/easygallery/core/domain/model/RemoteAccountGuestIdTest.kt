package com.jjw.easygallery.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteAccountGuestIdTest {

    @Test
    fun `guest id round trips the email`() {
        val id = RemoteAccount.guestDriveId("b@example.com")
        assertEquals("b@example.com", RemoteAccount.guestEmailOf(id))
    }

    @Test
    fun `ordinary ids are not guests`() {
        assertNull(RemoteAccount.guestEmailOf(null))
        assertNull(RemoteAccount.guestEmailOf(RemoteAccount.GOOGLE_DRIVE_ID))
        // 다른 저장소 계정은 UUID 다
        assertNull(RemoteAccount.guestEmailOf("321339e1-6f7e-47ff-849c-e5a037619d36"))
    }
}

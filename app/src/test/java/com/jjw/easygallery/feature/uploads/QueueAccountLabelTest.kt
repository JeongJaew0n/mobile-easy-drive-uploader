package com.jjw.easygallery.feature.uploads

import com.jjw.easygallery.core.domain.model.RemoteAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueAccountLabelTest {

    @Test
    fun `a guest upload shows B's email, not a disconnected storage`() {
        // 등록 목록에는 없다 — 다른 계정 업로드는 일부러 등록하지 않는다
        assertEquals("b@example.com", queueAccountLabel(RemoteAccount.guestDriveId("b@example.com"), emptyMap()))
    }

    @Test
    fun `a registered storage shows its name`() {
        assertEquals("MyNAS", queueAccountLabel("acct-1", mapOf("acct-1" to "MyNAS")))
    }

    @Test
    fun `a storage that was removed is unknown`() {
        assertNull(queueAccountLabel("gone", mapOf("acct-1" to "MyNAS")))
    }
}

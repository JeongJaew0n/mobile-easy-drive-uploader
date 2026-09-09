package com.jjw.easygallery.core.data.remote.sftp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator

class PinnedHostKeyVerifierTest {

    private val key = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair().public

    @Test
    fun `accepts only the pinned fingerprint and records what the server presented`() {
        val fingerprint = key.sshHostKeySha256()
        assertEquals(SHA256_HEX_LENGTH, fingerprint.length)

        val pinned = PinnedHostKeyVerifier(fingerprint.uppercase()) // 대소문자 무시
        assertTrue(pinned.verify("nas.local", PORT, key))
        assertEquals(fingerprint, pinned.presentedSha256)
    }

    @Test
    fun `rejects an unknown host key and a changed one, but still reports the fingerprint`() {
        val unknown = PinnedHostKeyVerifier(expectedSha256 = null)
        assertFalse(unknown.verify("nas.local", PORT, key))
        assertEquals(key.sshHostKeySha256(), unknown.presentedSha256)

        val changed = PinnedHostKeyVerifier("00".repeat(SHA256_BYTES))
        assertFalse(changed.verify("nas.local", PORT, key))
    }

    private companion object {
        const val KEY_BITS = 2048
        const val PORT = 22
        const val SHA256_BYTES = 32
        const val SHA256_HEX_LENGTH = 64
    }
}

package com.jjw.easygallery.core.data.remote.sftp

import com.jjw.easygallery.core.data.remote.RemotePaths
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

/** SSH 공개키의 SHA-256 지문(소문자 hex). WebDAV 인증서 지문과 같은 형식이라 UI·저장(`certSha256`)을 공유한다 */
fun PublicKey.sshHostKeySha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(Buffer.PlainBuffer().putPublicKey(this).compactData)
        .joinToString("") { byte -> "%02x".format(byte) }

/**
 * 저장해 둔 지문과 **정확히 같은** 호스트 키만 신뢰한다(`docs/NAS_STORAGE.md` §7).
 * known_hosts 파일은 쓰지 않는다 — 계정 행에 붙은 지문이 곧 신뢰 기준.
 */
class PinnedHostKeyVerifier(private val expectedSha256: String?) : HostKeyVerifier {

    /** 검증 결과와 무관하게 서버가 제시한 지문(신뢰 여부를 묻는 다이얼로그용) */
    @Volatile
    var presentedSha256: String? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val fingerprint = key.sshHostKeySha256()
        presentedSha256 = fingerprint
        return expectedSha256 != null && fingerprint.equals(expectedSha256, ignoreCase = true)
    }

    /** known_hosts 를 쓰지 않으므로 서버가 고를 알고리즘을 제한하지 않는다 */
    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
}

/**
 * 인증 전에 서버의 호스트 키 지문만 읽는다(자격 증명은 보내지 않는다).
 * 계정 추가 화면이 "이 서버를 신뢰할까요?" 를 묻는 데 쓴다.
 */
fun fetchSshHostKeySha256(endpoint: String): String {
    val (host, port) = RemotePaths.hostPort(endpoint, SSH_DEFAULT_PORT)
    val verifier = PinnedHostKeyVerifier(expectedSha256 = null)
    SSHClient().use { ssh ->
        ssh.connectTimeout = FETCH_TIMEOUT_MILLIS
        ssh.timeout = FETCH_TIMEOUT_MILLIS
        // 지문을 받아 두기만 하고 검증은 통과시킨다 — 인증은 하지 않으므로 비밀은 노출되지 않는다
        ssh.addHostKeyVerifier(
            object : HostKeyVerifier {
                override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                    verifier.verify(hostname, port, key)
                    return true
                }

                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
            },
        )
        runCatching { ssh.connect(host, port) }
    }
    return requireNotNull(verifier.presentedSha256) { "호스트 키를 읽지 못했습니다" }
}

private const val SSH_DEFAULT_PORT = 22
private const val FETCH_TIMEOUT_MILLIS = 15_000

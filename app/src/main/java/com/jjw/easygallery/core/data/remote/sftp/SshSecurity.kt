package com.jjw.easygallery.core.data.remote.sftp

import org.bouncycastle.jce.provider.BouncyCastleProvider
import timber.log.Timber
import java.security.Security

/**
 * 안드로이드에는 `BC` 라는 이름의 **축소판** BouncyCastle 이 미리 등록돼 있고, 여기에는 sshj 가 쓰는
 * `X25519`(curve25519 키 교환)·Ed25519 가 없다. 그대로 두면 SFTP 연결이
 * `no such algorithm: X25519 for provider BC` 로 실패한다(실기기 확인, `docs/NAS_STORAGE.md` §7).
 *
 * 그래서 앱이 이미 번들한 전체 BouncyCastle(smbj 와 공용)로 갈아 끼운다. 목록 **맨 뒤**에 넣으므로
 * TLS 등 다른 알고리즘은 그대로 Conscrypt/AndroidOpenSSL 가 처리하고, 다른 제공자에 없는 것만 여기로 온다.
 */
internal object SshSecurity {

    @Volatile
    private var ready = false

    @Synchronized
    fun ensureFullBouncyCastle() {
        if (ready) return
        ready = true
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing != null && existing::class.java == BouncyCastleProvider::class.java) return
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        val added = Security.addProvider(BouncyCastleProvider())
        Timber.i("BouncyCastle 교체: 기존=%s, 위치=%d", existing?.javaClass?.name, added)
    }
}

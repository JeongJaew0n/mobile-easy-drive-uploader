package com.jjw.easygallery.core.data.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 원격 저장소 비밀(Secret Key·비밀번호)을 Android Keystore 의 AES 키로 GCM 암호화해 앱 전용 prefs 에 둔다.
 * Keystore 키는 기기를 떠날 수 없으므로 백업·루팅 없는 복제로는 복호화가 안 된다(`docs/MULTI_CLOUD.md` §3).
 * `security-crypto` 라이브러리는 유지 관리가 끊겨 쓰지 않는다.
 */
interface SecretStore {
    fun put(ref: String, secret: String)
    fun get(ref: String): String?
    fun remove(ref: String)
}

@Singleton
class KeystoreSecretStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SecretStore {
    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun put(ref: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val payload = cipher.iv + encrypted
        prefs.edit().putString(ref, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    override fun get(ref: String): String? {
        val encoded = prefs.getString(ref, null) ?: return null
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, IV_LENGTH)
        val body = payload.copyOfRange(IV_LENGTH, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }

    override fun remove(ref: String) {
        prefs.edit().remove(ref).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS_NAME = "remote_secrets"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "easygallery.remote.secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}

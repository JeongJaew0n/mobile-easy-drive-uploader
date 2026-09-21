package com.jjw.easygallery.core.data.hidden

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

// 환경설정(user_prefs)과 섞지 않는다 — 백업·초기화 범위를 따로 가져갈 수 있게
private val Context.hiddenPinStore: DataStore<Preferences> by preferencesDataStore(name = "hidden_pin")

/** 잠금 상태. [remainingMillis] 가 0 보다 크면 지금은 시도할 수 없다 */
data class PinGate(
    val isSet: Boolean,
    val failedAttempts: Int,
    val remainingMillis: Long,
)

/**
 * 숨긴 사진 PIN 보관(`docs/PHOTO_HIDING.md` §3).
 * **원문은 어디에도 두지 않는다** — 소금과 PBKDF2 해시만 저장한다.
 */
@Singleton
class HiddenPinRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val store get() = context.hiddenPinStore

    fun observeGate(nowMillis: () -> Long = System::currentTimeMillis): Flow<PinGate> =
        store.data.map { prefs ->
            PinGate(
                isSet = prefs[KEY_HASH] != null,
                failedAttempts = prefs[KEY_FAILED] ?: 0,
                remainingMillis = HiddenPin.remainingLockMillis(prefs[KEY_LOCKED_UNTIL] ?: 0, nowMillis()),
            )
        }

    suspend fun isSet(): Boolean = store.data.first()[KEY_HASH] != null

    /** 처음 설정하거나 바꾼다. 실패 기록도 함께 지운다 */
    suspend fun set(pin: String) {
        val salt = HiddenPin.newSalt()
        val hash = HiddenPin.hash(pin, salt)
        store.edit {
            it[KEY_SALT] = salt.encode()
            it[KEY_HASH] = hash.encode()
            it[KEY_FAILED] = 0
            it[KEY_LOCKED_UNTIL] = 0
        }
    }

    /**
     * 맞으면 실패 기록을 지우고 true. 틀리면 실패 횟수를 올리고 잠금 시각을 다시 잡는다.
     * 잠겨 있는 동안은 아예 검사하지 않는다 — 안 그러면 잠금이 무의미하다.
     */
    suspend fun verify(pin: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val prefs = store.data.first()
        val locked = HiddenPin.remainingLockMillis(prefs[KEY_LOCKED_UNTIL] ?: 0, nowMillis) > 0
        val salt = prefs[KEY_SALT]?.decode()
        val hash = prefs[KEY_HASH]?.decode()
        val ok = !locked && salt != null && hash != null && HiddenPin.matches(pin, salt, hash)

        when {
            ok -> onSuccess()
            // 잠긴 동안에는 실패로 세지 않는다. 안 그러면 기다리는 사이에 잠금이 계속 늘어난다
            !locked -> onFailure((prefs[KEY_FAILED] ?: 0) + 1, nowMillis)
        }
        return ok
    }

    private suspend fun onSuccess() {
        store.edit {
            it[KEY_FAILED] = 0
            it[KEY_LOCKED_UNTIL] = 0
        }
    }

    private suspend fun onFailure(failed: Int, nowMillis: Long) {
        val lockSeconds = HiddenPin.lockSecondsFor(failed)
        store.edit {
            it[KEY_FAILED] = failed
            it[KEY_LOCKED_UNTIL] = if (lockSeconds > 0) nowMillis + lockSeconds * MILLIS_PER_SECOND else 0
        }
    }

    /** "자동 태그 전부 지우기" 처럼 사용자가 명시적으로 초기화할 때만 */
    suspend fun clear() {
        store.edit { it.clear() }
    }

    private fun ByteArray.encode(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.decode(): ByteArray? = runCatching { Base64.decode(this, Base64.NO_WRAP) }.getOrNull()

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        val KEY_SALT = stringPreferencesKey("salt")
        val KEY_HASH = stringPreferencesKey("hash")
        val KEY_FAILED = intPreferencesKey("failed_attempts")
        val KEY_LOCKED_UNTIL = longPreferencesKey("locked_until")
    }
}

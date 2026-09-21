package com.jjw.easygallery.core.data.hidden

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.min
import kotlin.math.pow

/**
 * 숨긴 사진 PIN 의 해시·검증·잠금 계산(`docs/PHOTO_HIDING.md`).
 * 저장소·안드로이드에 기대지 않는 순수 함수라 단위 테스트로 굳힌다.
 *
 * **이건 엿보기 방지지 암호화가 아니다.** 사진 파일 자체는 잠기지 않는다.
 */
object HiddenPin {

    /** 4자리 미만은 사실상 잠금이 아니고, 6자리를 넘으면 외우기 어렵다 */
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6

    /** 이 횟수까지는 그냥 틀려도 된다. 넘으면 기다려야 한다 */
    const val FREE_ATTEMPTS = 5

    private const val ITERATIONS = 200_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val ALGORITHM = "PBKDF2WithHmacSHA256"

    private const val FIRST_LOCK_SECONDS = 30L
    private const val MAX_LOCK_SECONDS = 600L

    fun isValidFormat(pin: String): Boolean =
        pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it.isDigit() }

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(SALT_BYTES).also(random::nextBytes)

    fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
    }

    /** 길이와 무관하게 상수 시간으로 비교한다 */
    fun matches(pin: String, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt), expected)

    /**
     * [failedAttempts] 번 틀린 뒤 기다려야 하는 시간(초). [FREE_ATTEMPTS] 이하면 0.
     * 그 뒤로는 30초에서 시작해 실패마다 두 배, [MAX_LOCK_SECONDS] 에서 멈춘다.
     *
     * 4자리는 만 가지뿐이라 제한이 없으면 잠금이 아니라 장식이다.
     */
    fun lockSecondsFor(failedAttempts: Int): Long {
        if (failedAttempts <= FREE_ATTEMPTS) return 0
        val step = failedAttempts - FREE_ATTEMPTS - 1
        val seconds = FIRST_LOCK_SECONDS * 2.0.pow(min(step, MAX_DOUBLINGS)).toLong()
        return min(seconds, MAX_LOCK_SECONDS)
    }

    /** 잠금이 풀릴 때까지 남은 밀리초. 0 이면 지금 시도할 수 있다 */
    fun remainingLockMillis(lockedUntilMillis: Long, nowMillis: Long): Long =
        (lockedUntilMillis - nowMillis).coerceAtLeast(0)

    /** 2^5 * 30 = 960초로 이미 상한을 넘으므로 그 위는 셀 필요가 없다 */
    private const val MAX_DOUBLINGS = 5
}

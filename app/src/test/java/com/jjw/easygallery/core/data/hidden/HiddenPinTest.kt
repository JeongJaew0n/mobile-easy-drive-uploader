package com.jjw.easygallery.core.data.hidden

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenPinTest {

    @Test
    fun `4에서 6자리 숫자만 받는다`() {
        assertTrue(HiddenPin.isValidFormat("1234"))
        assertTrue(HiddenPin.isValidFormat("123456"))
        assertFalse(HiddenPin.isValidFormat("123"))
        assertFalse(HiddenPin.isValidFormat("1234567"))
        assertFalse(HiddenPin.isValidFormat("12a4"))
        assertFalse(HiddenPin.isValidFormat(""))
    }

    @Test
    fun `같은 PIN 은 같은 해시, 다른 PIN 은 다른 해시`() {
        val salt = HiddenPin.newSalt()
        assertTrue(HiddenPin.matches("1234", salt, HiddenPin.hash("1234", salt)))
        assertFalse(HiddenPin.matches("1235", salt, HiddenPin.hash("1234", salt)))
    }

    /** 소금이 없으면 같은 PIN 이 같은 해시라 하나가 뚫리면 전부 뚫린다 */
    @Test
    fun `소금이 다르면 같은 PIN 도 해시가 다르다`() {
        val a = HiddenPin.hash("1234", HiddenPin.newSalt())
        val b = HiddenPin.hash("1234", HiddenPin.newSalt())
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun `정해진 횟수까지는 기다리지 않는다`() {
        for (n in 0..HiddenPin.FREE_ATTEMPTS) {
            assertEquals("실패 $n 회", 0L, HiddenPin.lockSecondsFor(n))
        }
    }

    @Test
    fun `그 뒤로는 30초에서 시작해 두 배씩 늘고 10분에서 멈춘다`() {
        assertEquals(30L, HiddenPin.lockSecondsFor(6))
        assertEquals(60L, HiddenPin.lockSecondsFor(7))
        assertEquals(120L, HiddenPin.lockSecondsFor(8))
        assertEquals(240L, HiddenPin.lockSecondsFor(9))
        assertEquals(480L, HiddenPin.lockSecondsFor(10))
        assertEquals(600L, HiddenPin.lockSecondsFor(11))
        assertEquals(600L, HiddenPin.lockSecondsFor(50)) // 상한에서 멈춘다
    }

    @Test
    fun `남은 잠금 시간은 음수가 되지 않는다`() {
        assertEquals(5_000L, HiddenPin.remainingLockMillis(lockedUntilMillis = 15_000, nowMillis = 10_000))
        assertEquals(0L, HiddenPin.remainingLockMillis(lockedUntilMillis = 5_000, nowMillis = 10_000))
        assertEquals(0L, HiddenPin.remainingLockMillis(lockedUntilMillis = 0, nowMillis = 10_000))
    }

    /**
     * 상한에 닿으면 잠금 "초" 가 600 으로 고정된다. 화면이 남은 초를 키로 쓰면 값이 안 바뀌어
     * 카운트다운이 멈추므로, 상태는 **절대 시각**으로 들고 다녀야 한다는 근거.
     */
    @Test
    fun `상한에 닿으면 잠금 초가 더 이상 변하지 않는다`() {
        assertEquals(HiddenPin.lockSecondsFor(11), HiddenPin.lockSecondsFor(12))
        assertEquals(HiddenPin.lockSecondsFor(12), HiddenPin.lockSecondsFor(13))
    }
}

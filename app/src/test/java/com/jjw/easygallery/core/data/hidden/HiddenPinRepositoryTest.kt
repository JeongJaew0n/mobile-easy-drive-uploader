package com.jjw.easygallery.core.data.hidden

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 잠금은 이 기능의 유일한 보호막이라 순수 함수 테스트만으로는 부족하다.
 * 실제 DataStore 를 써서 저장·재시작 경로까지 확인한다.
 */
@RunWith(RobolectricTestRunner::class)
class HiddenPinRepositoryTest {

    private lateinit var repository: HiddenPinRepository

    @Before
    fun setUp() {
        repository = HiddenPinRepository(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "datastore")
            .deleteRecursively()
    }

    @Test
    fun `처음에는 설정돼 있지 않다`() = runTest {
        assertFalse(repository.isSet())
        assertFalse(repository.observeGate().first().isSet)
    }

    @Test
    fun `설정한 PIN 으로 들어가고 틀린 PIN 은 막힌다`() = runTest {
        repository.set("1234")
        assertTrue(repository.isSet())
        assertEquals(VerifyResult.Wrong, repository.verify("9999"))
        assertEquals(VerifyResult.Ok, repository.verify("1234"))
    }

    @Test
    fun `성공하면 실패 기록이 지워진다`() = runTest {
        repository.set("1234")
        repeat(3) { repository.verify("0000") }
        assertEquals(3, repository.observeGate().first().failedAttempts)

        repository.verify("1234")
        assertEquals(0, repository.observeGate().first().failedAttempts)
    }

    /** 6회째부터 잠긴다. 잠긴 동안에는 **맞는 PIN 도** 검사하지 않는다 */
    @Test
    fun `여섯 번 틀리면 잠기고 맞는 PIN 도 그동안은 막힌다`() = runTest {
        repository.set("1234")
        val now = 1_000_000L
        repeat(HiddenPin.FREE_ATTEMPTS) {
            assertEquals(VerifyResult.Wrong, repository.verify("0000", now))
        }
        assertEquals(VerifyResult.Wrong, repository.verify("0000", now))

        val gate = repository.observeGate().first()
        assertEquals(HiddenPin.FREE_ATTEMPTS + 1, gate.failedAttempts)
        assertEquals(now + 30_000, gate.lockedUntilMillis)

        // 잠긴 동안: 맞는 PIN 이어도 Locked
        assertEquals(VerifyResult.Locked, repository.verify("1234", now + 1_000))
        // 기다린 뒤에는 통과
        assertEquals(VerifyResult.Ok, repository.verify("1234", now + 31_000))
    }

    /** 잠긴 동안의 시도를 실패로 세면 기다리는 사이 잠금이 계속 늘어난다 */
    @Test
    fun `잠긴 동안의 시도는 실패로 세지 않는다`() = runTest {
        repository.set("1234")
        val now = 1_000_000L
        repeat(HiddenPin.FREE_ATTEMPTS + 1) { repository.verify("0000", now) }
        val locked = repository.observeGate().first()

        repeat(5) { repository.verify("0000", now + 1_000) }

        val after = repository.observeGate().first()
        assertEquals(locked.failedAttempts, after.failedAttempts)
        assertEquals(locked.lockedUntilMillis, after.lockedUntilMillis)
    }

    @Test
    fun `PIN 을 바꾸면 실패 기록과 잠금이 풀린다`() = runTest {
        repository.set("1234")
        val now = 1_000_000L
        repeat(HiddenPin.FREE_ATTEMPTS + 1) { repository.verify("0000", now) }

        repository.set("5678")

        val gate = repository.observeGate().first()
        assertEquals(0, gate.failedAttempts)
        assertEquals(0, gate.lockedUntilMillis)
        assertEquals(VerifyResult.Ok, repository.verify("5678", now))
    }
}

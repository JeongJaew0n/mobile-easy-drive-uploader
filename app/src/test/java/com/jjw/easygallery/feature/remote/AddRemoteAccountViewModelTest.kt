package com.jjw.easygallery.feature.remote

import com.jjw.easygallery.core.data.remote.RemoteAccountRepository
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.RemoteStorageFactory
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AddRemoteAccountViewModelTest {

    private val storage: RemoteStorage = mockk {
        every { rootId } returns ""
    }
    private val created = slot<RemoteAccount>()
    private val factory = RemoteStorageFactory { account, _ ->
        created.captured = account
        storage
    }
    private val accounts: RemoteAccountRepository = mockk()
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = AddRemoteAccountViewModel(
        accounts,
        mapOf(RemoteAccountKind.S3 to factory, RemoteAccountKind.WEBDAV to factory),
        OkHttpClient(),
        testDispatcher,
    )

    @Test
    fun `naver preset fills endpoint and region, submit needs bucket for s3`() {
        val vm = viewModel()
        assertEquals("https://kr.object.ncloudstorage.com", vm.uiState.value.endpoint)
        assertEquals("kr-standard", vm.uiState.value.region)
        assertFalse(vm.uiState.value.canSubmit)

        vm.update { copy(displayName = "n", username = "AK", secret = "SK") }
        assertFalse(vm.uiState.value.canSubmit)
        vm.update { copy(bucketOrRoot = "photos") }
        assertTrue(vm.uiState.value.canSubmit)

        vm.setKind(RemoteAccountKind.WEBDAV)
        assertEquals("", vm.uiState.value.endpoint)
        vm.update { copy(endpoint = "https://nas/photos") }
        assertTrue(vm.uiState.value.canSubmit) // WebDAV 는 버킷 불필요
    }

    @Test
    fun `connection test lists the root with folders only and reports success`() = runTest(testDispatcher) {
        coEvery { storage.listChildren("", null, true) } returns mockk(relaxed = true)
        val vm = viewModel()
        vm.update { copy(displayName = "n", username = "AK", secret = "SK", bucketOrRoot = "photos") }

        vm.testConnection()
        advanceUntilIdle()

        assertTrue(vm.uiState.value.testResult!!.isSuccess)
        assertEquals("photos", created.captured.bucketOrRoot)
        assertEquals("https://kr.object.ncloudstorage.com", created.captured.endpoint)
    }

    @Test
    fun `connection failure surfaces the error and does not save`() = runTest(testDispatcher) {
        coEvery { storage.listChildren("", null, true) } throws IOException("403")
        val vm = viewModel()
        vm.update { copy(displayName = "n", username = "AK", secret = "SK", bucketOrRoot = "photos") }

        vm.testConnection()
        advanceUntilIdle()

        assertEquals("403", vm.uiState.value.testResult!!.exceptionOrNull()!!.message)
        assertNull(vm.uiState.value.pendingCertSha256)
        coVerify(exactly = 0) { accounts.add(any(), any()) }
    }

    @Test
    fun `save stores the account with the secret`() = runTest(testDispatcher) {
        coEvery { accounts.add(any(), "SK") } answers { firstArg() }
        val vm = viewModel()
        vm.update { copy(displayName = " Naver ", username = "AK", secret = "SK", bucketOrRoot = "photos") }

        vm.save()
        advanceUntilIdle()

        assertEquals(AddRemoteAccountEvent.Saved, vm.events.value)
        coVerify { accounts.add(match { it.displayName == "Naver" && it.kind == RemoteAccountKind.S3 }, "SK") }
    }
}

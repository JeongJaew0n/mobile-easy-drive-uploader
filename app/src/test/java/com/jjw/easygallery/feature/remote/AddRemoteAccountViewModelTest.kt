package com.jjw.easygallery.feature.remote

import com.jjw.easygallery.core.data.remote.RemoteAccountRepository
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.RemoteStorageFactory
import com.jjw.easygallery.core.data.remote.smb.DiscoveredHost
import com.jjw.easygallery.core.data.remote.smb.HostDiscovery
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

    private val discovery = HostDiscovery { flowOf(listOf(DiscoveredHost("Synology", "192.168.0.10", 445))) }

    private fun viewModel() = AddRemoteAccountViewModel(
        accounts,
        mapOf(RemoteAccountKind.S3 to factory, RemoteAccountKind.WEBDAV to factory),
        OkHttpClient(),
        testDispatcher,
        discovery,
    )

    @Test
    fun `discovering smb hosts lists them and picking one fills endpoint and name`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.setKind(RemoteAccountKind.SMB)

        vm.discoverSmbHosts()
        advanceUntilIdle()
        assertEquals(listOf("Synology"), vm.uiState.value.discoveredHosts.map { it.name })

        vm.pickDiscoveredHost(vm.uiState.value.discoveredHosts.single())
        assertEquals("192.168.0.10", vm.uiState.value.endpoint)
        assertEquals("Synology", vm.uiState.value.displayName)
        vm.pickDiscoveredHost(DiscoveredHost("x", "10.0.0.2", 4455))
        assertEquals("10.0.0.2:4455", vm.uiState.value.endpoint)
        assertEquals("Synology", vm.uiState.value.displayName) // 이미 있는 이름은 덮어쓰지 않음
    }

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

        vm.setKind(RemoteAccountKind.SMB)
        vm.update { copy(endpoint = "nas.local") }
        assertFalse(vm.uiState.value.canSubmit) // SMB 는 공유 이름 필수
        vm.update { copy(bucketOrRoot = "photo") }
        assertTrue(vm.uiState.value.canSubmit)
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

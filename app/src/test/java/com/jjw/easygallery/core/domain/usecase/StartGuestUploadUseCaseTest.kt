package com.jjw.easygallery.core.domain.usecase

import android.net.Uri
import com.jjw.easygallery.core.data.drive.DriveRepository
import com.jjw.easygallery.core.data.prefs.UserPreferences
import com.jjw.easygallery.core.data.prefs.UserPreferencesRepository
import com.jjw.easygallery.core.data.remote.GuestDrive
import com.jjw.easygallery.core.data.remote.GuestDriveFactory
import com.jjw.easygallery.core.data.remote.StorageRegistry
import com.jjw.easygallery.core.domain.model.DriveFolder
import com.jjw.easygallery.core.domain.model.MediaItem
import com.jjw.easygallery.core.domain.model.MediaType
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.ViewFolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * "다른 계정 업로드" 의 시작 절차(`docs/plans/guest-account-upload/spec.md` §3 ②~⑤).
 * 주 계정 A 를 건드리지 않는지, 공유가 올리기보다 먼저인지, 엉뚱한 곳에 올리지 않는지를 본다.
 */
@RunWith(RobolectricTestRunner::class)
class StartGuestUploadUseCaseTest {

    private val folder = DriveFolder("b-folder", "Easy Gallery")
    private val guestDrive: DriveRepository = mockk(relaxed = true) {
        coEvery { ensureAppRootFolder() } returns folder
    }
    private val guests: GuestDriveFactory = mockk { coEvery { emailOf(TOKEN) } returns B }
    private val registry: StorageRegistry = mockk {
        every { guestDrive(B) } returns GuestDrive(B, guestDrive, mockk(relaxed = true))
    }
    private val enqueue: EnqueueUploadsUseCase = mockk { coEvery { toFolder(any(), any(), any()) } returns 2 }
    private val items = listOf(item(1), item(2), item(3))

    private fun prefs(p: UserPreferences): UserPreferencesRepository = mockk(relaxed = true) {
        coEvery { current() } returns p
    }

    private fun useCase(p: UserPreferences, repo: UserPreferencesRepository = prefs(p)) =
        StartGuestUploadUseCase(repo, guests, registry, enqueue)

    @Test
    fun `uploads to B's folder under B's account id`() = runTest {
        val result = useCase(UserPreferences(accountEmail = A))(items, TOKEN)

        coVerify { enqueue.toFolder(items, RemoteAccount.guestDriveId(B), folder) }
        assertEquals(B, result.email)
        assertEquals(2, result.added)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `shares with A before anything is queued`() = runTest {
        useCase(UserPreferences(accountEmail = A))(items, TOKEN)

        // 먼저 공유해야 올라가는 대로 A 쪽에서 보인다
        coVerifyOrder {
            guestDrive.shareForReading(folder.id, A)
            enqueue.toFolder(any(), any(), any())
        }
    }

    @Test(expected = GuestIsPrimaryException::class)
    fun `picking A itself is refused`() = runTest {
        coEvery { guests.emailOf(TOKEN) } returns "A@Example.com"
        useCase(UserPreferences(accountEmail = A))(items, TOKEN)
    }

    @Test
    fun `refusing A queues nothing and shares nothing`() = runTest {
        coEvery { guests.emailOf(TOKEN) } returns A
        runCatching { useCase(UserPreferences(accountEmail = A))(items, TOKEN) }

        coVerify(exactly = 0) { guestDrive.shareForReading(any(), any()) }
        coVerify(exactly = 0) { enqueue.toFolder(any(), any(), any()) }
    }

    @Test
    fun `adds the folder to A's view folders when A opted in`() = runTest {
        val p = UserPreferences(accountEmail = A, driveViewScopeGranted = true)
        val repo = prefs(p)

        val result = useCase(p, repo)(items, TOKEN)

        assertTrue(result.addedToViewFolders)
        // 이름은 그대로 두고 소유자를 따로 적는다 — 화면이 부제로 A 의 "Easy Gallery" 와 가른다
        coVerify { repo.addViewFolder(ViewFolder(folder.id, "Easy Gallery", ownerEmail = B)) }
    }

    @Test
    fun `does not add a view folder A cannot read`() = runTest {
        val p = UserPreferences(accountEmail = A, driveViewScopeGranted = false)
        val repo = prefs(p)

        val result = useCase(p, repo)(items, TOKEN)

        assertFalse(result.addedToViewFolders)
        coVerify(exactly = 0) { repo.addViewFolder(any()) }
    }

    @Test
    fun `A's connection is never written`() = runTest {
        val p = UserPreferences(accountEmail = A, driveViewScopeGranted = true, uploadFolderId = "a-folder")
        val repo = prefs(p)

        useCase(p, repo)(items, TOKEN)

        coVerify(exactly = 0) { repo.setAccount(any(), any()) }
        coVerify(exactly = 0) { repo.clearAccount() }
        coVerify(exactly = 0) { repo.setUploadFolder(any(), any()) }
        coVerify(exactly = 0) { repo.setUploadTarget(any(), any(), any()) }
    }

    @Test
    fun `without a primary there is no one to share with`() = runTest {
        val result = useCase(UserPreferences(accountEmail = null))(items, TOKEN)

        assertFalse(result.sharedWithPrimary)
        coVerify(exactly = 0) { guestDrive.shareForReading(any(), any()) }
        coVerify { enqueue.toFolder(items, RemoteAccount.guestDriveId(B), folder) }
    }

    private fun item(id: Long) = MediaItem(
        id = id,
        uri = Uri.parse("content://media/$id"),
        displayName = "$id.jpg",
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        sizeBytes = 1,
        dateTakenMillis = 0,
        bucketId = 1,
        bucketName = "Camera",
    )

    private companion object {
        const val A = "a@example.com"
        const val B = "b@example.com"
        const val TOKEN = "b-token"
    }
}

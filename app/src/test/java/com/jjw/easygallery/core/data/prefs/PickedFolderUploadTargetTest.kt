package com.jjw.easygallery.core.data.prefs

import androidx.test.core.app.ApplicationProvider
import com.jjw.easygallery.core.domain.model.PickedFolder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 지정을 풀었는데 업로드 대상이 그대로면 볼 수 없는 곳으로 계속 올라간다.
 * 실제로 "올렸다는데 Drive 에 없다" 로 겪은 일이라 테스트로 굳힌다
 * (`docs/troubleshootings/project-specific/uploaded-but-not-visible.md`).
 */
@RunWith(RobolectricTestRunner::class)
class PickedFolderUploadTargetTest {

    private val repo = UserPreferencesRepository(ApplicationProvider.getApplicationContext())

    @Test
    fun `지정을 풀면 그 폴더로 설정돼 있던 업로드 대상도 풀린다`() = runTest {
        repo.addPickedFolder(PickedFolder("games", "GamesFolder"))
        repo.setUploadFolder("games", "GamesFolder")

        repo.removePickedFolder("games")

        val p = repo.current()
        assertEquals(emptyList<PickedFolder>(), p.pickedFolders)
        assertNull(p.uploadFolderId)
        assertNull(p.uploadFolderName)
    }

    @Test
    fun `다른 폴더가 업로드 대상이면 건드리지 않는다`() = runTest {
        repo.addPickedFolder(PickedFolder("games", "GamesFolder"))
        repo.setUploadFolder("other", "다른 폴더")

        repo.removePickedFolder("games")

        assertEquals("other", repo.current().uploadFolderId)
    }
}

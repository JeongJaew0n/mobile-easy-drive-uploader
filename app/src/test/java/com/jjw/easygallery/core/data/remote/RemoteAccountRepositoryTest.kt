package com.jjw.easygallery.core.data.remote

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jjw.easygallery.core.data.upload.db.AppDatabase
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RemoteAccountRepositoryTest {

    private lateinit var db: AppDatabase
    private val secrets = FakeSecretStore()
    private lateinit var repository: RemoteAccountRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repository = RemoteAccountRepository(db.remoteAccountDao(), secrets)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `add stores metadata in room and secret in the secret store, remove clears both`() = runTest {
        val saved = repository.add(
            RemoteAccount(
                id = "",
                kind = RemoteAccountKind.S3,
                displayName = "Naver",
                endpoint = "https://kr.object.ncloudstorage.com",
                region = "kr-standard",
                bucketOrRoot = "photos",
                username = "AKIA",
            ),
            secret = "s3cr3t",
        )

        assertTrue(saved.id.isNotBlank())
        val listed = repository.observeAccounts().first()
        assertEquals(listOf("Naver"), listed.map { it.displayName })
        assertEquals(RemoteAccountKind.S3, listed.single().kind)
        assertEquals("s3cr3t", repository.secretOf(saved))

        repository.rename(saved.id, " NCP ")
        assertEquals("NCP", repository.get(saved.id)?.displayName)

        repository.remove(saved.id)
        assertTrue(repository.observeAccounts().first().isEmpty())
        assertNull(secrets.get(saved.id))
    }

    private class FakeSecretStore : SecretStore {
        private val map = HashMap<String, String>()
        override fun put(ref: String, secret: String) {
            map[ref] = secret
        }
        override fun get(ref: String): String? = map[ref]
        override fun remove(ref: String) {
            map.remove(ref)
        }
    }
}

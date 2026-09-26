package com.jjw.easygallery.core.data.upload.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 11 → 12 를 **실제 행으로, 실제 기기와 같은 경로로** 돌린다.
 *
 * `schemas/11.json` 대로 v11 DB 파일을 만들고 거기에 행을 넣은 뒤, 앱과 똑같이
 * `Room.databaseBuilder(...).addMigrations(MIGRATION_11_12)` 로 연다. Room 은 열면서 마이그레이션을
 * 돌리고 **결과 표 모양을 엔티티와 대조한다** — 한 글자라도 다르면 여기서 예외가 난다.
 * 사용자 기기에서 앱이 켜지자마자 죽을 일을 먼저 잡는 자리다.
 *
 * `MigrationTestHelper` 를 쓰지 않는 이유: Robolectric 은 앱 변형의 자산만 읽어서
 * 테스트 소스의 `schemas/` 가 보이지 않는다. 디버그 APK 에 스키마를 싣기보다 여기서 파일로 읽는다.
 *
 * 원장은 "이미 백업됨" 의 근거라, 여기서 행이 사라지면 수천 장이 "안 올린 것" 이 되어
 * Drive 에 한 벌씩 더 올라간다.
 */
@RunWith(RobolectricTestRunner::class)
class Migration11To12Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbFile get() = context.getDatabasePath("migration-test.db")
    private var room: AppDatabase? = null

    @Before
    fun setUp() {
        dbFile.parentFile?.mkdirs()
        dbFile.delete()
    }

    @After
    fun tearDown() {
        room?.close()
        dbFile.delete()
    }

    /** schemas/11.json 대로 표·인덱스·room_master_table 을 만들고 user_version 을 11 로 둔다 */
    private fun createV11(insert: SQLiteDatabase.() -> Unit) {
        val schema = JSONObject(File(SCHEMA_DIR, "11.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = 11
            db.insert()
        }
    }

    /** 앱과 같은 방식으로 연다 — 여기서 마이그레이션과 스키마 대조가 일어난다 */
    private fun openMigrated(): UploadedMediaDao {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbFile.absolutePath)
            .addMigrations(MIGRATION_11_12)
            .allowMainThreadQueries()
            .build()
        room = db
        return db.uploadedMediaDao()
    }

    @Test
    fun `drive rows become unclaimed and other storages keep their account`() = runTest {
        createV11 {
            execSQL(
                "INSERT INTO uploaded_media (mediaId, driveFileId, folderId, uploadedAt, accountId) VALUES " +
                    "(1, 'drive-a', 'f1', 100, NULL), " +
                    "(2, 'drive-b', 'f1', 200, NULL), " +
                    "(3, 's3-key', 'bucket/', 300, 'acct-s3')",
            )
        }
        val dao = openMigrated()

        assertEquals(listOf(1L, 2L), dao.observeUploadedIdsAt(UNCLAIMED_DRIVE_DESTINATION).first().sorted())
        assertEquals(listOf(3L), dao.observeUploadedIdsAt("remote:acct-s3").first())
        assertEquals(listOf("s3-key"), dao.observeRemoteIdsAt("remote:acct-s3").first())
    }

    @Test
    fun `no row is lost`() = runTest {
        createV11 {
            for (i in 1..500) {
                val account = if (i % 3 == 0) "'acct'" else "NULL"
                execSQL(
                    "INSERT INTO uploaded_media (mediaId, driveFileId, folderId, uploadedAt, accountId) " +
                        "VALUES ($i, 'f$i', NULL, $i, $account)",
                )
            }
        }
        val dao = openMigrated()

        assertEquals(500, dao.count())
        assertEquals(166, dao.observeUploadedIdsAt("remote:acct").first().size)
        assertEquals(334, dao.observeUploadedIdsAt(UNCLAIMED_DRIVE_DESTINATION).first().size)
    }

    @Test
    fun `claiming after migration hands legacy rows to the connected account`() = runTest {
        createV11 {
            execSQL(
                "INSERT INTO uploaded_media (mediaId, driveFileId, folderId, uploadedAt, accountId) " +
                    "VALUES (1, 'd1', NULL, 1, NULL), (2, 'd2', NULL, 2, NULL)",
            )
        }
        val dao = openMigrated()

        dao.claimUnclaimed("drive:me@example.com")

        assertEquals(listOf(1L, 2L), dao.observeUploadedIdsAt("drive:me@example.com").first().sorted())
    }

    @Test
    fun `after migration the same photo can be recorded in two places`() = runTest {
        createV11 {
            execSQL(
                "INSERT INTO uploaded_media (mediaId, driveFileId, folderId, uploadedAt, accountId) " +
                    "VALUES (7, 'drive-7', NULL, 1, NULL)",
            )
        }
        val dao = openMigrated()
        // 스키마 11 에서는 이 기록이 앞 행을 덮어썼다(키가 mediaId 하나)
        dao.upsert(UploadedMediaEntity(7, "remote:acct", "s3-7", null, 2, "acct"))

        assertEquals(2, dao.count())
    }

    @Test
    fun `other tables survive untouched`() = runTest {
        createV11 {
            execSQL(
                "INSERT INTO upload_tasks (mediaId, uri, displayName, mimeType, sizeBytes, state, " +
                    "bytesUploaded, attemptCount, createdAt, updatedAt, width, height) " +
                    "VALUES (9, 'content://x', 'a.jpg', 'image/jpeg', 10, 'PENDING', 0, 0, 1, 1, 0, 0)",
            )
        }
        openMigrated()

        assertEquals(1, room!!.uploadTaskDao().countUnfinishedNow())
    }

    private companion object {
        // 단위 테스트는 모듈 폴더(app/)에서 돈다
        const val SCHEMA_DIR = "schemas/com.jjw.easygallery.core.data.upload.db.AppDatabase"
    }
}

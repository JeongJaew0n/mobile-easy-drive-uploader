package com.jjw.easygallery.core.data.upload.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * 11 → 12: 원장(`uploaded_media`)의 키를 `mediaId` → `(mediaId, destination)` 으로.
 *
 * AutoMigration 으로 못 하는 이유 — 기본 키가 바뀌고, 새 칸 `destination` 의 값을
 * **기존 `accountId` 에서 계산**해야 한다. AutoMigration 은 고정 기본값밖에 못 넣는다.
 *
 * Drive 행(`accountId IS NULL`)은 누가 올렸는지 모르므로 주인 미정(`drive:`)으로 옮기고,
 * 앱이 처음 연결 계정을 알게 되는 순간 그 계정에게 준다(`UploadLedgerRepository.claimLegacy`).
 * 마이그레이션은 DataStore 를 읽을 수 없어서 두 단계로 나눴다
 * (`docs/plans/ledger-per-account/spec.md` §2.3).
 *
 * `CREATE TABLE`·`CREATE INDEX` 는 `schemas/.../12.json` 과 **글자 그대로** 같아야 한다.
 * Room 이 열 때 표 모양을 대조해서, 다르면 앱이 켜지자마자 죽는다.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `uploaded_media_new` (`mediaId` INTEGER NOT NULL, " +
                "`destination` TEXT NOT NULL, `driveFileId` TEXT NOT NULL, `folderId` TEXT, " +
                "`uploadedAt` INTEGER NOT NULL, `accountId` TEXT, PRIMARY KEY(`mediaId`, `destination`))",
        )
        connection.execSQL(
            "INSERT INTO `uploaded_media_new` (mediaId, destination, driveFileId, folderId, uploadedAt, accountId) " +
                "SELECT mediaId, " +
                "CASE WHEN accountId IS NULL THEN '$UNCLAIMED_DRIVE_DESTINATION' ELSE 'remote:' || accountId END, " +
                "driveFileId, folderId, uploadedAt, accountId FROM `uploaded_media`",
        )
        connection.execSQL("DROP TABLE `uploaded_media`")
        connection.execSQL("ALTER TABLE `uploaded_media_new` RENAME TO `uploaded_media`")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_uploaded_media_destination` ON `uploaded_media` (`destination`)",
        )
    }
}

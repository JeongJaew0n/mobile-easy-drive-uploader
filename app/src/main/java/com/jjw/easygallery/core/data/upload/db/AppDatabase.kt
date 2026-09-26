package com.jjw.easygallery.core.data.upload.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        UploadTaskEntity::class,
        UploadedMediaEntity::class,
        MediaHashEntity::class,
        CategoryEntity::class,
        MediaCategoryEntity::class,
        RemoteAccountEntity::class,
        AutoTagEntity::class,
        AutoTagScanEntity::class,
        HiddenMediaEntity::class,
    ],
    version = 11,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        // 11: upload_tasks.errorReason — 실패 사유를 코드로 남겨 화면이 문장을 고른다
        AutoMigration(from = 10, to = 11),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadTaskDao(): UploadTaskDao
    abstract fun uploadedMediaDao(): UploadedMediaDao
    abstract fun mediaHashDao(): MediaHashDao
    abstract fun categoryDao(): CategoryDao
    abstract fun remoteAccountDao(): RemoteAccountDao
    abstract fun autoTagDao(): AutoTagDao

    abstract fun hiddenMediaDao(): HiddenMediaDao

    companion object {
        const val NAME = "easy_gallery.db"
    }
}

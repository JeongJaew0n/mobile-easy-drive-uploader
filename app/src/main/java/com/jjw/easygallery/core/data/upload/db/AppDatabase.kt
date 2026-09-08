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
    ],
    version = 5,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadTaskDao(): UploadTaskDao
    abstract fun uploadedMediaDao(): UploadedMediaDao
    abstract fun mediaHashDao(): MediaHashDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        const val NAME = "easy_gallery.db"
    }
}

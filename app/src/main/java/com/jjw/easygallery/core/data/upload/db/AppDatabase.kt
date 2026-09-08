package com.jjw.easygallery.core.data.upload.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [UploadTaskEntity::class, UploadedMediaEntity::class, MediaHashEntity::class],
    version = 4,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadTaskDao(): UploadTaskDao
    abstract fun uploadedMediaDao(): UploadedMediaDao
    abstract fun mediaHashDao(): MediaHashDao

    companion object {
        const val NAME = "easy_gallery.db"
    }
}

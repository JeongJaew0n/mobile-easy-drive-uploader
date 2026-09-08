package com.jjw.easygallery.core.data.upload.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [UploadTaskEntity::class, UploadedMediaEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadTaskDao(): UploadTaskDao
    abstract fun uploadedMediaDao(): UploadedMediaDao

    companion object {
        const val NAME = "easy_gallery.db"
    }
}

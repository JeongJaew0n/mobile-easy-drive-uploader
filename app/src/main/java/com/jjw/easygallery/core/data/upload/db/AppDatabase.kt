package com.jjw.easygallery.core.data.upload.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [UploadTaskEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun uploadTaskDao(): UploadTaskDao

    companion object {
        const val NAME = "easy_gallery.db"
    }
}

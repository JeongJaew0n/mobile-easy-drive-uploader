package com.jjw.easygallery.core.data.upload.db

import android.content.Context
import androidx.room.Room
import com.jjw.easygallery.core.data.upload.Media3VideoCompressor
import com.jjw.easygallery.core.data.upload.VideoCompressor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun providesDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun providesUploadTaskDao(db: AppDatabase): UploadTaskDao = db.uploadTaskDao()

    @Provides
    fun providesUploadedMediaDao(db: AppDatabase): UploadedMediaDao = db.uploadedMediaDao()

    @Provides
    fun providesMediaHashDao(db: AppDatabase): MediaHashDao = db.mediaHashDao()

    @Provides
    fun providesCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun providesRemoteAccountDao(db: AppDatabase): RemoteAccountDao = db.remoteAccountDao()

    @Provides
    @Singleton
    fun providesVideoCompressor(impl: Media3VideoCompressor): VideoCompressor = impl
}

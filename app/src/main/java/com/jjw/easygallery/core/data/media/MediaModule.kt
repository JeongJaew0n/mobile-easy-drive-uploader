package com.jjw.easygallery.core.data.media

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaModule {

    @Binds
    abstract fun bindsMediaRepository(impl: MediaStoreRepository): MediaRepository
}

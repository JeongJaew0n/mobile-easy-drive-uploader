package com.jjw.easygallery.core.data.autotag

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AutoTagModule {

    @Binds
    abstract fun bindsImageLabeler(impl: MlKitImageLabeler): ImageLabeler
}

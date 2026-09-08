package com.jjw.easygallery.core.data.category

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class CategoryModule {

    @Binds
    abstract fun bindsCategoryRepository(impl: RoomCategoryRepository): CategoryRepository
}

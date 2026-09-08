package com.jjw.easygallery

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.video.VideoFrameDecoder
import com.jjw.easygallery.core.ui.image.MediaStoreThumbnailFetcher
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class EasyGalleryApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    // WorkManager가 Hilt로 주입되는 Worker를 생성할 수 있도록 팩토리를 넘긴다.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR)
            .build()

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                // MediaStore URI 는 시스템 썸네일 우선, 그 외 영상은 프레임 디코더
                add(MediaStoreThumbnailFetcher.Factory())
                add(VideoFrameDecoder.Factory())
            }
            // 전역 crossfade 는 쓰지 않는다 — 시작 시 썸네일이 조각조각 페이드인해 화면이 '천천히 켜지는' 느낌을 준다
            // (ANIMATION_IMPROVEMENT.md §10)
            .build()
}

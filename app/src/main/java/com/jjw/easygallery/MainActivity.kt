package com.jjw.easygallery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.jjw.easygallery.core.domain.usecase.ManageUploadQueueUseCase
import com.jjw.easygallery.core.navigation.AppNavigation
import com.jjw.easygallery.core.ui.theme.EasyGalleryTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var manageUploadQueue: ManageUploadQueueUseCase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 이전 세션에서 남은 업로드가 있으면 워커가 예약되어 있도록 보장
        lifecycleScope.launch { manageUploadQueue.ensureScheduled() }
        setContent {
            EasyGalleryTheme {
                AppNavigation()
            }
        }
    }
}

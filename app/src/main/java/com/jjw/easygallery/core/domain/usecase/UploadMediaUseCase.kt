package com.jjw.easygallery.core.domain.usecase

import com.jjw.easygallery.core.data.upload.DriveUploader
import com.jjw.easygallery.core.data.upload.UploadEvent
import com.jjw.easygallery.core.domain.model.MediaItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import javax.inject.Inject

class UploadMediaUseCase @Inject constructor(
    private val getUploadFolder: GetUploadFolderUseCase,
    private val uploader: DriveUploader,
) {
    operator fun invoke(item: MediaItem): Flow<UploadEvent> = flow {
        val folder = getUploadFolder()
        emitAll(uploader.upload(item, folder.id))
    }
}

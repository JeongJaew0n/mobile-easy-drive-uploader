package com.jjw.easygallery.core.data.duplicates

import android.content.Context
import android.net.Uri
import com.jjw.easygallery.core.common.di.AppDispatcher
import com.jjw.easygallery.core.common.di.Dispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.security.MessageDigest
import javax.inject.Inject

/** content URI 의 전체 바이트를 스트리밍으로 읽어 SHA-256 을 낸다. 취소 가능. */
class MediaHasher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:Dispatcher(AppDispatcher.IO) private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun sha256(uri: Uri): String = withContext(ioDispatcher) {
        val input = context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        input.use { stream ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val BUFFER_SIZE = 256 * 1024
    }
}

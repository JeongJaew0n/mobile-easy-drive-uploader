package com.jjw.easygallery.core.data.remote.smb

import android.content.Context
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.EnumWithValue.EnumUtils
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.io.ByteChunkProvider
import com.hierynomus.smbj.share.DiskShare
import com.jjw.easygallery.core.data.remote.RemoteEntry
import com.jjw.easygallery.core.data.remote.RemoteFolder
import com.jjw.easygallery.core.data.remote.RemoteNames
import com.jjw.easygallery.core.data.remote.RemotePage
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.data.remote.RemoteUploader
import com.jjw.easygallery.core.data.remote.UnsupportedOperationException
import com.jjw.easygallery.core.data.upload.SessionStatus
import com.jjw.easygallery.core.data.upload.UploadEvent
import com.jjw.easygallery.core.data.upload.UploadSource
import com.jjw.easygallery.core.domain.model.Capability
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URLConnection
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 공유 폴더(NAS·Windows). `docs/NAS_STORAGE.md` §5.
 * - 계정: endpoint = `host[:port]`, bucketOrRoot = 공유 이름, region = 도메인(선택), username/secret
 * - 연결은 작업마다 열고 닫는다(절전·네트워크 전환에 강하고 워커 밖에서 소켓을 들고 있지 않는다)
 * - 업로드는 오프셋 쓰기가 가능해 원격 파일 크기에서 이어 올린다([Capability.RESUMABLE_UPLOAD])
 */
class SmbStorage(
    private val context: Context,
    override val account: RemoteAccount,
    private val password: String,
    private val ioDispatcher: CoroutineDispatcher,
) : RemoteStorage {

    private val shareName = requireNotNull(account.bucketOrRoot) { "SMB 계정에 공유 이름이 없습니다" }
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .withSoTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build(),
    )

    override val capabilities: Set<Capability> =
        setOf(
            Capability.DOWNLOAD,
            Capability.RENAME,
            Capability.MOVE,
            Capability.FOLDER_MUTATION,
            Capability.RESUMABLE_UPLOAD,
        )

    override val rootId: String get() = ""

    override suspend fun about(): RemoteAccountInfo =
        RemoteAccountInfo(account.displayName, "smb://${account.endpoint}/$shareName")

    override suspend fun listChildren(parentId: String, pageToken: String?, foldersOnly: Boolean): RemotePage =
        withShare { share ->
            val entries = share.list(SmbPaths.toSmb(parentId))
                .filter { it.fileName != "." && it.fileName != ".." }
                .filter { !foldersOnly || it.isDirectory() }
                .map { it.toEntry(parentId) }
                .sortedWith(compareBy<RemoteEntry> { !it.isFolder }.thenBy { it.name.lowercase() })
            RemotePage(entries, nextPageToken = null)
        }

    override suspend fun createFolder(name: String, parentId: String): RemoteFolder = withShare { share ->
        val id = SmbPaths.child(parentId, name, isFolder = true)
        share.mkdir(SmbPaths.toSmb(id))
        RemoteFolder(id, name.trim())
    }

    override suspend fun rename(entryId: String, name: String): RemoteEntry =
        moveTo(entryId, SmbPaths.child(SmbPaths.parentOf(entryId), name, entryId.endsWith("/")))

    override suspend fun move(entryId: String, fromParentId: String, toParentId: String): RemoteEntry =
        moveTo(entryId, SmbPaths.child(toParentId, SmbPaths.nameOf(entryId), entryId.endsWith("/")))

    override suspend fun delete(entryId: String) = withShare { share ->
        val path = SmbPaths.toSmb(entryId)
        if (entryId.endsWith("/")) share.rmdir(path, true) else share.rm(path)
    }

    override suspend fun restore(entryId: String) = throw UnsupportedOperationException("SMB 에는 휴지통이 없습니다")

    override fun uploader(): RemoteUploader = SmbUploader()

    /** 읽는 동안 연결을 유지해야 하므로 [withShare] 대신 직접 열고, 스트림을 닫을 때 함께 정리한다 */
    override suspend fun openDownload(entryId: String): InputStream = withContext(ioDispatcher) {
        val (host, port) = SmbPaths.hostPort(account.endpoint)
        val connection = client.connect(host, port)
        try {
            val session = connection.authenticate(
                AuthenticationContext(account.username.orEmpty(), password.toCharArray(), account.region.orEmpty()),
            )
            val share = session.connectShare(shareName) as DiskShare
            val file = share.openFile(
                SmbPaths.toSmb(entryId),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SHARE_ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
            object : FilterInputStream(file.inputStream) {
                override fun close() {
                    runCatching { super.close() }
                    runCatching { file.close() }
                    runCatching { share.close() }
                    runCatching { session.close() }
                    runCatching { connection.close() }
                }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            runCatching { connection.close() }
            throw e.toStorageException()
        }
    }

    private suspend fun moveTo(entryId: String, targetId: String): RemoteEntry = withShare { share ->
        val from = SmbPaths.toSmb(entryId)
        val to = SmbPaths.toSmb(targetId)
        if (entryId.endsWith("/")) {
            share.openDirectory(from, RENAME_ACCESS, null, SHARE_ALL, SMB2CreateDisposition.FILE_OPEN, null)
                .use { it.rename(to) }
        } else {
            share.openFile(from, RENAME_ACCESS, null, SHARE_ALL, SMB2CreateDisposition.FILE_OPEN, null)
                .use { it.rename(to) }
        }
        val name = SmbPaths.nameOf(targetId)
        RemoteEntry(
            id = targetId,
            name = name,
            mimeType = if (targetId.endsWith("/")) RemoteEntry.FOLDER_MIME_TYPE else guessMimeType(name),
            sizeBytes = null,
            modifiedTimeMillis = System.currentTimeMillis(),
            webViewLink = null,
        )
    }

    /** 연결 → 인증 → 공유 접속 → [block] → 정리. 실패는 [RemoteStorageException] 으로 */
    private suspend fun <T> withShare(block: (DiskShare) -> T): T = withContext(ioDispatcher) {
        val (host, port) = SmbPaths.hostPort(account.endpoint)
        try {
            client.connect(host, port).use { connection ->
                // 도메인 null 은 smbj NTLM 경로에서 NPE 가 날 수 있어 빈 문자열(작업 그룹 없음)로
                val auth = AuthenticationContext(
                    account.username.orEmpty(),
                    password.toCharArray(),
                    account.region.orEmpty(),
                )
                connection.authenticate(auth).use { session ->
                    (session.connectShare(shareName) as DiskShare).use(block)
                }
            }
        } catch (e: RemoteStorageException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // smbj 는 SMBApiException(상태 코드)·SocketException 등 다양한 예외를 던진다 → 하나로 감싼다
            throw e.toStorageException()
        }
    }

    /**
     * 다시 해도 결과가 같은 오류(자격 증명·공유 이름·권한)는 4xx 로 표시해 업로드 워커가 **즉시** 포기하게 한다.
     * 나머지(연결 끊김·타임아웃)는 코드 없이 감싸 재시도 대상으로 남긴다.
     */
    private fun Exception.toStorageException(): RemoteStorageException {
        val message = "SMB 오류: ${message ?: javaClass.simpleName}"
        val httpCode = (this as? SMBApiException)?.status?.let { status ->
            when (status) {
                NtStatus.STATUS_LOGON_FAILURE,
                NtStatus.STATUS_ACCOUNT_DISABLED,
                NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
                NtStatus.STATUS_PASSWORD_EXPIRED,
                NtStatus.STATUS_ACCESS_DENIED,
                -> HTTP_UNAUTHORIZED

                NtStatus.STATUS_BAD_NETWORK_NAME,
                NtStatus.STATUS_BAD_NETWORK_PATH,
                NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
                NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
                -> HTTP_NOT_FOUND

                else -> null
            }
        }
        return RemoteStorageException(message, httpCode = httpCode, cause = this)
    }

    private fun FileIdBothDirectoryInformation.isDirectory(): Boolean =
        EnumUtils.isSet(fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)

    private fun FileIdBothDirectoryInformation.toEntry(parentId: String): RemoteEntry {
        val folder = isDirectory()
        return RemoteEntry(
            id = SmbPaths.child(parentId, fileName, folder),
            name = fileName,
            mimeType = if (folder) RemoteEntry.FOLDER_MIME_TYPE else guessMimeType(fileName),
            sizeBytes = if (folder) null else endOfFile,
            modifiedTimeMillis = changeTime?.toEpochMillis(),
            webViewLink = null,
        )
    }

    /** 원격 파일 크기를 읽어 그 오프셋부터 이어 쓴다 */
    private inner class SmbUploader : RemoteUploader {
        override suspend fun resolveLength(source: UploadSource): Long = withContext(ioDispatcher) {
            runCatching { context.contentResolver.openAssetFileDescriptor(source.uri, "r")?.use { it.length } }
                .getOrNull()?.takeIf { it >= 0 } ?: source.sizeBytes
        }

        override suspend fun startSession(source: UploadSource, folderId: String, length: Long): String =
            withShare { share ->
                val name = RemoteNames.uniqueBlocking(source.displayName) { candidate ->
                    share.fileExists(SmbPaths.toSmb(SmbPaths.child(folderId, candidate, isFolder = false)))
                }
                SmbPaths.child(folderId, name, isFolder = false)
            }

        override suspend fun queryStatus(sessionUri: String, length: Long): SessionStatus = withShare { share ->
            val path = SmbPaths.toSmb(sessionUri)
            if (!share.fileExists(path)) return@withShare SessionStatus.Expired
            val size = share.getFileInformation(path).standardInformation.endOfFile
            when {
                size == length -> SessionStatus.Complete(sessionUri)
                size < length -> SessionStatus.Incomplete(size)
                else -> SessionStatus.Expired
            }
        }

        override fun upload(source: UploadSource, sessionUri: String, offset: Long, length: Long): Flow<UploadEvent> =
            channelFlow {
                send(UploadEvent.Progress(offset, length))
                withShare { share ->
                    val disposition =
                        if (offset > 0) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OVERWRITE_IF
                    val file = share.openFile(
                        SmbPaths.toSmb(sessionUri),
                        EnumSet.of(AccessMask.GENERIC_WRITE),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        SHARE_ALL,
                        disposition,
                        EnumSet.noneOf(SMB2CreateOptions::class.java),
                    )
                    file.use {
                        val input = context.contentResolver.openInputStream(source.uri)
                            ?: throw FileNotFoundException(source.uri.toString())
                        input.use { stream ->
                            val provider = ContentChunkProvider(stream, offset, length) { sent ->
                                trySend(UploadEvent.Progress(sent, length))
                            }
                            it.write(provider)
                        }
                    }
                }
                Timber.d("uploaded %s -> smb://%s/%s/%s", source.displayName, account.endpoint, shareName, sessionUri)
                send(UploadEvent.Completed(sessionUri))
            }.flowOn(ioDispatcher)
    }

    /** content:// 스트림을 [startOffset] 부터 [total] 까지 SMB 쓰기 청크로 넘긴다 */
    private class ContentChunkProvider(
        private val input: InputStream,
        startOffset: Long,
        private val total: Long,
        private val onProgress: (Long) -> Unit,
    ) : ByteChunkProvider() {
        init {
            offset = startOffset
            var skipped = 0L
            while (skipped < startOffset) {
                val n = input.skip(startOffset - skipped)
                if (n <= 0) break
                skipped += n
            }
        }

        override fun prepareWrite(maxBytesToPrepare: Int) = Unit

        override fun isAvailable(): Boolean = offset < total

        override fun bytesLeft(): Int = (total - offset).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        override fun getChunk(chunk: ByteArray): Int {
            val want = minOf(chunk.size.toLong(), total - offset).toInt()
            if (want <= 0) return -1
            var read = 0
            while (read < want) {
                val n = input.read(chunk, read, want - read)
                if (n < 0) break
                read += n
            }
            onProgress(offset + read)
            return if (read == 0) -1 else read
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        val SHARE_ALL: EnumSet<SMB2ShareAccess> =
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE)
        val RENAME_ACCESS: EnumSet<AccessMask> = EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ)

        fun guessMimeType(name: String): String =
            URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }
}

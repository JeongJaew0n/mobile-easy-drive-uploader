package com.jjw.easygallery.core.data.remote.sftp

import android.content.Context
import com.jjw.easygallery.core.data.remote.RemoteEntry
import com.jjw.easygallery.core.data.remote.RemoteFolder
import com.jjw.easygallery.core.data.remote.RemoteNames
import com.jjw.easygallery.core.data.remote.RemotePage
import com.jjw.easygallery.core.data.remote.RemotePaths
import com.jjw.easygallery.core.data.remote.RemoteStorage
import com.jjw.easygallery.core.data.remote.RemoteStorageException
import com.jjw.easygallery.core.data.remote.RemoteUploader
import com.jjw.easygallery.core.data.remote.UnsupportedOperationException
import com.jjw.easygallery.core.data.remote.smb.skipExactly
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
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URLConnection
import java.util.EnumSet

/**
 * SFTP(SSH File Transfer Protocol). `docs/NAS_STORAGE.md` §7.
 * - 계정: endpoint = `host[:port]`(기본 22), bucketOrRoot = 루트 경로(비우면 `/`), username/secret, certSha256 = 호스트 키 지문
 * - 연결은 작업마다 열고 닫는다(SMB 와 같은 이유). 호스트 키는 저장된 지문과 정확히 일치할 때만 신뢰
 * - `RemoteFile.write(offset, ...)` 로 임의 오프셋 쓰기가 되므로 재개 업로드 지원
 */
class SftpStorage(
    private val context: Context,
    override val account: RemoteAccount,
    private val password: String,
    private val ioDispatcher: CoroutineDispatcher,
) : RemoteStorage {

    private val root = account.bucketOrRoot?.trim()?.trimEnd('/').orEmpty()

    override val capabilities: Set<Capability> = setOf(
        Capability.DOWNLOAD,
        Capability.RENAME,
        Capability.MOVE,
        Capability.FOLDER_MUTATION,
        Capability.RESUMABLE_UPLOAD,
    )

    override val rootId: String get() = ""

    override suspend fun about(): RemoteAccountInfo =
        RemoteAccountInfo(account.displayName, "sftp://${account.endpoint}${root.ifEmpty { "/" }}")

    override suspend fun listChildren(parentId: String, pageToken: String?, foldersOnly: Boolean): RemotePage =
        withSftp { sftp ->
            val entries = sftp.ls(absolute(parentId))
                .filter { !foldersOnly || it.isDirectory }
                .map { it.toEntry(parentId) }
                .sortedWith(compareBy<RemoteEntry> { !it.isFolder }.thenBy { it.name.lowercase() })
            RemotePage(entries, nextPageToken = null)
        }

    override suspend fun createFolder(name: String, parentId: String): RemoteFolder = withSftp { sftp ->
        val id = RemotePaths.child(parentId, name, isFolder = true)
        sftp.mkdir(absolute(id))
        RemoteFolder(id, name.trim())
    }

    override suspend fun rename(entryId: String, name: String): RemoteEntry =
        moveTo(entryId, RemotePaths.child(RemotePaths.parentOf(entryId), name, entryId.endsWith("/")))

    override suspend fun move(entryId: String, fromParentId: String, toParentId: String): RemoteEntry =
        moveTo(entryId, RemotePaths.child(toParentId, RemotePaths.nameOf(entryId), entryId.endsWith("/")))

    override suspend fun delete(entryId: String) = withSftp { sftp ->
        if (entryId.endsWith("/")) deleteTree(sftp, entryId) else sftp.rm(absolute(entryId))
    }

    override suspend fun restore(entryId: String) = throw UnsupportedOperationException("SFTP 에는 휴지통이 없습니다")

    override suspend fun openDownload(entryId: String): InputStream = withContext(ioDispatcher) {
        val ssh = connect()
        try {
            val sftp = ssh.newSFTPClient()
            val file = sftp.open(absolute(entryId), EnumSet.of(OpenMode.READ))
            SftpInputStream(file) {
                runCatching { sftp.close() }
                runCatching { ssh.disconnect() }
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            runCatching { ssh.disconnect() }
            throw asStorageException(e)
        }
    }

    override fun uploader(): RemoteUploader = SftpUploader()

    /** 폴더는 비어 있어야 지워지므로 아래부터 훑어 지운다 */
    private fun deleteTree(sftp: SFTPClient, folderId: String) {
        sftp.ls(absolute(folderId)).forEach { child ->
            val childId = RemotePaths.child(folderId, child.name, child.isDirectory)
            if (child.isDirectory) deleteTree(sftp, childId) else sftp.rm(absolute(childId))
        }
        sftp.rmdir(absolute(folderId))
    }

    private suspend fun moveTo(entryId: String, targetId: String): RemoteEntry = withSftp { sftp ->
        sftp.rename(absolute(entryId), absolute(targetId))
        val name = RemotePaths.nameOf(targetId)
        RemoteEntry(
            id = targetId,
            name = name,
            mimeType = if (targetId.endsWith("/")) RemoteEntry.FOLDER_MIME_TYPE else guessMimeType(name),
            sizeBytes = null,
            modifiedTimeMillis = System.currentTimeMillis(),
            webViewLink = null,
        )
    }

    private fun absolute(entryId: String): String = SftpPaths.absolute(root, entryId)

    private suspend fun <T> withSftp(block: (SFTPClient) -> T): T = withContext(ioDispatcher) {
        val ssh = connect()
        try {
            ssh.newSFTPClient().use(block)
        } catch (e: RemoteStorageException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw asStorageException(e)
        } finally {
            runCatching { ssh.disconnect() }
        }
    }

    /** 연결 + 호스트 키 확인 + 비밀번호 인증 */
    private fun connect(): SSHClient {
        val (host, port) = RemotePaths.hostPort(account.endpoint, DEFAULT_PORT)
        val ssh = SSHClient()
        ssh.connectTimeout = TIMEOUT_MILLIS
        ssh.timeout = TIMEOUT_MILLIS
        ssh.addHostKeyVerifier(PinnedHostKeyVerifier(account.certSha256))
        try {
            ssh.connect(host, port)
            ssh.authPassword(account.username.orEmpty(), password)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            runCatching { ssh.disconnect() }
            throw asStorageException(e)
        }
        return ssh
    }

    /**
     * 인증 실패·권한 오류는 다시 해도 같으므로 4xx 로 표시해 업로드 워커가 즉시 포기하게 한다.
     * 호스트 키 불일치도 여기 포함된다(사용자가 다시 신뢰해야 한다).
     */
    private fun asStorageException(e: Exception): RemoteStorageException {
        val permanent = e is UserAuthException || e is TransportException
        return RemoteStorageException(
            "SFTP 오류: ${e.message ?: e.javaClass.simpleName}",
            httpCode = if (permanent) HTTP_UNAUTHORIZED else null,
            cause = e,
        )
    }

    private fun RemoteResourceInfo.toEntry(parentId: String): RemoteEntry {
        val folder = isDirectory
        return RemoteEntry(
            id = RemotePaths.child(parentId, name, folder),
            name = name,
            mimeType = if (folder) RemoteEntry.FOLDER_MIME_TYPE else guessMimeType(name),
            sizeBytes = if (folder) null else attributes.size,
            modifiedTimeMillis = attributes.mtime.takeIf { it > 0 }?.times(MILLIS_PER_SECOND),
            webViewLink = null,
        )
    }

    /** 원격 파일 크기를 읽어 그 오프셋부터 이어 쓴다 */
    private inner class SftpUploader : RemoteUploader {

        override suspend fun resolveLength(source: UploadSource): Long = withContext(ioDispatcher) {
            runCatching { context.contentResolver.openAssetFileDescriptor(source.uri, "r")?.use { it.length } }
                .getOrNull()?.takeIf { it >= 0 } ?: source.sizeBytes
        }

        override suspend fun startSession(source: UploadSource, folderId: String, length: Long): String =
            withSftp { sftp ->
                val name = RemoteNames.uniqueBlocking(source.displayName) { candidate ->
                    exists(sftp, RemotePaths.child(folderId, candidate, isFolder = false))
                }
                RemotePaths.child(folderId, name, isFolder = false)
            }

        override suspend fun queryStatus(sessionUri: String, length: Long): SessionStatus = withSftp { sftp ->
            val size = runCatching { sftp.stat(absolute(sessionUri)).size }.getOrNull()
                ?: return@withSftp SessionStatus.Expired
            when {
                size == length -> SessionStatus.Complete(sessionUri)
                size < length -> SessionStatus.Incomplete(size)
                else -> SessionStatus.Expired
            }
        }

        /** 영구 실패로 버릴 때 반쯤 올라간 파일을 지운다 — 남겨 두면 다시 올릴 때 " (1)" 사본이 생긴다 */
        override suspend fun abort(sessionUri: String) {
            runCatching { withSftp { sftp -> sftp.rm(absolute(sessionUri)) } }
                .onFailure { Timber.i(it, "abort 실패(무시): %s", sessionUri) }
        }

        override fun upload(source: UploadSource, sessionUri: String, offset: Long, length: Long): Flow<UploadEvent> =
            channelFlow {
                send(UploadEvent.Progress(offset, length))
                withSftp { sftp ->
                    val modes = EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
                    if (offset == 0L) modes.add(OpenMode.TRUNC)
                    val written = sftp.open(absolute(sessionUri), modes).use { file ->
                        val input = context.contentResolver.openInputStream(source.uri)
                            ?: throw FileNotFoundException(source.uri.toString())
                        input.use { stream ->
                            stream.skipExactly(offset)
                            val buffer = ByteArray(CHUNK_BYTES)
                            var position = offset
                            while (position < length) {
                                // 선언된 길이를 넘겨 쓰지 않는다 — 넘치면 재개 판정(stat 크기)이 어긋난다
                                val want = minOf(buffer.size.toLong(), length - position).toInt()
                                val read = stream.read(buffer, 0, want)
                                if (read <= 0) break
                                file.write(position, buffer, 0, read)
                                position += read
                                trySend(UploadEvent.Progress(position, length))
                            }
                            position
                        }
                    }
                    // 원본이 예상보다 짧으면 잘린 파일이 "완료" 로 기록된다 — 그 전에 막는다
                    if (written != length) {
                        throw IOException("업로드한 크기가 다릅니다: $written / $length")
                    }
                }
                Timber.d("uploaded %s -> sftp://%s/%s", source.displayName, account.endpoint, sessionUri)
                send(UploadEvent.Completed(sessionUri))
            }.flowOn(ioDispatcher)

        private fun exists(sftp: SFTPClient, entryId: String): Boolean =
            runCatching { sftp.statExistence(absolute(entryId)) != null }.getOrDefault(false)
    }

    /** sshj 의 내부 스트림 대신 오프셋 읽기로 감싼다 — 닫을 때 세션까지 정리한다 */
    private class SftpInputStream(
        private val file: net.schmizz.sshj.sftp.RemoteFile,
        private val onClose: () -> Unit,
    ) : InputStream() {

        private var position = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val read = file.read(position, b, off, len)
            if (read < 0) return -1
            position += read
            return read
        }

        override fun close() {
            runCatching { file.close() }
            onClose()
        }

        private companion object {
            const val BYTE_MASK = 0xFF
        }
    }

    private companion object {
        const val DEFAULT_PORT = 22
        const val TIMEOUT_MILLIS = 30_000
        const val HTTP_UNAUTHORIZED = 401
        const val MILLIS_PER_SECOND = 1_000L
        const val CHUNK_BYTES = 64 * 1024

        fun guessMimeType(name: String): String =
            URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
    }
}

package com.jjw.easygallery.core.data.remote

import com.jjw.easygallery.core.data.upload.db.RemoteAccountDao
import com.jjw.easygallery.core.data.upload.db.RemoteAccountEntity
import com.jjw.easygallery.core.domain.model.RemoteAccount
import com.jjw.easygallery.core.domain.model.RemoteAccountKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 계정 메타데이터는 Room, 비밀은 [SecretStore]. 둘을 같은 id 로 묶는다 */
@Singleton
class RemoteAccountRepository @Inject constructor(
    private val dao: RemoteAccountDao,
    private val secrets: SecretStore,
) {
    fun observeAccounts(): Flow<List<RemoteAccount>> = dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun get(id: String): RemoteAccount? = dao.getById(id)?.toDomain()

    fun secretOf(account: RemoteAccount): String? = secrets.get(account.id)

    /** 새 계정 저장. [secret] 은 Keystore 로 암호화된다 */
    suspend fun add(account: RemoteAccount, secret: String): RemoteAccount {
        val id = account.id.ifBlank { UUID.randomUUID().toString() }
        val saved = account.copy(id = id, createdAt = System.currentTimeMillis())
        secrets.put(id, secret)
        dao.upsert(saved.toEntity())
        return saved
    }

    suspend fun rename(id: String, name: String) = dao.rename(id, name.trim())

    suspend fun remove(id: String) {
        dao.delete(id)
        secrets.remove(id)
    }

    private fun RemoteAccountEntity.toDomain() = RemoteAccount(
        id = id,
        kind = RemoteAccountKind.valueOf(kind),
        displayName = displayName,
        endpoint = endpoint,
        region = region,
        bucketOrRoot = bucketOrRoot,
        username = username,
        certSha256 = certSha256,
        createdAt = createdAt,
    )

    private fun RemoteAccount.toEntity() = RemoteAccountEntity(
        id = id,
        kind = kind.name,
        displayName = displayName,
        endpoint = endpoint,
        region = region,
        bucketOrRoot = bucketOrRoot,
        username = username,
        secretRef = id,
        createdAt = createdAt,
        certSha256 = certSha256,
    )
}

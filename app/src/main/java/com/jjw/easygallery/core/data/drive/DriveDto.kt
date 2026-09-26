package com.jjw.easygallery.core.data.drive

import kotlinx.serialization.Serializable

@Serializable
data class DriveFileDto(
    val id: String,
    val name: String,
    val mimeType: String? = null,
    val parents: List<String>? = null,
    val modifiedTime: String? = null,
    /** Drive 는 int64 를 문자열로 내려준다 */
    val size: String? = null,
    val webViewLink: String? = null,
    val capabilities: DriveCapabilitiesDto? = null,
)

/** 이 항목에 무엇을 할 수 있는지. `drive.file` 에서 접근권이 어디까지 미치는지 보는 데 쓴다. */
@Serializable
data class DriveCapabilitiesDto(
    val canAddChildren: Boolean? = null,
    val canEdit: Boolean? = null,
)

@Serializable
data class DriveFileListDto(
    val files: List<DriveFileDto> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class DriveFileMetadata(
    val name: String,
    val mimeType: String? = null,
    val parents: List<String>? = null,
    val appProperties: Map<String, String>? = null,
)

/** `files.update` 본문. null 필드는 직렬화되지 않는다(`explicitNulls = false`) */
@Serializable
data class DriveFilePatch(
    val name: String? = null,
    val trashed: Boolean? = null,
)

@Serializable
data class DriveAboutDto(
    val user: DriveUserDto,
    val storageQuota: StorageQuotaDto? = null,
)

@Serializable
data class DriveUserDto(
    val displayName: String? = null,
    val emailAddress: String,
    val photoLink: String? = null,
)

@Serializable
data class StorageQuotaDto(
    val limit: String? = null,
    val usage: String? = null,
    val usageInDrive: String? = null,
)

/**
 * `permissions.create` 본문. 다른 계정 업로드에서 B 의 폴더를 A 에게 보여줄 때 쓴다
 * (`docs/plans/guest-account-upload/spec.md` §4.4).
 */
@Serializable
data class DrivePermissionRequest(
    val type: String,
    val role: String,
    val emailAddress: String,
)

@Serializable
data class DrivePermissionDto(
    val id: String,
    val role: String? = null,
)

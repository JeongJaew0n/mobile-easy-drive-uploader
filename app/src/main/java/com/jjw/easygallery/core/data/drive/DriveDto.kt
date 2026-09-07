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

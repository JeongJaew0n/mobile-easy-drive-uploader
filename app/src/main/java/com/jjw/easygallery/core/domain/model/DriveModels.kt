package com.jjw.easygallery.core.domain.model

data class DriveFolder(
    val id: String,
    val name: String,
)

data class DriveAccount(
    val email: String,
    val displayName: String?,
    val storageUsedBytes: Long?,
    val storageLimitBytes: Long?,
)

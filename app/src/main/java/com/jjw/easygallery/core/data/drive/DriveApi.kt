package com.jjw.easygallery.core.data.drive

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/** Google Drive REST v3. 공식 Java 클라이언트 대신 필요한 엔드포인트만 직접 정의한다. */
interface DriveApi {

    @GET("drive/v3/about")
    suspend fun about(
        @Query("fields") fields: String = "user,storageQuota",
    ): DriveAboutDto

    @GET("drive/v3/files")
    suspend fun listFiles(
        @Query("q") query: String,
        @Query("pageToken") pageToken: String? = null,
        @Query("pageSize") pageSize: Int = DEFAULT_PAGE_SIZE,
        @Query("orderBy") orderBy: String = "folder,name_natural",
        @Query("spaces") spaces: String = "drive",
        @Query("fields") fields: String =
            "nextPageToken,files(id,name,mimeType,parents,modifiedTime,size,webViewLink)",
    ): DriveFileListDto

    @POST("drive/v3/files")
    suspend fun createFile(
        @Body metadata: DriveFileMetadata,
        @Query("fields") fields: String = "id,name,mimeType,parents",
    ): DriveFileDto

    /**
     * 메타데이터 수정. 이름 변경은 본문 `name`, 휴지통은 `trashed`, 이동은 `addParents/removeParents` 쿼리(본문은 `{}`).
     * `docs/DRIVE_FILE_CRUD.md` §2
     */
    @PATCH("drive/v3/files/{fileId}")
    suspend fun updateFile(
        @Path("fileId") fileId: String,
        @Body patch: DriveFilePatch,
        @Query("addParents") addParents: String? = null,
        @Query("removeParents") removeParents: String? = null,
        @Query("fields") fields: String = "id,name,mimeType,parents,trashed,modifiedTime,size,webViewLink",
    ): DriveFileDto

    /** 재개 가능 업로드 세션 시작. 응답 `Location` 헤더가 세션 URI. */
    @POST("upload/drive/v3/files?uploadType=resumable")
    suspend fun startResumableUpload(
        @Body metadata: DriveFileMetadata,
        @Header("X-Upload-Content-Type") contentType: String,
        @Header("X-Upload-Content-Length") contentLength: Long,
    ): Response<Unit>

    companion object {
        const val BASE_URL = "https://www.googleapis.com/"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val DEFAULT_PAGE_SIZE = 100
    }
}

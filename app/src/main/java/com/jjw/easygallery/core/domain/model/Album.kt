package com.jjw.easygallery.core.domain.model

/** MediaStore 의 RELATIVE_PATH 단위로 묶인 앨범(폴더). */
data class Album(
    val name: String,
    val relativePath: String,
    val itemCount: Int,
    /** 가장 최근에 찍은 것. 앨범 격자의 대표 사진이자 "최근 앨범부터" 정렬 기준 */
    val cover: MediaItem? = null,
)

/** 현재 목록에서 앨범 후보를 뽑는다. 경로가 비어 있는 행(레거시)은 제외. */
fun albumsFrom(items: List<MediaItem>): List<Album> =
    items.asSequence()
        .filter { it.relativePath.isNotBlank() }
        .groupBy { it.relativePath }
        .map { (path, group) ->
            Album(
                name = group.first().bucketName.ifBlank { path.trimEnd('/') },
                relativePath = path,
                itemCount = group.size,
                cover = group.maxByOrNull { it.dateTakenMillis },
            )
        }
        .sortedBy { it.name.lowercase() }

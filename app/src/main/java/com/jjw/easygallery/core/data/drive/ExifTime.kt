package com.jjw.easygallery.core.data.drive

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Drive 의 `imageMediaMetadata.time` — EXIF 그대로의 `yyyy:MM:dd HH:mm:ss`. 시간대가 없어 기기 시간대로 읽는다
 * (사진은 대개 찍은 곳의 현지 시각이고, 갤러리도 같은 방식으로 보인다). 형식이 다르거나 0 으로 채운 값이면 null.
 */
internal fun parseExifTime(value: String, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
    LocalDateTime.parse(value.trim(), EXIF_FORMAT).atZone(zone).toInstant().toEpochMilli()
} catch (e: DateTimeParseException) {
    null
}

private val EXIF_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

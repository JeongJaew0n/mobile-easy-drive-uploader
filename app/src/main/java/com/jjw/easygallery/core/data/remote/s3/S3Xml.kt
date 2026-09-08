package com.jjw.easygallery.core.data.remote.s3

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Instant
import java.time.format.DateTimeParseException

/** ListObjectsV2 응답의 필요한 부분만 */
data class S3ListResult(
    val objects: List<S3Object>,
    val commonPrefixes: List<String>,
    val nextContinuationToken: String?,
)

data class S3Object(val key: String, val size: Long?, val lastModifiedMillis: Long?)

/** ListParts 의 한 파트 */
data class S3Part(val partNumber: Int, val size: Long, val eTag: String)

/** 플랫폼 내장 XmlPullParser 로 S3 XML 을 읽는다(라이브러리 추가 없음) */
object S3Xml {

    fun parseListResult(xml: String): S3ListResult {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        val objects = ArrayList<S3Object>()
        val prefixes = ArrayList<String>()
        var nextToken: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Contents" -> objects += readContents(parser)
                    "CommonPrefixes" -> readCommonPrefix(parser)?.let(prefixes::add)
                    "NextContinuationToken" -> nextToken = parser.nextText()
                }
            }
            event = parser.next()
        }
        return S3ListResult(objects, prefixes, nextToken)
    }

    /** InitiateMultipartUploadResult 의 `<UploadId>` */
    fun parseUploadId(xml: String): String? = firstText(xml, "UploadId")

    /** ListPartsResult → 파트 목록(번호순) */
    fun parseListParts(xml: String): List<S3Part> {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        val parts = ArrayList<S3Part>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "Part") parts += readPart(parser)
            event = parser.next()
        }
        return parts.sortedBy { it.partNumber }
    }

    /** 200 응답이어도 본문이 `<Error>` 일 수 있는 CompleteMultipartUpload 용 */
    fun errorCode(xml: String): String? = if (xml.contains("<Error>")) firstText(xml, "Code") else null

    private fun firstText(xml: String, tag: String): String? {
        val parser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(StringReader(xml)) }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == tag) return parser.nextText().trim()
            event = parser.next()
        }
        return null
    }

    private fun readPart(parser: XmlPullParser): S3Part {
        var number = 0
        var size = 0L
        var eTag = ""
        while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "Part")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "PartNumber" -> number = parser.nextText().trim().toInt()
                    "Size" -> size = parser.nextText().trim().toLong()
                    "ETag" -> eTag = parser.nextText().trim()
                }
            }
        }
        return S3Part(number, size, eTag)
    }

    private fun readContents(parser: XmlPullParser): S3Object {
        var key = ""
        var size: Long? = null
        var modified: Long? = null
        while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "Contents")) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "Key" -> key = parser.nextText()
                    "Size" -> size = parser.nextText().toLongOrNull()
                    "LastModified" -> modified = parseInstant(parser.nextText())
                }
            }
        }
        return S3Object(key, size, modified)
    }

    private fun readCommonPrefix(parser: XmlPullParser): String? {
        var prefix: String? = null
        while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "CommonPrefixes")) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "Prefix") prefix = parser.nextText()
        }
        return prefix
    }

    private fun parseInstant(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}

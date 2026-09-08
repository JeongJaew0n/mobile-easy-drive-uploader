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

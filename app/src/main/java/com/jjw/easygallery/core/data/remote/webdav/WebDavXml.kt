package com.jjw.easygallery.core.data.remote.webdav

import okhttp3.HttpUrl
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** PROPFIND 207 응답의 한 `<response>` */
data class DavResource(
    /** endpoint 기준 상대 경로(디코딩됨). 컬렉션은 `/` 로 끝난다 */
    val path: String,
    val displayName: String?,
    val isCollection: Boolean,
    val contentLength: Long?,
    val contentType: String?,
    val lastModifiedMillis: Long?,
    val quotaAvailableBytes: Long?,
    val quotaUsedBytes: Long?,
)

/** 네임스페이스 접두어가 서버마다 달라(`d:`, `D:`, 없음) 로컬 이름으로만 비교한다 */
object WebDavXml {

    fun parseMultiStatus(xml: String, baseUrl: HttpUrl): List<DavResource> {
        val parser = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        parser.setInput(StringReader(xml))
        val resources = ArrayList<DavResource>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "response") {
                resources += readResponse(parser, baseUrl)
            }
            event = parser.next()
        }
        return resources
    }

    @Suppress("CyclomaticComplexMethod") // 태그 이름 분기 — 표 형태가 가장 읽기 쉽다
    private fun readResponse(parser: XmlPullParser, baseUrl: HttpUrl): DavResource {
        var href = ""
        var displayName: String? = null
        var collection = false
        var length: Long? = null
        var type: String? = null
        var modified: Long? = null
        var quotaAvailable: Long? = null
        var quotaUsed: Long? = null
        while (!(parser.next() == XmlPullParser.END_TAG && parser.name == "response")) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "href" -> href = parser.nextText().trim()
                "displayname" -> displayName = parser.nextText().trim()
                "collection" -> collection = true
                "getcontentlength" -> length = parser.nextText().trim().toLongOrNull()
                "getcontenttype" -> type = parser.nextText().trim().ifBlank { null }
                "getlastmodified" -> modified = parseHttpDate(parser.nextText().trim())
                "quota-available-bytes" -> quotaAvailable = parser.nextText().trim().toLongOrNull()
                "quota-used-bytes" -> quotaUsed = parser.nextText().trim().toLongOrNull()
            }
        }
        val path = relativePath(href, baseUrl).let { if (collection && !it.endsWith("/")) "$it/" else it }
        return DavResource(path, displayName, collection, length, type, modified, quotaAvailable, quotaUsed)
    }

    /** href(절대 URL 또는 절대 경로, 인코딩됨) → endpoint 기준 상대 경로 */
    fun relativePath(href: String, baseUrl: HttpUrl): String {
        val encodedPath = href.substringAfter("://", href).let { if (it == href) it else it.substringAfter('/', "") }
            .let { if (href.contains("://")) "/$it" else it }
            .substringBefore('?')
        val decoded = URLDecoder.decode(encodedPath.replace("+", "%2B"), "UTF-8")
        val basePath = baseUrl.encodedPath.let { URLDecoder.decode(it, "UTF-8") }.trimEnd('/')
        val relative = if (basePath.isNotEmpty() && decoded.startsWith(basePath)) {
            decoded.removePrefix(basePath)
        } else {
            decoded
        }
        return if (relative.isEmpty()) "/" else relative
    }

    private fun parseHttpDate(value: String): Long? = try {
        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        null
    }
}

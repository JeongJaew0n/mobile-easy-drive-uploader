package com.jjw.easygallery.core.data.remote.webdav

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

/**
 * HTTP Digest 인증(RFC 7616, MD5·SHA-256, qop=auth). `docs/NAS_STORAGE.md` §2.
 *
 * - 처음에는 Basic 을 보낸다(HTTPS 전제). 서버가 `401 WWW-Authenticate: Digest …` 로 답하면
 *   [authenticator] 가 응답을 계산해 같은 요청을 다시 보내고 챌린지를 기억한다.
 * - 이후 요청은 [interceptor] 가 기억한 챌린지로 **선제적으로** Digest 헤더를 붙인다 — 스트리밍 PUT 은
 *   재전송이 안 되므로(`isOneShot`) 401 뒤 재시도로는 업로드가 불가능하기 때문.
 * - 서버가 `stale=true` 로 nonce 만료를 알리면 새 nonce 로 한 번 더, 그 외 반복 401 은 자격 증명 오류로 두고 멈춘다.
 */
class DigestAuth(private val username: String, private val password: String) {

    @Volatile
    private var challenge: Challenge? = null
    private val nonceCount = AtomicInteger(0)
    private val random = SecureRandom()

    /** Basic 또는(챌린지를 알면) Digest 를 붙인다 */
    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val header = challenge?.let { authorization(request, it) } ?: Credentials.basic(username, password)
        chain.proceed(request.newBuilder().header("Authorization", header).build())
    }

    /** 401 Digest 챌린지 → 응답 계산 후 재시도. 반복 실패면 null(포기) */
    val authenticator = Authenticator { _: Route?, response: Response ->
        val parsed = response.headers("WWW-Authenticate").firstNotNullOfOrNull { Challenge.parse(it) }
            ?: return@Authenticator null
        val previous = response.request.header("Authorization")
        val alreadyDigest = previous?.startsWith("Digest ", ignoreCase = true) == true
        if (alreadyDigest && !parsed.stale) return@Authenticator null
        challenge = parsed
        nonceCount.set(0)
        response.request.newBuilder().header("Authorization", authorization(response.request, parsed)).build()
    }

    private fun authorization(request: Request, challenge: Challenge): String {
        val nc = nonceCount.incrementAndGet()
        val cnonce = ByteArray(CNONCE_BYTES).also(random::nextBytes).toHex()
        val uri = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        return DigestCalculator.header(
            username = username,
            password = password,
            method = request.method,
            uri = uri,
            challenge = challenge,
            nc = nc,
            cnonce = cnonce,
        )
    }

    /** `WWW-Authenticate: Digest` 의 파라미터 */
    data class Challenge(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        val algorithm: String,
        /** 서버가 제시한 qop 중 우리가 쓰는 것. null 이면 RFC 2069 호환(qop 없음) */
        val qop: String?,
        val stale: Boolean,
    ) {
        companion object {
            fun parse(header: String): Challenge? {
                if (!header.startsWith("Digest ", ignoreCase = true)) return null
                val params = HashMap<String, String>()
                PARAM.findAll(header.substring("Digest ".length)).forEach { m ->
                    params[m.groupValues[1].lowercase()] = m.groupValues[QUOTED].ifEmpty { m.groupValues[BARE] }
                }
                val nonce = params["nonce"] ?: return null
                val qops = params["qop"]?.split(',')?.map { it.trim() }.orEmpty()
                return Challenge(
                    realm = params["realm"].orEmpty(),
                    nonce = nonce,
                    opaque = params["opaque"],
                    algorithm = params["algorithm"] ?: "MD5",
                    qop = if ("auth" in qops) "auth" else null,
                    stale = params["stale"]?.equals("true", ignoreCase = true) == true,
                )
            }

            private const val QUOTED = 2
            private const val BARE = 3
            private val PARAM = Regex("""(\w+)=(?:"((?:[^"\\]|\\.)*)"|([^,\s]*))""")
        }
    }

    private companion object {
        const val CNONCE_BYTES = 16
    }
}

/** 순수 계산 — RFC 7616 §3.9 벡터로 테스트한다 */
object DigestCalculator {

    @Suppress("LongParameterList") // RFC 의 입력 그대로 — 묶으면 오히려 벡터 대조가 어렵다
    fun header(
        username: String,
        password: String,
        method: String,
        uri: String,
        challenge: DigestAuth.Challenge,
        nc: Int,
        cnonce: String,
    ): String {
        val response = response(username, password, method, uri, challenge, nc, cnonce)
        val ncHex = nc.toString(HEX_RADIX).padStart(NC_DIGITS, '0')
        return buildString {
            append("Digest username=\"").append(quote(username)).append('"')
            append(", realm=\"").append(quote(challenge.realm)).append('"')
            append(", nonce=\"").append(challenge.nonce).append('"')
            append(", uri=\"").append(uri).append('"')
            append(", response=\"").append(response).append('"')
            append(", algorithm=").append(challenge.algorithm)
            challenge.opaque?.let { append(", opaque=\"").append(it).append('"') }
            if (challenge.qop != null) {
                append(", qop=").append(challenge.qop)
                append(", nc=").append(ncHex)
                append(", cnonce=\"").append(cnonce).append('"')
            }
        }
    }

    @Suppress("LongParameterList")
    fun response(
        username: String,
        password: String,
        method: String,
        uri: String,
        challenge: DigestAuth.Challenge,
        nc: Int,
        cnonce: String,
    ): String {
        val hash = hasher(challenge.algorithm)
        var ha1 = hash("$username:${challenge.realm}:$password")
        if (challenge.algorithm.endsWith("-sess", ignoreCase = true)) ha1 = hash("$ha1:${challenge.nonce}:$cnonce")
        val ha2 = hash("$method:$uri")
        return if (challenge.qop != null) {
            val ncHex = nc.toString(HEX_RADIX).padStart(NC_DIGITS, '0')
            hash("$ha1:${challenge.nonce}:$ncHex:$cnonce:${challenge.qop}:$ha2")
        } else {
            hash("$ha1:${challenge.nonce}:$ha2")
        }
    }

    private fun hasher(algorithm: String): (String) -> String {
        val name = when (algorithm.removeSuffix("-sess").uppercase()) {
            "SHA-256" -> "SHA-256"
            "SHA-512-256" -> "SHA-512/256"
            else -> "MD5"
        }
        return { input -> MessageDigest.getInstance(name).digest(input.toByteArray()).toHex() }
    }

    private fun quote(value: String) = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private const val HEX_RADIX = 16
    private const val NC_DIGITS = 8
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

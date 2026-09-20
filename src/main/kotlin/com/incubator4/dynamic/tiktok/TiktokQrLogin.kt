package com.incubator4.dynamic.tiktok

import kotlinx.serialization.json.JsonObject
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.abs
import kotlin.random.Random

internal const val TIKTOK_SSO_HOME: String = "https://sso.douyin.com"
internal const val TIKTOK_LOGIN_HOME: String = "https://login.douyin.com"
internal const val TIKTOK_LOGIN_BOOTSTRAP_URL: String = "$TIKTOK_HOME/login/"
internal const val TIKTOK_QR_CREATE_URL: String = "$TIKTOK_LOGIN_HOME/passport/web/get_qrcode/"
internal const val TIKTOK_QR_CHECK_URL: String = "$TIKTOK_LOGIN_HOME/passport/web/check_qrconnect/"
internal const val TIKTOK_TTWID_REGISTER_URL: String = "https://ttwid.bytedance.com/ttwid/union/register/"
internal const val TIKTOK_TTWID_REGISTER_BODY: String =
    """{"region":"cn","aid":6383,"needFid":false,"service":"www.douyin.com","migrate_info":{"ticket":"","source":"node"},"cbUrlProtocol":"https","union":true}"""
internal const val TIKTOK_QR_SERVICE: String = TIKTOK_HOME
internal const val TIKTOK_QR_AID: String = TIKTOK_WEB_AID
internal const val TIKTOK_QR_PASSPORT_JSSDK_VERSION: String = "3.4.4"
internal const val TIKTOK_QR_PASSPORT_JSSDK_TYPE: String = "normal"
internal const val TIKTOK_QR_ACCOUNT_SDK_SOURCE: String = "web"
internal const val TIKTOK_QR_DEVICE_PLATFORM: String = "web_app"
internal const val TIKTOK_QR_POLL_INTERVAL_MS: Long = 2_000L
internal const val TIKTOK_QR_TIMEOUT_MS: Long = 180_000L
internal const val TIKTOK_QR_EXPIRE_SECONDS: Long = 180L

private const val VERIFY_FP_ALPHABET: String =
    "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

internal data class TiktokQrLoginOutcome(
    val result: PublisherLoginResult,
    val cookieHeader: String? = null,
)

internal data class TiktokQrCodeSession(
    val token: String,
    val qrContent: String?,
    val qrImageBytes: ByteArray?,
    val verifyFp: String,
    val expiresAtEpochSeconds: Long,
    val isFrontier: Boolean = false,
)

internal enum class TiktokQrCheckStatus {
    WAITING,
    SCANNED,
    CONFIRMED,
    EXPIRED,
    ERROR,
}

internal data class TiktokQrCheckResult(
    val status: TiktokQrCheckStatus,
    val message: String,
    val redirectUrl: String? = null,
    val nickname: String? = null,
)

/**
 * 生成网页端 `verifyFp` / `s_v_web_id`。
 * 格式对齐开源方案（如 f2）：`verify_{base36(ms)}_{uuid-like}`。
 */
internal fun generateTiktokVerifyFp(nowMs: Long = System.currentTimeMillis(), random: Random = Random.Default): String {
    val base36Time = toBase36(abs(nowMs))
    val body = CharArray(36) { '0' }
    body[8] = '_'
    body[13] = '_'
    body[18] = '_'
    body[23] = '_'
    body[14] = '4'
    for (index in body.indices) {
        if (index == 8 || index == 13 || index == 14 || index == 18 || index == 23) continue
        var pick = random.nextInt(VERIFY_FP_ALPHABET.length)
        if (index == 19) {
            pick = (pick and 0x3) or 0x8
        }
        body[index] = VERIFY_FP_ALPHABET[pick % VERIFY_FP_ALPHABET.length]
    }
    return "verify_${base36Time}_${body.concatToString()}"
}

internal fun tiktokQrCreateQueryString(verifyFp: String): String {
    return tiktokQrQueryString(verifyFp = verifyFp, includeCreateFlags = true)
}

internal fun tiktokQrCheckQueryString(verifyFp: String): String {
    return tiktokQrQueryString(verifyFp = verifyFp, includeCreateFlags = false)
}

internal fun tiktokQrCheckFormBody(token: String): String {
    val utf8 = StandardCharsets.UTF_8
    return buildString {
        append("need_logo=false")
        append("&is_frontier=true")
        append("&token=")
        append(URLEncoder.encode(token, utf8))
        append("&is_new_login=1")
        append("&next=")
        append(URLEncoder.encode(TIKTOK_QR_SERVICE, utf8))
        append("&need_short_url=true")
    }
}

/**
 * 抖音网页扫码查询串。
 * 对齐当前 `login.douyin.com` 网页端（passport jssdk 3.x + account_sdk_source=web），
 * 而不是已返回登录页 HTML 的旧 `sso.douyin.com/get_qrcode/`。
 */
internal fun tiktokQrQueryString(
    verifyFp: String,
    token: String? = null,
    includeCreateFlags: Boolean = token == null,
): String {
    val utf8 = StandardCharsets.UTF_8
    return buildString {
        append("aid=")
        append(TIKTOK_QR_AID)
        append("&language=zh")
        append("&device_platform=")
        append(TIKTOK_QR_DEVICE_PLATFORM)
        append("&account_sdk_source=")
        append(TIKTOK_QR_ACCOUNT_SDK_SOURCE)
        append("&passport_jssdk_version=")
        append(TIKTOK_QR_PASSPORT_JSSDK_VERSION)
        append("&passport_jssdk_type=")
        append(TIKTOK_QR_PASSPORT_JSSDK_TYPE)
        append("&is_from_ttaccountsdk=1")
        if (includeCreateFlags) {
            append("&next=")
            append(URLEncoder.encode(TIKTOK_QR_SERVICE, utf8))
            append("&need_logo=false&need_short_url=true&is_new_login=1")
        }
        append("&verifyFp=")
        append(URLEncoder.encode(verifyFp, utf8))
        append("&fp=")
        append(URLEncoder.encode(verifyFp, utf8))
        if (!token.isNullOrBlank()) {
            append("&token=")
            append(URLEncoder.encode(token, utf8))
        }
    }
}

internal fun parseTiktokQrCodeCreate(json: String): TiktokQrCodeSession {
    val root = parseTiktokQrJson(json, "抖音二维码创建")
    val data = root.obj("data") ?: JsonObject(emptyMap())
    val errorCode = root.long("error_code", "status_code", "code")
        ?: data.long("error_code", "status_code", "code")
    val errorMessage = firstNonBlank(
        data.string("description", "message", "msg"),
        root.string("description", "message", "msg", "status_msg"),
    ).orEmpty()
    if (looksLikeRiskControl(errorCode, errorMessage)) {
        throw TiktokBlockedException(
            errorMessage.ifBlank { "抖音二维码登录疑似被风控，请稍后再试" },
        )
    }
    if (errorCode != null && errorCode != 0L) {
        throw TiktokLoginException(
            errorMessage.ifBlank { "抖音二维码创建失败（code=$errorCode）" },
        )
    }

    val token = firstNonBlank(data.string("token"), root.string("token"))
        ?: throw TiktokLoginException("抖音二维码创建响应缺少 token")
    val qrContent = firstNonBlank(
        data.string("qrcode_index_url", "qrcode_url", "frontend_show_qrcode", "url"),
        root.string("qrcode_index_url", "qrcode_url", "frontend_show_qrcode", "url"),
    )
    val qrImageBytes = decodeTiktokQrImage(
        firstNonBlank(
            data.string("qrcode", "qrcode_base64"),
            root.string("qrcode", "qrcode_base64"),
        ),
    )
    if (qrContent.isNullOrBlank() && (qrImageBytes == null || qrImageBytes.isEmpty())) {
        throw TiktokLoginException("抖音二维码创建响应缺少二维码内容")
    }

    val expireTime = data.long("expire_time") ?: root.long("expire_time")
    val expiresAt = expireTime?.takeIf { it > 1_000_000_000L }
        ?: ((System.currentTimeMillis() / 1000L) + TIKTOK_QR_EXPIRE_SECONDS)
    val isFrontier = data.boolean("is_frontier") == true || root.boolean("is_frontier") == true

    return TiktokQrCodeSession(
        token = token,
        qrContent = qrContent,
        qrImageBytes = qrImageBytes,
        verifyFp = firstNonBlank(
            data.string("verify_fp", "fp"),
            root.string("verify_fp", "fp"),
        ) ?: generateTiktokVerifyFp(),
        expiresAtEpochSeconds = expiresAt,
        isFrontier = isFrontier,
    )
}

internal fun parseTiktokQrCodeCheck(json: String): TiktokQrCheckResult {
    val root = parseTiktokQrJson(json, "抖音二维码状态")
    val data = root.obj("data") ?: JsonObject(emptyMap())
    val errorCode = root.long("error_code", "status_code", "code")
        ?: data.long("error_code", "status_code", "code")
    val errorMessage = firstNonBlank(
        data.string("description", "message", "msg"),
        root.string("description", "message", "msg", "status_msg"),
    ).orEmpty()
    if (looksLikeRiskControl(errorCode, errorMessage)) {
        return TiktokQrCheckResult(
            status = TiktokQrCheckStatus.ERROR,
            message = errorMessage.ifBlank { "抖音二维码登录疑似被风控，请稍后再试" },
        )
    }
    if (errorCode != null && errorCode != 0L) {
        return TiktokQrCheckResult(
            status = TiktokQrCheckStatus.ERROR,
            message = errorMessage.ifBlank { "抖音二维码状态检查失败（code=$errorCode）" },
        )
    }

    val statusCode = firstNonBlank(
        data.string("status"),
        data.long("status")?.toString(),
        root.string("status"),
        root.long("status")?.toString(),
    ).orEmpty()
    val redirectUrl = firstHttpUrl(
        data.string("redirect_url", "redirect_uri"),
        root.string("redirect_url", "redirect_uri"),
    )
    val nickname = firstNonBlank(
        data.string("nickname", "screen_name", "name"),
        root.string("nickname", "screen_name", "name"),
    )
    return when {
        statusCode == "5" || statusCode.equals("expired", ignoreCase = true) -> {
            TiktokQrCheckResult(
                status = TiktokQrCheckStatus.EXPIRED,
                message = "抖音二维码已过期，请重新获取后扫码",
                nickname = nickname,
            )
        }
        statusCode == "2" || statusCode.equals("scanned", ignoreCase = true) -> {
            TiktokQrCheckResult(
                status = TiktokQrCheckStatus.SCANNED,
                message = "已扫码，请在抖音 App 内确认登录",
                nickname = nickname,
            )
        }
        statusCode == "3" || statusCode == "4" ||
            statusCode.equals("confirmed", ignoreCase = true) ||
            !redirectUrl.isNullOrBlank() -> {
            TiktokQrCheckResult(
                status = TiktokQrCheckStatus.CONFIRMED,
                message = "扫码已确认，正在完成登录",
                redirectUrl = redirectUrl,
                nickname = nickname,
            )
        }
        statusCode == "1" || statusCode.isBlank() ||
            statusCode.equals("new", ignoreCase = true) ||
            statusCode.equals("waiting", ignoreCase = true) -> {
            TiktokQrCheckResult(
                status = TiktokQrCheckStatus.WAITING,
                message = "请使用抖音 App 扫描二维码",
                nickname = nickname,
            )
        }
        else -> {
            TiktokQrCheckResult(
                status = TiktokQrCheckStatus.ERROR,
                message = errorMessage.ifBlank { "抖音二维码状态未知（status=$statusCode）" },
                nickname = nickname,
            )
        }
    }
}

internal fun TiktokQrCodeSession.toChallenge(): PublisherQrLoginChallenge {
    return PublisherQrLoginChallenge(
        qrContent = qrContent,
        qrImageBytes = qrImageBytes,
        expiresAtEpochSeconds = expiresAtEpochSeconds,
        message = "请使用抖音 App 扫描二维码并确认登录",
        instruction = "打开抖音 App → 右上角扫一扫 → 确认登录",
        validityHint = "约三分钟内有效",
        statusPollIntervalMillis = TIKTOK_QR_POLL_INTERVAL_MS,
    )
}

private fun parseTiktokQrJson(body: String, action: String): JsonObject {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) {
        throw TiktokLoginException("${action}响应为空")
    }
    if (looksLikeHtml(trimmed)) {
        throw TiktokBlockedException(
            "${action}疑似被风控（返回了网页而不是数据），请稍后再试或改用 Cookie 登录",
        )
    }
    return parseJsonObject(trimmed, "${action}响应不是有效 JSON")
}

private fun decodeTiktokQrImage(raw: String?): ByteArray? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val payload = value
        .removePrefix("data:image/png;base64,")
        .removePrefix("data:image/jpeg;base64,")
        .removePrefix("data:image/jpg;base64,")
        .trim()
    return runCatching { Base64.getDecoder().decode(payload) }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }
}

private fun toBase36(value: Long): String {
    if (value == 0L) return "0"
    val alphabet = "0123456789abcdefghijklmnopqrstuvwxyz"
    var remaining = value
    val chars = ArrayDeque<Char>()
    while (remaining > 0L) {
        val index = (remaining % 36L).toInt()
        chars.addFirst(alphabet[index])
        remaining /= 36L
    }
    return chars.joinToString("")
}

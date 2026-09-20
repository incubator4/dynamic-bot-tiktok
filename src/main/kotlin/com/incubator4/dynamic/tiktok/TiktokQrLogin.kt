package com.incubator4.dynamic.tiktok

import kotlinx.serialization.json.JsonObject
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

internal const val TIKTOK_SSO_HOME: String = "https://sso.douyin.com"
internal const val TIKTOK_QR_CREATE_URL: String = "$TIKTOK_SSO_HOME/get_qrcode/"
internal const val TIKTOK_QR_CHECK_URL: String = "$TIKTOK_SSO_HOME/check_qrconnect/"
internal const val TIKTOK_QR_SERVICE: String = TIKTOK_HOME
internal const val TIKTOK_QR_AID: String = TIKTOK_WEB_AID
internal const val TIKTOK_QR_POLL_INTERVAL_MS: Long = 2_000L
internal const val TIKTOK_QR_TIMEOUT_MS: Long = 180_000L
internal const val TIKTOK_QR_EXPIRE_SECONDS: Long = 180L

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

internal fun generateTiktokVerifyFp(): String {
    val suffix = UUID.randomUUID().toString().replace("-", "")
    val noise = Random.nextInt(1000, 9999)
    return "verify_${suffix}_$noise"
}

internal fun parseTiktokQrCodeCreate(json: String): TiktokQrCodeSession {
    val root = parseJsonObject(json, "抖音二维码创建响应不是有效 JSON")
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
        data.string("qrcode_index_url", "qrcode_url", "url"),
        root.string("qrcode_index_url", "qrcode_url", "url"),
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

    return TiktokQrCodeSession(
        token = token,
        qrContent = qrContent,
        qrImageBytes = qrImageBytes,
        verifyFp = firstNonBlank(
            data.string("verify_fp", "fp"),
            root.string("verify_fp", "fp"),
        ) ?: generateTiktokVerifyFp(),
        expiresAtEpochSeconds = (System.currentTimeMillis() / 1000L) + TIKTOK_QR_EXPIRE_SECONDS,
    )
}

internal fun parseTiktokQrCodeCheck(json: String): TiktokQrCheckResult {
    val root = parseJsonObject(json, "抖音二维码状态响应不是有效 JSON")
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
        statusCode == "3" || statusCode == "4" || !redirectUrl.isNullOrBlank() -> {
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

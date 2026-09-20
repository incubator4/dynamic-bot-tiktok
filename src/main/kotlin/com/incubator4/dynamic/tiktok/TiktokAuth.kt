package com.incubator4.dynamic.tiktok

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import java.util.LinkedHashMap

internal const val TIKTOK_PLATFORM_ID: String = "tiktok"
internal const val TIKTOK_HOME: String = "https://www.douyin.com"
internal const val TIKTOK_LIVE_HOME: String = "https://live.douyin.com"
internal const val TIKTOK_WEB_AID: String = "6383"
internal const val TIKTOK_PASSPORT_AID: String = "2906"
internal const val TIKTOK_ACCOUNT_INFO_URL: String =
    "https://www.douyin.com/aweme/v1/passport/account/info/v2/?aid=$TIKTOK_PASSPORT_AID"
internal const val TIKTOK_DEFAULT_AVATAR: String = "https://www.douyin.com/favicon.ico"

internal open class TiktokApiException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class TiktokLoginException(
    message: String,
    cause: Throwable? = null,
) : TiktokApiException(message, cause)

internal class TiktokBlockedException(
    message: String,
    cause: Throwable? = null,
) : TiktokApiException(message, cause)

internal data class TiktokCookieSet(
    val pairs: Map<String, String>,
) {
    val header: String
        get() = pairs.entries.joinToString("; ") { (name, value) -> "$name=$value" }

    fun has(name: String): Boolean = pairs.keys.any { it.equals(name, ignoreCase = true) }

    fun value(name: String): String? {
        return pairs.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            ?.takeIf { it.isNotBlank() }
    }

    fun hasLoginSession(): Boolean {
        return LOGIN_COOKIE_NAMES.any(::has)
    }

    fun isEmpty(): Boolean = pairs.isEmpty()
}

internal data class TiktokAccountSnapshot(
    val code: Long? = null,
    val message: String? = null,
    val hasLogin: Boolean? = null,
    val userId: String? = null,
    val nickname: String? = null,
    val avatarUrl: String? = null,
)

internal fun parseTiktokCookieInput(raw: String): TiktokCookieSet {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return TiktokCookieSet(emptyMap())
    return when {
        trimmed.startsWith("[") -> parseCookieJsonArray(trimmed)
        trimmed.startsWith("{") -> parseCookieJsonObject(trimmed)
        else -> parseCookieHeader(trimmed)
    }
}

internal fun mergeTiktokCookieHeaders(vararg headers: String?): String {
    return parseCookieHeader(headers.filterNotNull().joinToString("; ")).header
}

internal fun parseTiktokAccountInfo(json: String): TiktokAccountSnapshot {
    val root = parseJsonObject(json, "抖音登录状态响应不是有效 JSON")
    val data = root.obj("data", "user") ?: JsonObject(emptyMap())
    val user = data.obj("user") ?: data
    return TiktokAccountSnapshot(
        code = root.long("status_code", "error_code", "code")
            ?: data.long("error_code", "status_code", "code"),
        message = firstNonBlank(
            data.string("description"),
            root.string("status_msg", "msg"),
            data.string("msg", "message"),
            root.string("message")?.takeIf { it != "success" && it != "error" },
            root.string("message"),
        ),
        hasLogin = root.boolean("has_login") ?: data.boolean("has_login"),
        userId = firstNonBlank(
            user.string("user_id_str", "uid_str", "sec_uid"),
            user.long("user_id", "uid")?.takeIf { it > 0L }?.toString(),
            user.string("user_id", "uid"),
            data.string("user_id_str", "uid_str"),
            data.long("user_id", "uid")?.takeIf { it > 0L }?.toString(),
            data.string("user_id", "uid"),
        )?.takeIf { it != "0" },
        nickname = firstNonBlank(
            user.string("screen_name", "nickname", "name", "unique_id"),
            data.string("screen_name", "nickname", "name", "unique_id"),
        ),
        avatarUrl = firstHttpUrl(
            user.string("avatar_url", "avatar"),
            nestedUrlList(user.obj("avatar_thumb", "avatar_medium", "avatar_larger")),
            data.string("avatar_url", "avatar"),
            nestedUrlList(data.obj("avatar_thumb", "avatar_medium", "avatar_larger")),
        ),
    )
}

internal fun TiktokAccountSnapshot.toLoginResult(): PublisherLoginResult {
    val detail = message?.trim().orEmpty()
    if (looksLikeRiskControl(code, detail)) {
        return PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = detail.ifBlank { "抖音请求疑似被风控，已停止继续尝试。请稍后再试或更新 Cookie。" },
        )
    }
    if (looksLikeAppDenied(detail)) {
        return PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = "抖音登录校验失败：当前检查接口无权限。Cookie 可能仍然有效，请改用扫码登录，或更新插件后重试。",
        )
    }
    if (looksLikeMissingAccount(code, detail)) {
        return PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = "抖音 Cookie 未登录或已失效，请重新登录后导入包含 sessionid 的完整 Cookie",
        )
    }
    if (isApiFailure()) {
        return PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = loginFailureMessage(detail.ifBlank { "抖音登录状态不可用" }),
        )
    }
    if (hasLogin == false || userId.isNullOrBlank() || userId == "0") {
        return PublisherLoginResult(
            status = PublisherLoginStatus.FAILED,
            message = "抖音 Cookie 未登录或已失效，请重新登录后导入包含 sessionid 的完整 Cookie",
        )
    }
    return PublisherLoginResult(
        status = PublisherLoginStatus.SUCCESS,
        message = "抖音登录状态可用",
        account = PublisherLoginAccount(
            userId = userId,
            name = nickname?.takeIf { it.isNotBlank() },
            avatar = avatarUrl?.let { MediaRef(it, MediaKind.AVATAR) },
        ),
    )
}

internal fun looksLikeRiskControl(code: Long?, message: String, httpStatus: Int? = null): Boolean {
    if (httpStatus == 403 || httpStatus == 461 || httpStatus == 471) return true
    if (code == 461L || code == 471L || code == 4031L || code == 2156L) return true
    val value = message.lowercase()
    return value.contains("风控") ||
        value.contains("验证码") ||
        value.contains("人机验证") ||
        value.contains("拦截") ||
        value.contains("安全风险") ||
        value.contains("系统繁忙") ||
        value.contains("captcha") ||
        value.contains("risk control") ||
        value.contains("risk")
}

internal fun looksLikeAppDenied(message: String): Boolean {
    val value = message.lowercase()
    return value.contains("该应用无权限") ||
        value.contains("应用无权限") ||
        value.contains("no permission") ||
        value.contains("permission denied")
}

internal fun looksLikeMissingAccount(code: Long?, message: String): Boolean {
    if (code == 1041L) return true
    val value = message.lowercase()
    return value.contains("用户不存在") ||
        value.contains("user not exist") ||
        value.contains("user does not exist")
}

internal fun looksLikeHtml(body: String): Boolean {
    val value = body.trim().lowercase()
    return value.startsWith("<!doctype html") ||
        value.startsWith("<html") ||
        value.contains("<title>") ||
        value.contains("<script")
}

internal fun looksLikeLoginFailure(message: String): Boolean {
    val value = message.lowercase()
    return value.contains("登录") ||
        value.contains("登陆") ||
        value.contains("未登录") ||
        value.contains("过期") ||
        value.contains("失效") ||
        value.contains("login") ||
        value.contains("auth") ||
        value.contains("cookie") ||
        value.contains("session")
}

private fun TiktokAccountSnapshot.isApiFailure(): Boolean {
    val apiCode = code ?: return false
    if (apiCode == 0L) return false
    return true
}

private fun loginFailureMessage(detail: String): String {
    return if (looksLikeLoginFailure(detail)) {
        "抖音登录状态不可用：$detail"
    } else {
        detail
    }
}

private fun parseCookieHeader(header: String): TiktokCookieSet {
    val values = LinkedHashMap<String, String>()
    header.split(';').forEach { raw ->
        val index = raw.indexOf('=')
        if (index <= 0) return@forEach
        val name = raw.substring(0, index).trim()
        val value = raw.substring(index + 1).trim()
        if (name.isBlank() || name.equals("path", ignoreCase = true) ||
            name.equals("domain", ignoreCase = true) ||
            name.equals("expires", ignoreCase = true) ||
            name.equals("max-age", ignoreCase = true) ||
            name.equals("samesite", ignoreCase = true) ||
            name.equals("secure", ignoreCase = true) ||
            name.equals("httponly", ignoreCase = true)
        ) {
            return@forEach
        }
        values[name] = value
    }
    return TiktokCookieSet(values)
}

private fun parseCookieJsonArray(raw: String): TiktokCookieSet {
    val array = runCatching { TIKTOK_JSON.parseToJsonElement(raw).jsonArray }.getOrElse {
        throw TiktokLoginException("抖音 Cookie JSON 无法解析")
    }
    val values = LinkedHashMap<String, String>()
    array.forEach { item ->
        val obj = item as? JsonObject ?: return@forEach
        val name = obj.string("name", "key") ?: return@forEach
        val value = obj.string("value") ?: return@forEach
        values[name] = value
    }
    return TiktokCookieSet(values)
}

private fun parseCookieJsonObject(raw: String): TiktokCookieSet {
    val obj = runCatching { TIKTOK_JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
        throw TiktokLoginException("抖音 Cookie JSON 无法解析")
    }
    if (obj.containsKey("name") && obj.containsKey("value")) {
        val name = obj.string("name") ?: return TiktokCookieSet(emptyMap())
        val value = obj.string("value") ?: return TiktokCookieSet(emptyMap())
        return TiktokCookieSet(mapOf(name to value))
    }
    val values = LinkedHashMap<String, String>()
    obj.forEach { (name, element) ->
        val primitive = element as? JsonPrimitive ?: return@forEach
        val value = primitive.contentOrNull?.trim().orEmpty()
        if (name.isNotBlank() && value.isNotEmpty()) {
            values[name] = value
        }
    }
    return TiktokCookieSet(values)
}

private fun nestedUrlList(obj: JsonObject?): String? {
    val urls = obj?.array("url_list") ?: JsonArray(emptyList())
    return urls.firstNotNullOfOrNull { it.asTrimmedString() }
}

private val LOGIN_COOKIE_NAMES: List<String> = listOf("sessionid", "sessionid_ss", "sid_tt")

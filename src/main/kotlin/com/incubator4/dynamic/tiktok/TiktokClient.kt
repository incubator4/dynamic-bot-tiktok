package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge
import top.colter.dynamic.core.tools.loggerFor
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

private val logger = loggerFor<TiktokClient>()

internal class TiktokClient(
    private val config: TiktokPublisherConfig,
    private val httpClient: HttpClient = defaultHttpClient(config.cookie),
    private val accountInfoUri: URI = URI.create(TIKTOK_ACCOUNT_INFO_URL),
    private val userProfileUriBuilder: (String) -> URI = { userId ->
        URI.create("$TIKTOK_HOME/user/${URLEncoder.encode(userId, StandardCharsets.UTF_8)}")
    },
    private val homeUri: URI = URI.create("$TIKTOK_HOME/"),
    private val ssoHomeUri: URI = URI.create("$TIKTOK_SSO_HOME/"),
    private val qrCreateUri: URI = URI.create(TIKTOK_QR_CREATE_URL),
    private val qrCheckUriBuilder: (String, String) -> URI = { token, verifyFp ->
        URI.create(
            buildString {
                append(TIKTOK_QR_CHECK_URL)
                append("?service=")
                append(URLEncoder.encode(TIKTOK_QR_SERVICE, StandardCharsets.UTF_8))
                append("&need_logo=false&need_short_url=false")
                append("&aid=")
                append(TIKTOK_QR_AID)
                append("&account_sdk_source=sso&sdk_version=2.2.7&language=zh")
                append("&verifyFp=")
                append(URLEncoder.encode(verifyFp, StandardCharsets.UTF_8))
                append("&fp=")
                append(URLEncoder.encode(verifyFp, StandardCharsets.UTF_8))
                append("&token=")
                append(URLEncoder.encode(token, StandardCharsets.UTF_8))
            },
        )
    },
    private val qrPollIntervalMs: Long = TIKTOK_QR_POLL_INTERVAL_MS,
    private val qrTimeoutMs: Long = TIKTOK_QR_TIMEOUT_MS,
) {
    suspend fun checkLoginState(): PublisherLoginResult {
        val cookies = parseTiktokCookieInput(currentCookieHeader())
        if (cookies.isEmpty()) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "抖音 Cookie 未配置",
            )
        }
        if (!cookies.hasLoginSession()) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "抖音 Cookie 缺少登录会话，请从已登录的浏览器导入包含 sessionid 的完整 Cookie",
            )
        }

        return try {
            val response = fetchAccountInfo(cookies.header)
            toLoginResult(response.statusCode(), response.body())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = error.message ?: "抖音登录状态检查失败",
            )
        }
    }

    suspend fun fetchLiveSnapshot(userId: String): TiktokLiveSnapshot {
        val normalized = userId.trim()
        require(normalized.isNotBlank()) { "抖音用户 ID 不能为空" }
        val cookies = parseTiktokCookieInput(currentCookieHeader())
        if (cookies.isEmpty()) {
            throw TiktokLoginException("抖音 Cookie 未配置")
        }
        if (!cookies.hasLoginSession()) {
            throw TiktokLoginException(
                "抖音 Cookie 缺少登录会话，请从已登录的浏览器导入包含 sessionid 的完整 Cookie",
            )
        }
        return try {
            val response = fetchUserProfile(normalized, cookies.header)
            parseLiveResponse(response.statusCode(), response.body(), normalized)
        } catch (error: CancellationException) {
            throw error
        } catch (error: TiktokApiException) {
            throw error
        } catch (error: Throwable) {
            throw TiktokApiException(error.message ?: "抖音直播状态检查失败", error)
        }
    }

    fun exportCookieHeader(): String = currentCookieHeader()


    suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): TiktokQrLoginOutcome {
        val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val qrClient = HttpClient.newBuilder()
            .cookieHandler(cookieManager)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(10))
            .build()

        try {
            warmUpQrSession(qrClient)
            val session = createQrSession(qrClient)
            onQrCode(session.toChallenge())
            onStatusChanged(
                PublisherLoginResult(
                    status = PublisherLoginStatus.PENDING,
                    message = "请使用抖音 App 扫描二维码",
                ),
            )

            val deadline = System.currentTimeMillis() + qrTimeoutMs
            var lastStatus: TiktokQrCheckStatus? = null
            while (System.currentTimeMillis() < deadline) {
                val check = checkQrSession(qrClient, session)
                if (check.status != lastStatus) {
                    lastStatus = check.status
                    when (check.status) {
                        TiktokQrCheckStatus.WAITING -> {
                            emitQrStatus(onStatusChanged, PublisherLoginStatus.PENDING, check.message)
                        }
                        TiktokQrCheckStatus.SCANNED -> {
                            emitQrStatus(onStatusChanged, PublisherLoginStatus.PENDING, check.message)
                        }
                        TiktokQrCheckStatus.EXPIRED -> {
                            return TiktokQrLoginOutcome(
                                result = emitQrStatus(
                                    onStatusChanged,
                                    PublisherLoginStatus.EXPIRED,
                                    check.message,
                                ),
                            )
                        }
                        TiktokQrCheckStatus.ERROR -> {
                            return TiktokQrLoginOutcome(
                                result = emitQrStatus(
                                    onStatusChanged,
                                    PublisherLoginStatus.FAILED,
                                    check.message,
                                ),
                            )
                        }
                        TiktokQrCheckStatus.CONFIRMED -> {
                            emitQrStatus(onStatusChanged, PublisherLoginStatus.PENDING, check.message)
                            finalizeQrLogin(qrClient, check.redirectUrl)
                            val cookieHeader = cookieManager.toCookieHeader()
                            val cookies = parseTiktokCookieInput(cookieHeader)
                            if (!cookies.hasLoginSession()) {
                                return TiktokQrLoginOutcome(
                                    result = emitQrStatus(
                                        onStatusChanged,
                                        PublisherLoginStatus.FAILED,
                                        "扫码已确认，但未拿到包含 sessionid 的登录 Cookie，请重试或改用 Cookie 登录",
                                    ),
                                )
                            }
                            val verified = TiktokClient(
                                config = config.copy(cookie = cookies.header),
                                httpClient = defaultHttpClient(cookies.header),
                                accountInfoUri = accountInfoUri,
                            ).checkLoginState()
                            return if (verified.status == PublisherLoginStatus.SUCCESS) {
                                TiktokQrLoginOutcome(
                                    result = emitQrStatus(onStatusChanged, verified),
                                    cookieHeader = cookies.header,
                                )
                            } else {
                                TiktokQrLoginOutcome(
                                    result = emitQrStatus(
                                        onStatusChanged,
                                        PublisherLoginStatus.FAILED,
                                        verified.message.ifBlank { "扫码登录后账号状态校验失败" },
                                    ),
                                )
                            }
                        }
                    }
                }
                delay(qrPollIntervalMs)
            }
            return TiktokQrLoginOutcome(
                result = emitQrStatus(
                    onStatusChanged,
                    PublisherLoginStatus.EXPIRED,
                    "抖音扫码登录超时，请重新获取二维码",
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: TiktokBlockedException) {
            return TiktokQrLoginOutcome(
                result = emitQrStatus(
                    onStatusChanged,
                    PublisherLoginStatus.FAILED,
                    error.message ?: "抖音扫码登录疑似被风控",
                ),
            )
        } catch (error: Throwable) {
            return TiktokQrLoginOutcome(
                result = emitQrStatus(
                    onStatusChanged,
                    PublisherLoginStatus.FAILED,
                    error.message ?: "抖音扫码登录失败",
                ),
            )
        }
    }


    internal fun parseLiveResponse(statusCode: Int, body: String, userId: String): TiktokLiveSnapshot {
        if (statusCode == 401) {
            throw TiktokLoginException("抖音登录状态不可用：HTTP $statusCode")
        }
        if (looksLikeRiskControl(code = null, message = "", httpStatus = statusCode)) {
            throw TiktokBlockedException(
                "抖音请求疑似被风控（HTTP $statusCode），已停止继续尝试。请稍后再试或更新 Cookie。",
            )
        }
        if (statusCode !in 200..299) {
            throw TiktokApiException("抖音直播状态检查失败：HTTP $statusCode")
        }
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            throw TiktokApiException("抖音用户主页没有返回内容")
        }
        val payload = try {
            extractTiktokEmbeddedPayload(trimmed)
        } catch (error: TiktokApiException) {
            if (looksLikeHtml(trimmed) && looksLikeLoginFailure(trimmed)) {
                throw TiktokLoginException(
                    "抖音 Cookie 未登录或已失效，请重新登录后导入包含 sessionid 的完整 Cookie",
                )
            }
            throw error
        }
        return parseTiktokLiveSnapshot(payload, userId)
    }

    internal fun toLoginResult(statusCode: Int, body: String): PublisherLoginResult {
        if (statusCode == 401) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "抖音登录状态不可用：HTTP $statusCode",
            )
        }
        if (looksLikeRiskControl(code = null, message = "", httpStatus = statusCode)) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "抖音请求疑似被风控（HTTP $statusCode），已停止继续尝试。请稍后再试或更新 Cookie。",
            )
        }
        if (statusCode !in 200..299) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "抖音登录状态检查失败：HTTP $statusCode",
            )
        }
        val trimmed = body.trim()
        if (trimmed.isEmpty() || looksLikeHtml(trimmed)) {
            return PublisherLoginResult(
                status = PublisherLoginStatus.FAILED,
                message = "抖音登录状态不可用：当前会话没有返回账号信息",
            )
        }
        val snapshot = parseTiktokAccountInfo(trimmed)
        val result = snapshot.toLoginResult()
        if (result.status == PublisherLoginStatus.SUCCESS) {
            logger.info {
                "抖音当前账号识别成功：uid=${result.account?.userId ?: "未知"}，name=${result.account?.name ?: "未知"}"
            }
        }
        return result
    }

    private suspend fun fetchAccountInfo(cookieHeader: String): HttpResponse<String> {
        return send(
            HttpRequest.newBuilder(accountInfoUri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .applyCommonHeaders(cookieHeader)
                .build(),
        )
    }

    private suspend fun fetchUserProfile(userId: String, cookieHeader: String): HttpResponse<String> {
        return send(
            HttpRequest.newBuilder(userProfileUriBuilder(userId))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .applyCommonHeaders(cookieHeader, referer = userProfileLink(userId))
                .build(),
        )
    }

    private suspend fun send(request: HttpRequest): HttpResponse<String> {
        return withContext(Dispatchers.IO) {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
    }

    private fun currentCookieHeader(): String {
        return mergeTiktokCookieHeaders(config.cookie, cookieStoreHeader())
    }

    private fun cookieStoreHeader(): String {
        val cookieManager = httpClient.cookieHandler().orElse(null) as? CookieManager ?: return ""
        return cookieManager.cookieStore.cookies
            .asSequence()
            .filterNot { it.hasExpired() }
            .filter { it.name.isNotBlank() }
            .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
    }


    private suspend fun warmUpQrSession(client: HttpClient) {
        listOf(homeUri, ssoHomeUri).distinct().forEach { uri ->
            runCatching {
                sendWithClient(
                    client,
                    HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .applyCommonHeaders("")
                        .build(),
                )
            }
        }
    }

    private suspend fun createQrSession(client: HttpClient): TiktokQrCodeSession {
        val verifyFp = generateTiktokVerifyFp()
        val separator = if (qrCreateUri.query == null) "?" else "&"
        val createUri = URI.create(
            buildString {
                append(qrCreateUri)
                append(separator)
                append("service=")
                append(URLEncoder.encode(TIKTOK_QR_SERVICE, StandardCharsets.UTF_8))
                append("&need_logo=false&need_short_url=false")
                append("&aid=")
                append(TIKTOK_QR_AID)
                append("&account_sdk_source=sso&sdk_version=2.2.7&language=zh")
                append("&verifyFp=")
                append(URLEncoder.encode(verifyFp, StandardCharsets.UTF_8))
                append("&fp=")
                append(URLEncoder.encode(verifyFp, StandardCharsets.UTF_8))
            },
        )
        val response = sendWithClient(
            client,
            HttpRequest.newBuilder(createUri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .applyCommonHeaders("")
                .build(),
        )
        if (looksLikeRiskControl(code = null, message = "", httpStatus = response.statusCode())) {
            throw TiktokBlockedException("抖音二维码创建疑似被风控（HTTP ${response.statusCode()}）")
        }
        if (response.statusCode() !in 200..299) {
            throw TiktokLoginException("抖音二维码创建失败：HTTP ${response.statusCode()}")
        }
        val session = parseTiktokQrCodeCreate(response.body())
        return session.copy(verifyFp = session.verifyFp.ifBlank { verifyFp })
    }

    private suspend fun checkQrSession(
        client: HttpClient,
        session: TiktokQrCodeSession,
    ): TiktokQrCheckResult {
        val response = sendWithClient(
            client,
            HttpRequest.newBuilder(qrCheckUriBuilder(session.token, session.verifyFp))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .applyCommonHeaders("")
                .build(),
        )
        if (looksLikeRiskControl(code = null, message = "", httpStatus = response.statusCode())) {
            return TiktokQrCheckResult(
                status = TiktokQrCheckStatus.ERROR,
                message = "抖音二维码状态检查疑似被风控（HTTP ${response.statusCode()}）",
            )
        }
        if (response.statusCode() !in 200..299) {
            return TiktokQrCheckResult(
                status = TiktokQrCheckStatus.ERROR,
                message = "抖音二维码状态检查失败：HTTP ${response.statusCode()}",
            )
        }
        return parseTiktokQrCodeCheck(response.body())
    }

    private suspend fun finalizeQrLogin(client: HttpClient, redirectUrl: String?) {
        if (!redirectUrl.isNullOrBlank()) {
            runCatching {
                sendWithClient(
                    client,
                    HttpRequest.newBuilder(URI.create(redirectUrl))
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .applyCommonHeaders("")
                        .build(),
                )
            }
        }
        runCatching {
            sendWithClient(
                client,
                HttpRequest.newBuilder(homeUri)
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .applyCommonHeaders("")
                    .build(),
            )
        }
    }

    private suspend fun sendWithClient(
        client: HttpClient,
        request: HttpRequest,
    ): HttpResponse<String> {
        return withContext(Dispatchers.IO) {
            client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        }
    }

    private suspend fun emitQrStatus(
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
        status: PublisherLoginStatus,
        message: String,
    ): PublisherLoginResult {
        return emitQrStatus(onStatusChanged, PublisherLoginResult(status, message))
    }

    private suspend fun emitQrStatus(
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
        result: PublisherLoginResult,
    ): PublisherLoginResult {
        onStatusChanged(result)
        return result
    }

    companion object {
        internal fun defaultHttpClient(cookie: String): HttpClient {
            val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)
            parseTiktokCookieInput(cookie).pairs.forEach { (name, value) ->
                val parsed = HttpCookie(name, value).apply {
                    domain = ".douyin.com"
                    path = "/"
                }
                cookieManager.cookieStore.add(URI.create(TIKTOK_HOME), parsed)
            }
            return HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(10))
                .build()
        }
    }
}

private fun HttpRequest.Builder.applyCommonHeaders(
    cookieHeader: String,
    referer: String = "$TIKTOK_HOME/",
): HttpRequest.Builder {
    header("Accept", "application/json, text/plain, */*")
    header("Accept-Language", "zh-CN,zh;q=0.9")
    header("User-Agent", DESKTOP_USER_AGENT)
    header("Origin", TIKTOK_HOME)
    header("Referer", referer)
    if (cookieHeader.isNotBlank()) {
        header("Cookie", cookieHeader)
    }
    parseTiktokCookieInput(cookieHeader).value("passport_csrf_token")?.let { csrf ->
        header("x-tt-passport-csrf-token", csrf)
    }
    return this
}


private fun CookieManager.toCookieHeader(): String {
    return cookieStore.cookies
        .asSequence()
        .filterNot { it.hasExpired() }
        .filter { it.name.isNotBlank() }
        .joinToString("; ") { cookie -> "${cookie.name}=${cookie.value}" }
}

private const val DESKTOP_USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36 Edg/149.0.0.0"

private fun looksLikeHtml(body: String): Boolean {
    val value = body.lowercase()
    return value.startsWith("<!doctype html") ||
        value.startsWith("<html") ||
        value.contains("<title>")
}

package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.delay
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import top.colter.dynamic.core.plugin.PublisherQrLoginChallenge

internal interface TiktokGateway {
    fun exportCookie(): String = ""

    suspend fun checkLoginState(): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "不支持抖音登录状态检查",
        )
    }

    suspend fun fetchLiveSnapshot(userId: String): TiktokLiveSnapshot {
        return TiktokLiveSnapshot(userId = userId)
    }

    suspend fun expandShortUrl(url: String): String? = null

    suspend fun fetchAwemeSnapshot(awemeId: String, note: Boolean = false): TiktokAwemeSnapshot? {
        throw TiktokApiException("不支持抖音作品详情查询")
    }

    suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): TiktokQrLoginOutcome {
        return TiktokQrLoginOutcome(
            result = PublisherLoginResult(
                status = PublisherLoginStatus.UNSUPPORTED,
                message = "不支持抖音二维码登录",
            ),
        )
    }
}

internal class TiktokHttpGateway(
    private val client: TiktokClient,
    private val requestIntervalMs: Long,
) : TiktokGateway {
    override fun exportCookie(): String = client.exportCookieHeader()

    override suspend fun checkLoginState(): PublisherLoginResult {
        return withRequestInterval {
            client.checkLoginState()
        }
    }

    override suspend fun fetchLiveSnapshot(userId: String): TiktokLiveSnapshot {
        return withRequestInterval {
            client.fetchLiveSnapshot(userId)
        }
    }

    override suspend fun expandShortUrl(url: String): String? {
        return withRequestInterval {
            client.expandShortUrl(url)
        }
    }

    override suspend fun fetchAwemeSnapshot(awemeId: String, note: Boolean): TiktokAwemeSnapshot? {
        return withRequestInterval {
            client.fetchAwemeSnapshot(awemeId, note)
        }
    }

    override suspend fun loginByQrCode(
        onQrCode: suspend (PublisherQrLoginChallenge) -> Unit,
        onStatusChanged: suspend (PublisherLoginResult) -> Unit,
    ): TiktokQrLoginOutcome {
        return client.loginByQrCode(onQrCode, onStatusChanged)
    }

    private suspend fun <T> withRequestInterval(block: suspend () -> T): T {
        return try {
            block()
        } finally {
            if (requestIntervalMs > 0) {
                delay(requestIntervalMs)
            }
        }
    }
}

internal fun secondsToMillis(seconds: Double, minimumMillis: Long): Long {
    return (seconds * 1_000.0).toLong().coerceAtLeast(minimumMillis)
}

package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.delay
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus

internal interface TiktokGateway {
    fun exportCookie(): String = ""

    suspend fun checkLoginState(): PublisherLoginResult {
        return PublisherLoginResult(
            status = PublisherLoginStatus.UNSUPPORTED,
            message = "不支持抖音登录状态检查",
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

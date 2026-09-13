package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.event.SystemNotificationPublishRequest
import top.colter.dynamic.core.event.SystemNotificationPublishResult
import top.colter.dynamic.core.event.SystemNotificationPublisher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TiktokRequestFailureHandlerTest {
    @Test
    fun `pause polling after consecutive login failures and recover after success`() = runBlocking {
        val notifications = mutableListOf<SystemNotificationPublishRequest>()
        val handler = TiktokRequestFailureHandler(
            configProvider = { TiktokPublisherConfig(maxConsecutiveLoginFailures = 2) },
            notificationPublisher = SystemNotificationPublisher { request ->
                notifications += request
                SystemNotificationPublishResult.accepted()
            },
        )

        handler.run("抖音登录检查") {
            throw TiktokLoginException("Cookie 已失效")
        }
        assertFalse(handler.isPollingPaused())

        handler.run("抖音登录检查") {
            throw TiktokLoginException("Cookie 已失效")
        }
        assertTrue(handler.isPollingPaused())
        assertEquals("tiktok.login_paused", notifications.single().type)

        handler.run("抖音登录检查") { "ok" }
        assertFalse(handler.isPollingPaused())
        assertEquals("tiktok.login_recovered", notifications.last().type)
    }

    @Test
    fun `risk control pauses polling immediately`() = runBlocking {
        val notifications = mutableListOf<SystemNotificationPublishRequest>()
        val handler = TiktokRequestFailureHandler(
            configProvider = { TiktokPublisherConfig(maxConsecutiveLoginFailures = 3) },
            notificationPublisher = SystemNotificationPublisher { request ->
                notifications += request
                SystemNotificationPublishResult.accepted()
            },
        )

        handler.run("抖音登录检查") {
            throw TiktokBlockedException("请求被风控拦截")
        }

        assertTrue(handler.isPollingPaused())
        assertEquals("tiktok.risk_paused", notifications.single().type)
    }
}

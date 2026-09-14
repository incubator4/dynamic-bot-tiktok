package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.plugin.PublisherLoginAccount
import top.colter.dynamic.core.plugin.PublisherLoginMethod
import top.colter.dynamic.core.plugin.PublisherLoginResult
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TiktokPublisherRuntimeAuthTest {
    @Test
    fun `cookie login persists cookie and restores previous cookie on failure`() = runBlocking {
        val gateway = RecordingTiktokGateway(
            loginResult = PublisherLoginResult(
                status = PublisherLoginStatus.SUCCESS,
                message = "抖音登录状态可用",
                account = PublisherLoginAccount(userId = "u1", name = "测试用户"),
            ),
            exportedCookie = "sessionid=valid; ttwid=token",
        )
        var savedConfig: TiktokPublisherConfig? = null
        val runtime = TiktokPublisherRuntime(
            loadConfig = { TiktokPublisherConfig(cookie = "sessionid=old") },
            gatewayFactory = { gateway },
            saveConfig = { _, config -> savedConfig = config },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())

        val result = runtime.loginByCookie("sessionid=valid; ttwid=token")

        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals("测试用户", result.account?.name)
        assertEquals("sessionid=valid; ttwid=token", savedConfig?.cookie)
        assertEquals("sessionid=valid; ttwid=token", runtime.exportCookie())
        assertTrue(PublisherLoginMethod.COOKIE in runtime.supportedLoginMethods)
        assertTrue(PublisherLoginMethod.QR_CODE in runtime.supportedLoginMethods)

        gateway.loginResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "Cookie 已失效")
        val failed = runtime.loginByCookie("sessionid=guest")
        assertEquals(PublisherLoginStatus.FAILED, failed.status)
        assertEquals("sessionid=valid; ttwid=token", runtime.currentConfig().cookie)
    }

    @Test
    fun `empty cookie fails and default qr gateway stays unsupported`() = runBlocking {
        val runtime = TiktokPublisherRuntime(
            loadConfig = { TiktokPublisherConfig() },
            gatewayFactory = { RecordingTiktokGateway() },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())

        val empty = runtime.loginByCookie("   ")
        assertEquals(PublisherLoginStatus.FAILED, empty.status)
        assertTrue(empty.message.contains("Cookie"))

        val missingSession = runtime.loginByCookie("ttwid=device-only")
        assertEquals(PublisherLoginStatus.FAILED, missingSession.status)
        assertTrue(missingSession.message.contains("sessionid"))

        val qr = runtime.loginByQrCode(onQrCode = {}, onStatusChanged = {})
        assertEquals(PublisherLoginStatus.UNSUPPORTED, qr.status)
        assertTrue(qr.message.contains("二维码"))
    }

    @Test
    fun `json cookie login is accepted`() = runBlocking {
        val gateway = RecordingTiktokGateway(
            loginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "抖音登录状态可用"),
            exportedCookie = "sessionid=from-json; ttwid=token",
        )
        var savedConfig: TiktokPublisherConfig? = null
        val runtime = TiktokPublisherRuntime(
            loadConfig = { TiktokPublisherConfig() },
            gatewayFactory = { gateway },
            saveConfig = { _, config -> savedConfig = config },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())

        val result = runtime.loginByCookie(
            """[{"name":"sessionid","value":"from-json"},{"name":"ttwid","value":"token"}]""",
        )

        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals("sessionid=from-json; ttwid=token", savedConfig?.cookie)
    }

    @Test
    fun `startup login check does not pause polling`() = runBlocking {
        val gateway = RecordingTiktokGateway(
            loginResult = PublisherLoginResult(PublisherLoginStatus.FAILED, "Cookie 已失效"),
        )
        val runtime = TiktokPublisherRuntime(
            loadConfig = {
                TiktokPublisherConfig(
                    pollingEnabled = true,
                    maxConsecutiveLoginFailures = 1,
                    cookie = "sessionid=expired",
                )
            },
            gatewayFactory = { gateway },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())
        runtime.onStart()
        repeat(2) { runtime.checkLoginState() }

        assertEquals(3, gateway.loginCheckCount)
        assertFalse(runtime.isPollingPaused())
    }

    @Test
    fun `plugin delegates cookie login to runtime`() = runBlocking {
        val gateway = RecordingTiktokGateway(
            loginResult = PublisherLoginResult(PublisherLoginStatus.SUCCESS, "抖音登录状态可用"),
            exportedCookie = "sessionid=ok",
        )
        val plugin = TiktokPublisherPlugin(
            loadConfig = { TiktokPublisherConfig() },
            gatewayFactory = { gateway },
            taskScheduler = ManualTaskScheduler(),
        )
        plugin.onLoad(testContext())

        val result = plugin.loginByCookie("sessionid=ok")
        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals(setOf(PublisherLoginMethod.COOKIE, PublisherLoginMethod.QR_CODE), plugin.supportedLoginMethods)
        assertTrue(plugin.supportsCookieExport)
        assertEquals("sessionid=ok", plugin.exportCookie())
    }

    @Test
    fun `qr login persists cookie and bootstraps like cookie login`() = runBlocking {
        val gateway = RecordingTiktokGateway(
            loginResult = PublisherLoginResult(
                status = PublisherLoginStatus.SUCCESS,
                message = "抖音登录状态可用",
                account = PublisherLoginAccount(userId = "u-qr", name = "扫码用户"),
            ),
            exportedCookie = "sessionid=qr-session; ttwid=token",
            qrLoginOutcome = TiktokQrLoginOutcome(
                result = PublisherLoginResult(
                    status = PublisherLoginStatus.SUCCESS,
                    message = "扫码登录成功",
                    account = PublisherLoginAccount(userId = "u-qr", name = "扫码用户"),
                ),
                cookieHeader = "sessionid=qr-session; ttwid=token",
            ),
        )
        var savedConfig: TiktokPublisherConfig? = null
        val runtime = TiktokPublisherRuntime(
            loadConfig = { TiktokPublisherConfig() },
            gatewayFactory = { gateway },
            saveConfig = { _, config -> savedConfig = config },
            taskScheduler = ManualTaskScheduler(),
        )
        runtime.onLoad(testContext())

        val result = runtime.loginByQrCode(onQrCode = {}, onStatusChanged = {})
        assertEquals(PublisherLoginStatus.SUCCESS, result.status)
        assertEquals("扫码用户", result.account?.name)
        assertEquals("sessionid=qr-session; ttwid=token", savedConfig?.cookie)
        assertEquals("sessionid=qr-session; ttwid=token", runtime.exportCookie())
    }

}

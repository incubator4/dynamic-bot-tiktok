package com.incubator4.dynamic.tiktok

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.LiveStatus
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import java.net.InetSocketAddress
import java.net.URI
import java.util.Base64
import java.net.http.HttpClient
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TiktokClientTest {
    @Test
    fun `check login state reads account from passport endpoint`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/passport/web/account/info/") { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            val body = if (cookie.contains("sessionid=valid")) {
                """{"message":"success","data":{"user_id_str":"u1","screen_name":"登录用户"}}"""
            } else {
                """{"message":"success","data":{}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val uri = URI.create("http://127.0.0.1:${server.address.port}/passport/web/account/info/")
            val httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build()
            val loggedIn = TiktokClient(
                config = TiktokPublisherConfig(cookie = "sessionid=valid; ttwid=token"),
                httpClient = httpClient,
                accountInfoUri = uri,
            ).checkLoginState()
            assertEquals(PublisherLoginStatus.SUCCESS, loggedIn.status)
            assertEquals("u1", loggedIn.account?.userId)

            val guest = TiktokClient(
                config = TiktokPublisherConfig(cookie = "sessionid=guest"),
                httpClient = httpClient,
                accountInfoUri = uri,
            ).checkLoginState()
            assertEquals(PublisherLoginStatus.FAILED, guest.status)
            assertTrue(guest.message.contains("未登录") || guest.message.contains("失效"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `fetch live snapshot parses user homepage json`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/user/") { exchange ->
            val body = """{"user_info":{"sec_uid":"MS4w","nickname":"主播","live_status":1,"unique_id":"rid1","room_id_str":"9"}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = URI.create("http://127.0.0.1:${server.address.port}/user/")
            val snapshot = TiktokClient(
                config = TiktokPublisherConfig(cookie = "sessionid=valid"),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                userProfileUriBuilder = { userId -> URI.create("${base}$userId") },
            ).fetchLiveSnapshot("MS4w")
            assertEquals(LiveStatus.OPEN, snapshot.status)
            assertEquals("MS4w", snapshot.userId)
            assertEquals("rid1", snapshot.webRid)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `missing cookie does not request account info`() = runBlocking {
        val result = TiktokClient(TiktokPublisherConfig(cookie = "   ")).checkLoginState()
        assertEquals(PublisherLoginStatus.FAILED, result.status)
        assertEquals("抖音 Cookie 未配置", result.message)
    }

    @Test
    fun `cookie without login session is rejected`() = runBlocking {
        val result = TiktokClient(TiktokPublisherConfig(cookie = "ttwid=device-only")).checkLoginState()
        assertEquals(PublisherLoginStatus.FAILED, result.status)
        assertTrue(result.message.contains("sessionid"))
    }

    @Test
    fun `html login page is treated as login failure`() {
        val result = TiktokClient(TiktokPublisherConfig(cookie = "sessionid=x"))
            .toLoginResult(200, "<!doctype html><html><title>登录</title></html>")
        assertEquals(PublisherLoginStatus.FAILED, result.status)
        assertTrue(result.message.contains("登录状态不可用"))
    }

    @Test
    fun `loginByQrCode completes after confirmed poll`() = runBlocking {
        val png = Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            ),
        )
        var pollCount = 0
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.createContext("/get_qrcode/") { exchange ->
            val body = """{"error_code":0,"data":{"token":"tok","qrcode":"$png","qrcode_index_url":"https://example.com/qr"}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/check_qrconnect/") { exchange ->
            pollCount += 1
            val body = if (pollCount == 1) {
                """{"error_code":0,"data":{"status":"1"}}"""
            } else {
                """{"error_code":0,"data":{"status":"3","redirect_url":"http://127.0.0.1:${server.address.port}/callback"}}"""
            }
            val bytes = body.toByteArray()
            // set login cookie on confirm
            if (pollCount > 1) {
                exchange.responseHeaders.add("Set-Cookie", "sessionid=qr-ok; Path=/; Domain=127.0.0.1")
            }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/callback") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "sessionid=qr-ok; Path=/")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.createContext("/passport/web/account/info/") { exchange ->
            val body = """{"message":"success","data":{"user_id_str":"qr-user","screen_name":"扫码账号"}}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val outcome = TiktokClient(
                config = TiktokPublisherConfig(),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                accountInfoUri = URI.create("$base/passport/web/account/info/"),
                homeUri = URI.create("$base/"),
                qrCreateUri = URI.create("$base/get_qrcode/"),
                qrCheckUriBuilder = { token, _ -> URI.create("$base/check_qrconnect/?token=$token") },
                qrPollIntervalMs = 10,
                qrTimeoutMs = 2_000,
            ).loginByQrCode(
                onQrCode = { challenge ->
                    assertTrue(!challenge.qrContent.isNullOrBlank() || challenge.qrImageBytes?.isNotEmpty() == true)
                },
                onStatusChanged = {},
            )
            assertEquals(PublisherLoginStatus.SUCCESS, outcome.result.status)
            assertTrue(outcome.cookieHeader.orEmpty().contains("sessionid"))
        } finally {
            server.stop(0)
        }
    }

}

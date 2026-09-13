package com.incubator4.dynamic.tiktok

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.plugin.PublisherLoginStatus
import java.net.InetSocketAddress
import java.net.URI
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
}

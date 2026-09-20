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
        server.createContext("/aweme/v1/passport/account/info/v2/") { exchange ->
            val cookie = exchange.requestHeaders.getFirst("Cookie").orEmpty()
            val query = exchange.requestURI.query.orEmpty()
            val body = when {
                query.contains("aid=6383") ->
                    """{"message":"error","data":{"error_code":1105,"description":"该应用无权限"}}"""
                !query.contains("aid=2906") ->
                    """{"message":"error","data":{"error_code":1041,"description":"用户不存在"}}"""
                cookie.contains("sessionid=valid") ->
                    """{"status_code":0,"user":{"sec_uid":"u1","nickname":"登录用户"}}"""
                else ->
                    """{"status_code":0,"user":{}}"""
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val uri = URI.create(
                "http://127.0.0.1:${server.address.port}/aweme/v1/passport/account/info/v2/?aid=2906",
            )
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

            val missingAid = TiktokClient(
                config = TiktokPublisherConfig(cookie = "sessionid=valid; ttwid=token"),
                httpClient = httpClient,
                accountInfoUri = URI.create(
                    "http://127.0.0.1:${server.address.port}/aweme/v1/passport/account/info/v2/",
                ),
            ).checkLoginState()
            assertEquals(PublisherLoginStatus.FAILED, missingAid.status)
            assertTrue(missingAid.message.contains("Cookie"))
            assertTrue(missingAid.message.contains("sessionid"))

            val webAidDenied = TiktokClient(
                config = TiktokPublisherConfig(cookie = "sessionid=valid; ttwid=token"),
                httpClient = httpClient,
                accountInfoUri = URI.create(
                    "http://127.0.0.1:${server.address.port}/aweme/v1/passport/account/info/v2/?aid=6383",
                ),
            ).checkLoginState()
            assertEquals(PublisherLoginStatus.FAILED, webAidDenied.status)
            assertTrue(webAidDenied.message.contains("无权限"))
            assertTrue(webAidDenied.message.contains("扫码"))
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
    fun `expand response prefers canonical douyin url`() {
        val client = TiktokClient(TiktokPublisherConfig(cookie = "sessionid=valid"))
        assertEquals(
            "https://www.douyin.com/video/7123456789012345678",
            client.parseExpandResponse(
                200,
                "https://www.iesdouyin.com/share/video/7123456789012345678",
                "<html></html>",
            ),
        )
        assertEquals(
            "https://www.douyin.com/user/MS4wLjABAAAAtest",
            client.parseExpandResponse(
                200,
                "https://v.douyin.com/iPxxxx/",
                """<link rel="canonical" href="https://www.douyin.com/user/MS4wLjABAAAAtest">""",
            ),
        )
    }

    @Test
    fun `fetch aweme snapshot parses video page json`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/share/") { exchange ->
            val body = """
                {
                  "aweme_id":"7123456789012345678",
                  "desc":"作品",
                  "author":{"sec_uid":"MS4w","nickname":"作者","avatar_thumb":{"url_list":["https://example.com/a.png"]}},
                  "video":{"cover":{"url_list":["https://example.com/c.jpg"]}}
                }
            """.trimIndent()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val shareBase = URI.create("http://127.0.0.1:${server.address.port}/share/")
            val snapshot = TiktokClient(
                config = TiktokPublisherConfig(cookie = "sessionid=valid", requestIntervalSeconds = 0.0),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                shareAwemeUriBuilder = { awemeId, _ -> URI.create("${shareBase}$awemeId") },
                awemeUriBuilder = { _, _ -> URI.create("http://127.0.0.1:${server.address.port}/missing") },
            ).fetchAwemeSnapshot("7123456789012345678")
            assertEquals("7123456789012345678", snapshot?.awemeId)
            assertEquals("作品", snapshot?.description)
            assertEquals("MS4w", snapshot?.authorUserId)
            assertEquals("作者", snapshot?.authorName)
            assertEquals("https://example.com/c.jpg", snapshot?.coverUrl)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `parse aweme response fills author and cover from html meta`() {
        val client = TiktokClient(TiktokPublisherConfig(cookie = "sessionid=valid"))
        val html = """
            <html>
              <title>兜底标题</title>
              <meta property="og:image" content="https://example.com/og.jpg">
              <meta name="author" content="页面作者">
              <meta property="og:title" content="作品标题">
            </html>
        """.trimIndent()
        val snapshot = client.parseAwemeResponse(200, html, "7123456789012345678")
        assertEquals("7123456789012345678", snapshot?.awemeId)
        assertEquals("作品标题", snapshot?.description)
        assertEquals("页面作者", snapshot?.authorName)
        assertEquals("https://example.com/og.jpg", snapshot?.coverUrl)
    }

    @Test
    fun `fetch aweme snapshot fills cover from desktop page when share page is incomplete`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/share/") { exchange ->
            val body = """{"awemeId":"7123456789012345678","desc":"只有文案"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/video/") { exchange ->
            val body = """
                {
                  "awemeId":"7123456789012345678",
                  "desc":"只有文案",
                  "authorInfo":{"secUid":"MS4wFill","nickname":"补全作者"},
                  "video":{"coverUrlList":["https://example.com/filled.jpg"]}
                }
            """.trimIndent()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val port = server.address.port
            val snapshot = TiktokClient(
                config = TiktokPublisherConfig(cookie = "sessionid=valid", requestIntervalSeconds = 0.0),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                shareAwemeUriBuilder = { awemeId, _ -> URI.create("http://127.0.0.1:$port/share/$awemeId") },
                awemeUriBuilder = { awemeId, _ -> URI.create("http://127.0.0.1:$port/video/$awemeId") },
            ).fetchAwemeSnapshot("7123456789012345678")
            assertEquals("MS4wFill", snapshot?.authorUserId)
            assertEquals("补全作者", snapshot?.authorName)
            assertEquals("https://example.com/filled.jpg", snapshot?.coverUrl)
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
            assertEquals("POST", exchange.requestMethod)
            val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            assertTrue(requestBody.contains("token="))
            assertTrue(requestBody.contains("is_frontier=true"))
            val body = if (pollCount == 1) {
                """{"error_code":0,"data":{"status":"new"}}"""
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
            val statuses = mutableListOf<PublisherLoginStatus>()
            val outcome = qrClient(base).loginByQrCode(
                onQrCode = { challenge ->
                    assertTrue(!challenge.qrContent.isNullOrBlank() || challenge.qrImageBytes?.isNotEmpty() == true)
                },
                onStatusChanged = { statuses += it.status },
            )
            assertEquals(PublisherLoginStatus.SUCCESS, outcome.result.status)
            assertTrue(outcome.cookieHeader.orEmpty().contains("sessionid"))
            assertTrue(PublisherLoginStatus.PENDING in statuses)
            assertTrue(PublisherLoginStatus.SUCCESS in statuses)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `loginByQrCode expires when poll reports expired`() = runBlocking {
        val server = startQrMockServer(checkBody = """{"error_code":0,"data":{"status":"5"}}""")
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val statuses = mutableListOf<PublisherLoginStatus>()
            val outcome = qrClient(base, timeoutMs = 500).loginByQrCode(
                onQrCode = {},
                onStatusChanged = { statuses += it.status },
            )
            assertEquals(PublisherLoginStatus.EXPIRED, outcome.result.status)
            assertTrue(outcome.result.message.contains("过期"))
            assertTrue(PublisherLoginStatus.EXPIRED in statuses)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `loginByQrCode times out while waiting`() = runBlocking {
        val server = startQrMockServer(checkBody = """{"error_code":0,"data":{"status":"1"}}""")
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val outcome = qrClient(base, pollIntervalMs = 20, timeoutMs = 80).loginByQrCode(
                onQrCode = {},
                onStatusChanged = {},
            )
            assertEquals(PublisherLoginStatus.EXPIRED, outcome.result.status)
            assertTrue(outcome.result.message.contains("超时"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `loginByQrCode treats html create response as risk control`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.close()
        }
        server.createContext("/get_qrcode/") { exchange ->
            val body = "<!doctype html><html><head><script></script></head><body>login</body></html>"
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val outcome = qrClient(base).loginByQrCode(onQrCode = {}, onStatusChanged = {})
            assertEquals(PublisherLoginStatus.FAILED, outcome.result.status)
            assertTrue(
                outcome.result.message.contains("风控") ||
                    outcome.result.message.contains("网页") ||
                    outcome.result.message.contains("Cookie"),
            )
        } finally {
            server.stop(0)
        }
    }

    private fun qrClient(
        base: String,
        pollIntervalMs: Long = 10,
        timeoutMs: Long = 2_000,
    ): TiktokClient {
        return TiktokClient(
            config = TiktokPublisherConfig(),
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            accountInfoUri = URI.create("$base/passport/web/account/info/"),
            homeUri = URI.create("$base/"),
            ssoHomeUri = URI.create("$base/"),
            loginBootstrapUri = URI.create("$base/login/"),
            qrCreateUri = URI.create("$base/get_qrcode/"),
            qrCheckUriBuilder = { _ -> URI.create("$base/check_qrconnect/") },
            ttwidRegisterUri = null,
            qrPollIntervalMs = pollIntervalMs,
            qrTimeoutMs = timeoutMs,
        )
    }

    private fun startQrMockServer(checkBody: String): HttpServer {
        val png = Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            ),
        )
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
            val bytes = checkBody.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

}

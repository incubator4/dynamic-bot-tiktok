package com.incubator4.dynamic.tiktok

import top.colter.dynamic.core.data.LiveStatus
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiktokLiveTest {
    @Test
    fun `parse iesdouyin user info when living`() {
        val snapshot = parseTiktokLiveSnapshot(
            """
            {
              "status_code": 0,
              "user_info": {
                "sec_uid": "MS4wLjABAAAAtest",
                "uid": 123,
                "nickname": "主播",
                "unique_id": "host1",
                "live_status": 1,
                "room_id": 777,
                "room_id_str": "777",
                "avatar_thumb": { "url_list": ["https://example.com/avatar.png"] },
                "room_data": "{\"status\":2,\"title\":\"晚安\",\"cover\":{\"url_list\":[\"https://example.com/cover.jpg\"]}}"
              }
            }
            """.trimIndent(),
            fallbackUserId = "fallback",
        )

        assertEquals("MS4wLjABAAAAtest", snapshot.userId)
        assertEquals(LiveStatus.OPEN, snapshot.status)
        assertEquals("host1", snapshot.webRid)
        assertEquals("host1", snapshot.roomId)
        assertEquals("晚安", snapshot.title)
        assertEquals("https://example.com/cover.jpg", snapshot.coverUrl)
        assertEquals("https://live.douyin.com/host1", liveRoomLink(snapshot.roomId, snapshot.userId))
        assertEquals("主播", snapshot.nickname)
        assertEquals("https://example.com/avatar.png", snapshot.avatarUrl)
        assertTrue(snapshot.profileFound)
    }

    @Test
    fun `parse user info when not living`() {
        val snapshot = parseTiktokLiveSnapshot(
            """
            {
              "user": {
                "sec_uid": "MS4wLjABAAAAoff",
                "nickname": "休息中",
                "live_status": 0,
                "room_id": 0
              }
            }
            """.trimIndent(),
            fallbackUserId = "fallback",
        )

        assertEquals(LiveStatus.CLOSE, snapshot.status)
        assertEquals("", snapshot.roomId)
        assertEquals("https://www.douyin.com/user/MS4wLjABAAAAoff", liveRoomLink(snapshot.userId, snapshot.userId))
    }

    @Test
    fun `extract render data from user homepage html`() {
        val payload = """{"app":{"user":{"user":{"sec_uid":"MS4wHtml","nickname":"页面主播","live_status":1,"unique_id":"htmlrid","room_id_str":"999"}}}}"""
        val encoded = URLEncoder.encode(payload, StandardCharsets.UTF_8)
        val html = """<html><script id="RENDER_DATA" type="application/json">$encoded</script></html>"""
        val extracted = extractTiktokEmbeddedPayload(html)
        val snapshot = parseTiktokLiveSnapshot(extracted, "fallback")
        assertEquals(LiveStatus.OPEN, snapshot.status)
        assertEquals("htmlrid", snapshot.webRid)
        assertEquals("MS4wHtml", snapshot.userId)
    }

    @Test
    fun `risk control json throws blocked exception`() {
        val error = assertFailsWith<TiktokBlockedException> {
            parseTiktokLiveSnapshot(
                """{"status_code":461,"status_msg":"请求被风控拦截"}""",
                fallbackUserId = "u1",
            )
        }
        assertTrue(error.message!!.contains("风控"))
    }

    @Test
    fun `extract html meta author and cover`() {
        val meta = extractTiktokHtmlMeta(
            """
            <html>
              <head>
                <title>页标题</title>
                <meta property="og:title" content="作品标题">
                <meta property="og:image" content="//example.com/og.jpg">
                <meta name="author" content="页面作者">
              </head>
            </html>
            """.trimIndent(),
        )
        assertEquals("作品标题", meta.title)
        assertEquals("页面作者", meta.authorName)
        assertEquals("https://example.com/og.jpg", meta.coverUrl)
    }

    @Test
    fun `missing payload throws api exception`() {
        assertFailsWith<TiktokApiException> {
            extractTiktokEmbeddedPayload("<html><title>空白</title></html>")
        }
        assertNull(parseTiktokEpochSeconds(0))
        assertEquals(1_700_000_000L, parseTiktokEpochSeconds(1_700_000_000_000L))
    }
}

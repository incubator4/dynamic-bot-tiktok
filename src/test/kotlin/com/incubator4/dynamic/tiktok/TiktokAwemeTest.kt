package com.incubator4.dynamic.tiktok

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiktokAwemeTest {
    @Test
    fun `parse video aweme from nested json`() {
        val snapshot = assertNotNull(
            parseTiktokAwemeSnapshot(
                """
                {
                  "aweme": {
                    "detail": {
                      "aweme_id": "7123456789012345678",
                      "desc": "测试作品",
                      "create_time": 1700000000,
                      "author": {
                        "sec_uid": "MS4wLjABAAAAtest",
                        "nickname": "作者",
                        "avatar_thumb": { "url_list": ["https://example.com/a.png"] }
                      },
                      "video": {
                        "duration": 15200,
                        "cover": { "url_list": ["https://example.com/c.jpg"] }
                      },
                      "statistics": {
                        "digg_count": 12,
                        "comment_count": 3,
                        "play_count": 100
                      }
                    }
                  }
                }
                """.trimIndent(),
                fallbackId = "fallback",
            ),
        )

        assertEquals("7123456789012345678", snapshot.awemeId)
        assertEquals("测试作品", snapshot.description)
        assertEquals("MS4wLjABAAAAtest", snapshot.authorUserId)
        assertEquals("作者", snapshot.authorName)
        assertEquals("https://example.com/c.jpg", snapshot.coverUrl)
        assertEquals(15, snapshot.durationSeconds)
        assertEquals(12, snapshot.likeCount)
        assertEquals(false, snapshot.isNote)
    }

    @Test
    fun `parse note aweme from item list`() {
        val snapshot = assertNotNull(
            parseTiktokAwemeSnapshot(
                """
                {
                  "item_list": [
                    {
                      "aweme_id": "7987654321098765432",
                      "desc": "图集",
                      "aweme_type": 68,
                      "author": { "sec_uid": "MS4wNote", "nickname": "图集作者" },
                      "images": [
                        { "url_list": ["https://example.com/1.jpg"] }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
                fallbackId = "fallback",
            ),
        )

        assertEquals("7987654321098765432", snapshot.awemeId)
        assertTrue(snapshot.isNote)
        assertEquals("https://example.com/1.jpg", snapshot.coverUrl)
        assertNull(snapshot.durationSeconds)
    }

    @Test
    fun `extract share html redirect target`() {
        val html = """
            <html>
              <link rel="canonical" href="https://www.iesdouyin.com/share/video/7123456789012345678">
            </html>
        """.trimIndent()
        assertEquals(
            "https://www.douyin.com/video/7123456789012345678",
            extractTiktokShareRedirectTarget(html),
        )
        assertEquals(
            "https://www.douyin.com/user/MS4wLjABAAAAtest",
            extractTiktokShareRedirectTarget(
                """<script>window.location.href="https://www.douyin.com/user/MS4wLjABAAAAtest";</script>""",
            ),
        )
    }

    @Test
    fun `parse aweme from encoded render data`() {
        val payload = """{"awemeDetail":{"aweme_id":"7123456789012345678","desc":"页面作品","author":{"sec_uid":"MS4wHtml","nickname":"页面作者"}}}"""
        val encoded = URLEncoder.encode(payload, StandardCharsets.UTF_8)
        val html = """<html><script id="RENDER_DATA" type="application/json">$encoded</script></html>"""
        val extracted = extractTiktokEmbeddedPayload(html)
        val snapshot = assertNotNull(parseTiktokAwemeSnapshot(extracted, "fallback"))
        assertEquals("页面作品", snapshot.description)
        assertEquals("MS4wHtml", snapshot.authorUserId)
    }
}

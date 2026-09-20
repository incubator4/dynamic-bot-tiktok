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

    @Test
    fun `parse modern player json authorInfo and coverUrlList`() {
        val snapshot = assertNotNull(
            parseTiktokAwemeSnapshot(
                """
                {
                  "__DEFAULT_SCOPE__": {
                    "webapp.video-detail": {
                      "awemeDetail": {
                        "awemeId": "7123456789012345678",
                        "desc": "新页面作品",
                        "authorUserId": 987654,
                        "authorInfo": {
                          "secUid": "MS4wModern",
                          "nickname": "新作者",
                          "avatarUri": "tos-cn-avt-0015/avatar",
                          "avatarThumb": { "urlList": ["https://example.com/modern-avatar.png"] }
                        },
                        "video": {
                          "cover": "tos-cn-p-0015/cover",
                          "coverUrlList": ["https://example.com/modern-cover.jpg"],
                          "originCoverUrlList": ["https://example.com/modern-origin.jpg"],
                          "duration": 18000
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
                fallbackId = "fallback",
            ),
        )

        assertEquals("7123456789012345678", snapshot.awemeId)
        assertEquals("新页面作品", snapshot.description)
        assertEquals("MS4wModern", snapshot.authorUserId)
        assertEquals("新作者", snapshot.authorName)
        assertEquals("https://example.com/modern-avatar.png", snapshot.authorAvatarUrl)
        assertEquals("https://example.com/modern-origin.jpg", snapshot.coverUrl)
        assertEquals(18, snapshot.durationSeconds)
        assertTrue(snapshot.hasPreviewIdentity())
    }

    @Test
    fun `prefer rich aweme over shallow seo sibling`() {
        val snapshot = assertNotNull(
            parseTiktokAwemeSnapshot(
                """
                {
                  "seo": {
                    "awemeId": "7123456789012345678",
                    "desc": "只有标题的 SEO 残缺数据"
                  },
                  "awemeDetail": {
                    "awemeId": "7123456789012345678",
                    "desc": "完整作品",
                    "authorInfo": { "secUid": "MS4wRich", "nickname": "完整作者" },
                    "video": { "coverUrlList": ["https://example.com/rich-cover.jpg"] }
                  }
                }
                """.trimIndent(),
                fallbackId = "7123456789012345678",
            ),
        )

        assertEquals("完整作品", snapshot.description)
        assertEquals("MS4wRich", snapshot.authorUserId)
        assertEquals("完整作者", snapshot.authorName)
        assertEquals("https://example.com/rich-cover.jpg", snapshot.coverUrl)
    }

    @Test
    fun `extract universal data and router data payloads`() {
        val universal = """{"awemeDetail":{"awemeId":"7123456789012345678","desc":"通用数据","authorInfo":{"secUid":"MS4wUni","nickname":"通用作者"},"video":{"coverUrlList":["https://example.com/uni.jpg"]}}}"""
        val html = """<html><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">$universal</script></html>"""
        val extracted = extractTiktokEmbeddedPayload(html)
        val snapshot = assertNotNull(parseTiktokAwemeSnapshot(extracted, "fallback"))
        assertEquals("通用作者", snapshot.authorName)
        assertEquals("https://example.com/uni.jpg", snapshot.coverUrl)

        val router = """{"loaderData":{"video_(id)/page":{"videoInfoRes":{"item_list":[{"aweme_id":"7123456789012345678","desc":"分享页","author":{"sec_uid":"MS4wShare","nickname":"分享作者"},"video":{"cover":{"url_list":["https://example.com/share-cover.jpg"]}}}]}}}}"""
        val routerHtml = """<html><script>window._ROUTER_DATA = $router;</script></html>"""
        val routerSnapshot = assertNotNull(parseTiktokAwemeSnapshot(extractTiktokEmbeddedPayload(routerHtml), "fallback"))
        assertEquals("分享作者", routerSnapshot.authorName)
        assertEquals("https://example.com/share-cover.jpg", routerSnapshot.coverUrl)
    }

    @Test
    fun `html meta fills missing author and cover`() {
        val snapshot = TiktokAwemeSnapshot(
            awemeId = "7123456789012345678",
            description = "只有文案",
        ).enrichWithHtmlMeta(
            TiktokHtmlMeta(
                title = "页面标题",
                coverUrl = "https://example.com/og-cover.jpg",
                authorName = "OG 作者",
            ),
            fallbackId = "7123456789012345678",
        )
        assertEquals("只有文案", snapshot.description)
        assertEquals("OG 作者", snapshot.authorName)
        assertEquals("https://example.com/og-cover.jpg", snapshot.coverUrl)

        val fromMeta = assertNotNull(
            TiktokHtmlMeta(
                title = "封面标题",
                coverUrl = "https://example.com/meta.jpg",
                authorName = "页面作者",
            ).toAwemeSnapshot("7123456789012345678"),
        )
        assertEquals("封面标题", fromMeta.description)
        assertEquals("页面作者", fromMeta.authorName)
        assertEquals("https://example.com/meta.jpg", fromMeta.coverUrl)
    }
}

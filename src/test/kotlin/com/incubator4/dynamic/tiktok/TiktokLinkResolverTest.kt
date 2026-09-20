package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TiktokLinkResolverTest {
    private val platformId = PlatformId.of(TIKTOK_PLATFORM_ID)
    private val awemeId = "7123456789012345678"
    private val userId = "MS4wLjABAAAAtest"

    @Test
    fun `parse video note user and share links`() = runBlocking {
        val resolver = TiktokLinkResolver(platformId)

        val video = assertNotNull(resolver.parseLink("https://www.douyin.com/video/$awemeId"))
        val note = assertNotNull(resolver.parseLink("https://www.douyin.com/note/$awemeId。"))
        val userLink = assertNotNull(resolver.parseLink("https://www.douyin.com/user/$userId"))
        val share = assertNotNull(resolver.parseLink("https://www.iesdouyin.com/share/video/$awemeId"))
        val modal = assertNotNull(
            resolver.parseLink("https://www.douyin.com/user/$userId?modal_id=$awemeId"),
        )

        assertEquals(LinkKinds.VIDEO, video.kind)
        assertEquals(awemeId, video.targetId)
        assertEquals("https://www.douyin.com/video/$awemeId", video.normalizedUrl)
        assertEquals(LinkKinds.DYNAMIC, note.kind)
        assertEquals("https://www.douyin.com/note/$awemeId", note.normalizedUrl)
        assertEquals(LinkKinds.USER, userLink.kind)
        assertEquals(userId, userLink.targetId)
        assertEquals(LinkKinds.VIDEO, share.kind)
        assertEquals(LinkKinds.VIDEO, modal.kind)
        assertEquals(awemeId, modal.targetId)
        assertTrue(resolver.matchesLink("https://v.douyin.com/iPxxxx/"))
        assertFalse(resolver.matchesLink("https://www.tiktok.com/@user/video/1"))
        assertNull(resolver.parseLink("https://www.tiktok.com/@user/video/1"))
    }

    @Test
    fun `short link parse succeeds but resolve returns chinese failure`() = runBlocking {
        val resolver = TiktokLinkResolver(platformId)

        val parsed = assertNotNull(resolver.parseLink("https://v.douyin.com/iPxxxx"))
        assertEquals(TIKTOK_SHORT_LINK_KIND, parsed.kind)
        assertEquals("iPxxxx", parsed.targetId)
        assertEquals("https://v.douyin.com/iPxxxx", parsed.sourceUrl)

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Failed)
        assertTrue(resolution.reason.contains("短链"))
        assertTrue(resolution.reason.contains("完整"))
    }

    @Test
    fun `resolve video preview from url without fetching`() = runBlocking {
        val resolver = TiktokLinkResolver(platformId)
        val parsed = assertNotNull(resolver.parseLink("https://www.douyin.com/video/$awemeId"))

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Preview)
        assertEquals(LinkKinds.VIDEO, resolution.preview.kind)
        assertEquals("抖音视频 $awemeId", resolution.preview.title)
        assertEquals("视频", resolution.preview.badge)
        assertEquals("https://www.douyin.com/video/$awemeId", resolution.preview.url)
        assertNull(resolution.preview.publisher)
    }

    @Test
    fun `resolve note preview as gallery without fetching`() = runBlocking {
        val resolver = TiktokLinkResolver(platformId)
        val parsed = assertNotNull(resolver.parseLink("https://www.douyin.com/note/$awemeId"))

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Preview)
        assertEquals(LinkKinds.DYNAMIC, resolution.preview.kind)
        assertEquals("图集", resolution.preview.badge)
        assertEquals("抖音图集 $awemeId", resolution.preview.title)
        assertNull(resolution.preview.durationSeconds)
    }

    @Test
    fun `resolve user preview from url without fetching`() = runBlocking {
        val resolver = TiktokLinkResolver(platformId)
        val parsed = assertNotNull(resolver.parseLink("https://www.douyin.com/user/$userId"))

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Preview)
        assertEquals(LinkKinds.USER, resolution.preview.kind)
        assertEquals("抖音用户 $userId", resolution.preview.title)
        assertEquals(userId, resolution.preview.publisher?.externalId)
    }

    @Test
    fun `unsupported parsed kind returns chinese failure`() = runBlocking {
        val resolver = TiktokLinkResolver(platformId)
        val parsed = assertNotNull(resolver.parseLink("https://www.douyin.com/video/$awemeId"))
            .copy(kind = "unknown")

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Failed)
        assertTrue(resolution.reason.contains("不支持的抖音链接类型"))
    }

    @Test
    fun `plugin delegates link matching after load`() = runBlocking {
        val plugin = TiktokPublisherPlugin(
            loadConfig = { TiktokPublisherConfig() },
            gatewayFactory = { RecordingTiktokGateway() },
            taskScheduler = ManualTaskScheduler(),
        )
        plugin.onLoad(testContext())

        assertTrue(plugin.matchesLink("https://www.douyin.com/video/$awemeId"))
        val parsed = assertNotNull(plugin.parseLink("https://www.douyin.com/user/$userId"))
        assertEquals(LinkKinds.USER, parsed.kind)
        val resolution = plugin.resolveLink(parsed)
        assertTrue(resolution is LinkResolution.Preview)
        assertEquals("用户", resolution.preview.badge)
    }
}

package com.incubator4.dynamic.tiktok

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.core.data.LiveStatus
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
    private val aweme = TiktokAwemeSnapshot(
        awemeId = "7123456789012345678",
        description = "测试作品",
        authorUserId = "MS4wLjABAAAAtest",
        authorName = "测试作者",
        authorAvatarUrl = "https://example.com/avatar.png",
        coverUrl = "https://example.com/cover.jpg",
        durationSeconds = 15,
        likeCount = 12000,
        playCount = 100000,
    )
    private val user = TiktokLiveSnapshot(
        userId = "MS4wLjABAAAAtest",
        status = LiveStatus.CLOSE,
        nickname = "测试作者",
        avatarUrl = "https://example.com/avatar.png",
        uniqueId = "tester",
        signature = "简介",
        profileFound = true,
    )

    @Test
    fun `parse video note user and share links`() = runBlocking {
        val resolver = resolver()

        val video = assertNotNull(resolver.parseLink("https://www.douyin.com/video/7123456789012345678"))
        val note = assertNotNull(resolver.parseLink("https://www.douyin.com/note/7123456789012345678。"))
        val userLink = assertNotNull(resolver.parseLink("https://www.douyin.com/user/MS4wLjABAAAAtest"))
        val share = assertNotNull(resolver.parseLink("https://www.iesdouyin.com/share/video/7123456789012345678"))
        val modal = assertNotNull(
            resolver.parseLink("https://www.douyin.com/user/MS4wLjABAAAAtest?modal_id=7123456789012345678"),
        )

        assertEquals(LinkKinds.VIDEO, video.kind)
        assertEquals("7123456789012345678", video.targetId)
        assertEquals("https://www.douyin.com/video/7123456789012345678", video.normalizedUrl)
        assertEquals(LinkKinds.DYNAMIC, note.kind)
        assertEquals("https://www.douyin.com/note/7123456789012345678", note.normalizedUrl)
        assertEquals(LinkKinds.USER, userLink.kind)
        assertEquals("MS4wLjABAAAAtest", userLink.targetId)
        assertEquals(LinkKinds.VIDEO, share.kind)
        assertEquals(LinkKinds.VIDEO, modal.kind)
        assertEquals("7123456789012345678", modal.targetId)
        assertTrue(resolver.matchesLink("https://v.douyin.com/iPxxxx/"))
        assertFalse(resolver.matchesLink("https://www.tiktok.com/@user/video/1"))
        assertNull(resolver.parseLink("https://www.tiktok.com/@user/video/1"))
    }

    @Test
    fun `expand short link then parse video`() = runBlocking {
        val gateway = RecordingTiktokGateway()
        gateway.enqueueExpand(
            "https://v.douyin.com/iPxxxx",
            "https://www.iesdouyin.com/share/video/7123456789012345678",
        )
        val resolver = resolver(gateway)

        val parsed = assertNotNull(resolver.parseLink("https://v.douyin.com/iPxxxx"))
        assertEquals(LinkKinds.VIDEO, parsed.kind)
        assertEquals("7123456789012345678", parsed.targetId)
        assertEquals("https://v.douyin.com/iPxxxx", parsed.sourceUrl)
        assertEquals(listOf("https://v.douyin.com/iPxxxx"), gateway.expandedShortUrls)
    }

    @Test
    fun `resolve video preview`() = runBlocking {
        val gateway = RecordingTiktokGateway()
        gateway.enqueueAweme(aweme.awemeId, aweme)
        val resolver = resolver(gateway)
        val parsed = assertNotNull(resolver.parseLink("https://www.douyin.com/video/${aweme.awemeId}"))

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Preview)
        assertEquals(LinkKinds.VIDEO, resolution.preview.kind)
        assertEquals("测试作品", resolution.preview.title)
        assertEquals("视频", resolution.preview.badge)
        assertEquals(15, resolution.preview.durationSeconds)
        assertEquals("MS4wLjABAAAAtest", resolution.preview.publisher?.externalId)
        assertEquals("测试作者", resolution.preview.publisher?.name)
        assertEquals("https://example.com/cover.jpg", resolution.preview.cover?.uri)
        assertEquals("1.2万", resolution.preview.metrics.first { it.key == "like" }.display)
    }

    @Test
    fun `resolve note preview as gallery`() = runBlocking {
        val gateway = RecordingTiktokGateway()
        gateway.enqueueAweme(
            aweme.awemeId,
            aweme.copy(isNote = true, durationSeconds = null, description = "一组图"),
        )
        val resolver = resolver(gateway)
        val parsed = assertNotNull(resolver.parseLink("https://www.douyin.com/note/${aweme.awemeId}"))

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Preview)
        assertEquals(LinkKinds.DYNAMIC, resolution.preview.kind)
        assertEquals("图集", resolution.preview.badge)
        assertNull(resolution.preview.durationSeconds)
    }

    @Test
    fun `resolve user preview`() = runBlocking {
        val gateway = RecordingTiktokGateway()
        gateway.enqueueLive(user.userId, user)
        val resolver = resolver(gateway)
        val parsed = assertNotNull(resolver.parseLink("https://www.douyin.com/user/${user.userId}"))

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Preview)
        assertEquals(LinkKinds.USER, resolution.preview.kind)
        assertEquals("测试作者", resolution.preview.title)
        assertTrue(resolution.preview.description.contains("tester"))
        assertEquals(user.userId, resolution.preview.publisher?.externalId)
        assertEquals("测试作者", resolution.preview.publisher?.name)
        assertEquals("https://example.com/avatar.png", resolution.preview.cover?.uri)
    }

    @Test
    fun `missing aweme returns chinese failure`() = runBlocking {
        val resolver = resolver(RecordingTiktokGateway())
        val parsed = assertNotNull(resolver.parseLink("https://www.douyin.com/video/${aweme.awemeId}"))

        val resolution = resolver.resolveLink(parsed)

        assertTrue(resolution is LinkResolution.Failed)
        assertTrue(resolution.reason.contains("未找到抖音作品"))
    }

    @Test
    fun `plugin delegates link matching after load`() = runBlocking {
        val plugin = TiktokPublisherPlugin(
            loadConfig = { TiktokPublisherConfig() },
            gatewayFactory = { RecordingTiktokGateway() },
            taskScheduler = ManualTaskScheduler(),
        )
        plugin.onLoad(testContext())

        assertTrue(plugin.matchesLink("https://www.douyin.com/video/${aweme.awemeId}"))
        val parsed = assertNotNull(plugin.parseLink("https://www.douyin.com/user/${user.userId}"))
        assertEquals(LinkKinds.USER, parsed.kind)
    }

    private fun resolver(gateway: TiktokGateway = RecordingTiktokGateway()): TiktokLinkResolver {
        return TiktokLinkResolver(
            platformId = platformId,
            gatewayProvider = { gateway },
            requestFailureHandler = TiktokRequestFailureHandler(
                configProvider = { TiktokPublisherConfig() },
            ),
        )
    }
}

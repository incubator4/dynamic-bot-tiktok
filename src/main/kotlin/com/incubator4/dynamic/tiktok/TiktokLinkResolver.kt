package com.incubator4.dynamic.tiktok

import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.PublisherInfo
import top.colter.dynamic.core.data.PublisherKey
import top.colter.dynamic.core.data.PublisherKind
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.LinkPreview
import top.colter.dynamic.core.link.LinkResolution
import top.colter.dynamic.core.link.ParsedLink

internal class TiktokLinkResolver(
    private val platformId: PlatformId,
) {
    fun matchesLink(inputUrl: String): Boolean = matchesTiktokLink(inputUrl)

    suspend fun parseLink(inputUrl: String): ParsedLink? {
        return parseTiktokLink(inputUrl, platformId)
    }

    suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
        if (parsedLink.platformId != platformId) {
            return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "不支持的平台：${parsedLink.platformId.value}",
            )
        }
        if (parsedLink.kind == TIKTOK_SHORT_LINK_KIND) {
            return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "无法解析该抖音短链，请改用完整作品或用户主页链接",
            )
        }
        return when (parsedLink.kind) {
            LinkKinds.VIDEO, LinkKinds.DYNAMIC, LinkKinds.USER -> resolveLocalPreview(parsedLink)
            else -> LinkResolution.Failed(parsedLink, "不支持的抖音链接类型：${parsedLink.kind}")
        }
    }

    private fun resolveLocalPreview(parsedLink: ParsedLink): LinkResolution {
        val isNote = parsedLink.kind == LinkKinds.DYNAMIC
        val isUser = parsedLink.kind == LinkKinds.USER
        val title = when (parsedLink.kind) {
            LinkKinds.DYNAMIC -> "抖音图集 ${parsedLink.targetId}"
            LinkKinds.USER -> "抖音用户 ${parsedLink.targetId}"
            else -> "抖音视频 ${parsedLink.targetId}"
        }
        return LinkResolution.Preview(
            parsedLink = parsedLink,
            preview = LinkPreview(
                platformId = platformId,
                kind = parsedLink.kind,
                id = parsedLink.targetId,
                url = parsedLink.normalizedUrl,
                title = title,
                badge = when {
                    isNote -> "图集"
                    isUser -> "用户"
                    else -> "视频"
                },
                publisher = if (isUser) {
                    PublisherInfo(
                        key = PublisherKey.of(platformId.value, PublisherKind.USER, parsedLink.targetId),
                        name = title,
                        avatar = MediaRef(TIKTOK_DEFAULT_AVATAR, MediaKind.AVATAR),
                    )
                } else {
                    null
                },
            ),
        )
    }
}

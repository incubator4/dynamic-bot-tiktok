package com.incubator4.dynamic.tiktok

import top.colter.dynamic.core.data.DynamicMetric
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
    private val gatewayProvider: () -> TiktokGateway = { object : TiktokGateway {} },
) {
    private val gateway: TiktokGateway
        get() = gatewayProvider()

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
            return resolveShortLink(parsedLink)
        }
        return when (parsedLink.kind) {
            LinkKinds.VIDEO, LinkKinds.DYNAMIC -> resolveAwemePreview(parsedLink)
            LinkKinds.USER -> resolveLocalPreview(parsedLink)
            else -> LinkResolution.Failed(parsedLink, "不支持的抖音链接类型：${parsedLink.kind}")
        }
    }

    private suspend fun resolveShortLink(parsedLink: ParsedLink): LinkResolution {
        val expanded = runCatching { gateway.expandShortUrl(parsedLink.normalizedUrl) }.getOrNull()
        val resolved = expanded?.let { parseTiktokDirectLink(it, platformId) }
            ?: return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "无法解析该抖音短链，请改用完整作品或用户主页链接",
            )
        return resolveLink(resolved.copy(sourceUrl = parsedLink.sourceUrl))
    }

    private suspend fun resolveAwemePreview(parsedLink: ParsedLink): LinkResolution {
        val note = parsedLink.kind == LinkKinds.DYNAMIC
        val snapshot = runCatching { gateway.fetchAwemeSnapshot(parsedLink.targetId, note) }.getOrNull()
        if (snapshot != null) {
            return LinkResolution.Preview(
                parsedLink = parsedLink,
                preview = snapshot.toPreview(sourceUrl = parsedLink.normalizedUrl),
            )
        }
        return resolveLocalPreview(parsedLink)
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

    private fun TiktokAwemeSnapshot.toPreview(sourceUrl: String): LinkPreview {
        val userId = authorUserId?.takeIf { it.isNotBlank() }
        val displayName = authorName?.takeIf { it.isNotBlank() }
        val publisher = when {
            userId != null -> PublisherInfo(
                key = PublisherKey.of(platformId.value, PublisherKind.USER, userId),
                name = displayName ?: "抖音用户 $userId",
                avatar = MediaRef(authorAvatarUrl?.takeIf { it.isNotBlank() } ?: TIKTOK_DEFAULT_AVATAR, MediaKind.AVATAR),
            )
            displayName != null -> PublisherInfo(
                key = PublisherKey.of(platformId.value, PublisherKind.USER, displayName),
                name = displayName,
                avatar = MediaRef(authorAvatarUrl?.takeIf { it.isNotBlank() } ?: TIKTOK_DEFAULT_AVATAR, MediaKind.AVATAR),
            )
            else -> null
        }
        val title = description.takeIf { it.isNotBlank() }
            ?: if (isNote) "抖音图集 $awemeId" else "抖音视频 $awemeId"
        return LinkPreview(
            platformId = platformId,
            kind = if (isNote) LinkKinds.DYNAMIC else LinkKinds.VIDEO,
            id = awemeId,
            url = sourceUrl.ifBlank { awemeLink(awemeId, isNote) },
            title = title,
            description = description,
            badge = if (isNote) "图集" else "视频",
            cover = firstHttpUrl(coverUrl, authorAvatarUrl)?.let { MediaRef(it, MediaKind.COVER) },
            publisher = publisher,
            metrics = listOfNotNull(
                playCount.toDisplayMetric("play"),
                likeCount.toDisplayMetric("like"),
                commentCount.toDisplayMetric("comment"),
                collectCount.toDisplayMetric("favorite"),
                shareCount.toDisplayMetric("share"),
            ),
            durationSeconds = durationSeconds.takeUnless { isNote },
        )
    }

    private fun Long?.toDisplayMetric(key: String): DynamicMetric? {
        val value = this?.takeIf { it > 0 } ?: return null
        return DynamicMetric(
            key = key,
            raw = value,
            display = value.toDisplayCount(),
        )
    }

    private fun Long.toDisplayCount(): String {
        return when {
            this >= 100_000_000L -> "%.1f亿".format(this / 100_000_000.0).replace(".0", "")
            this >= 10_000L -> "%.1f万".format(this / 10_000.0).replace(".0", "")
            else -> toString()
        }
    }
}

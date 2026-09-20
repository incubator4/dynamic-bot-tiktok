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
    private val gatewayProvider: () -> TiktokGateway,
    private val requestFailureHandler: TiktokRequestFailureHandler,
) {
    private val gateway: TiktokGateway
        get() = gatewayProvider()

    fun matchesLink(inputUrl: String): Boolean = matchesTiktokLink(inputUrl)

    suspend fun parseLink(inputUrl: String): ParsedLink? {
        val normalized = normalizeTiktokInputUrl(inputUrl)
        if (normalized.isBlank()) return null
        parseTiktokDirectLink(normalized, platformId)?.let { return it }
        if (!isTiktokShortUrl(normalized)) return null

        val expanded = requestFailureHandler.run("短链展开 url=$normalized") {
            gateway.expandShortUrl(normalized)
        }.getOrNull() ?: return null

        return parseTiktokDirectLink(expanded, platformId)?.copy(sourceUrl = normalized)
    }

    suspend fun resolveLink(parsedLink: ParsedLink): LinkResolution {
        if (parsedLink.platformId != platformId) {
            return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = "不支持的平台：${parsedLink.platformId.value}",
            )
        }
        return when (parsedLink.kind) {
            LinkKinds.VIDEO, LinkKinds.DYNAMIC -> resolveAwemePreview(parsedLink)
            LinkKinds.USER -> resolveUserPreview(parsedLink)
            else -> LinkResolution.Failed(parsedLink, "不支持的抖音链接类型：${parsedLink.kind}")
        }
    }

    private suspend fun resolveAwemePreview(parsedLink: ParsedLink): LinkResolution {
        val note = parsedLink.kind == LinkKinds.DYNAMIC
        val snapshot = requestFailureHandler.run("作品详情解析 id=${parsedLink.targetId}") {
            gateway.fetchAwemeSnapshot(parsedLink.targetId, note)
        }.getOrElse { error ->
            return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = error.message ?: "获取抖音作品详情失败",
                cause = error,
            )
        } ?: return LinkResolution.Failed(parsedLink, "未找到抖音作品：${parsedLink.targetId}")

        return LinkResolution.Preview(
            parsedLink = parsedLink,
            preview = snapshot.toPreview(),
        )
    }

    private suspend fun resolveUserPreview(parsedLink: ParsedLink): LinkResolution {
        val snapshot = requestFailureHandler.run("用户主页解析 uid=${parsedLink.targetId}") {
            gateway.fetchLiveSnapshot(parsedLink.targetId)
        }.getOrElse { error ->
            return LinkResolution.Failed(
                parsedLink = parsedLink,
                reason = error.message ?: "获取抖音用户信息失败",
                cause = error,
            )
        }
        if (!snapshot.profileFound) {
            return LinkResolution.Failed(parsedLink, "未找到抖音用户：${parsedLink.targetId}")
        }

        val publisher = snapshot.toPublisherInfo()
            ?: return LinkResolution.Failed(parsedLink, "未找到抖音用户：${parsedLink.targetId}")
        val description = buildString {
            snapshot.uniqueId?.takeIf { it.isNotBlank() }?.let { append("抖音号 $it") }
            snapshot.signature?.takeIf { it.isNotBlank() }?.let { signature ->
                if (isNotEmpty()) append(" · ")
                append(signature)
            }
            if (isEmpty()) append("抖音用户 ${publisher.externalId}")
        }
        return LinkResolution.Preview(
            parsedLink = parsedLink,
            preview = LinkPreview(
                platformId = platformId,
                kind = LinkKinds.USER,
                id = publisher.externalId,
                url = userProfileLink(publisher.externalId),
                title = publisher.name,
                description = description,
                badge = "用户",
                cover = snapshot.coverUrl?.takeIf { it.isNotBlank() }?.let { MediaRef(it, MediaKind.COVER) }
                    ?: publisher.avatar.takeIf { it.uri.isNotBlank() },
                publisher = publisher,
            ),
        )
    }

    private fun TiktokAwemeSnapshot.toPreview(): LinkPreview {
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
            url = awemeLink(awemeId, isNote),
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

    private fun TiktokLiveSnapshot.toPublisherInfo(): PublisherInfo? {
        val normalizedUserId = userId.trim().takeIf { it.isNotBlank() } ?: return null
        return PublisherInfo(
            key = PublisherKey.of(platformId.value, PublisherKind.USER, normalizedUserId),
            name = nickname?.takeIf { it.isNotBlank() } ?: uniqueId?.takeIf { it.isNotBlank() } ?: normalizedUserId,
            avatar = MediaRef(avatarUrl?.takeIf { it.isNotBlank() } ?: TIKTOK_DEFAULT_AVATAR, MediaKind.AVATAR),
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

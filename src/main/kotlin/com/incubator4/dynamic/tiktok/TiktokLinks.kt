package com.incubator4.dynamic.tiktok

import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.link.LinkKinds
import top.colter.dynamic.core.link.ParsedLink
import java.net.URI

internal const val TIKTOK_SHORT_LINK_KIND: String = "short"

internal fun matchesTiktokLink(inputUrl: String): Boolean {
    val normalized = normalizeTiktokInputUrl(inputUrl)
    if (normalized.isBlank()) return false
    return parseTiktokDirectLink(normalized) != null || isTiktokShortUrl(normalized)
}

internal fun parseTiktokLink(
    inputUrl: String,
    platformId: PlatformId = PlatformId.of(TIKTOK_PLATFORM_ID),
): ParsedLink? {
    val normalized = normalizeTiktokInputUrl(inputUrl)
    if (normalized.isBlank()) return null
    parseTiktokDirectLink(normalized, platformId)?.let { return it }
    return parseTiktokShortLink(normalized, platformId)
}

internal fun parseTiktokDirectLink(
    inputUrl: String,
    platformId: PlatformId = PlatformId.of(TIKTOK_PLATFORM_ID),
): ParsedLink? {
    val normalized = normalizeTiktokInputUrl(inputUrl)
    if (normalized.isBlank() || isTiktokShortUrl(normalized)) return null
    val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase() ?: return null
    if (!host.isDouyinWebHost()) return null

    val pathSegments = uri.path
        ?.split("/")
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val queryId = uri.queryAwemeId()

    parseSharePath(pathSegments, normalized, platformId)?.let { return it }
    parseDesktopPath(pathSegments, queryId, normalized, platformId)?.let { return it }
    queryId?.let { return videoParsedLink(it, normalized, platformId) }
    return null
}

internal fun isTiktokShortUrl(inputUrl: String): Boolean = tiktokShortCode(inputUrl) != null

internal fun awemeLink(awemeId: String, note: Boolean): String {
    val kind = if (note) "note" else "video"
    return "$TIKTOK_HOME/$kind/${awemeId.trim()}"
}

private fun parseTiktokShortLink(
    inputUrl: String,
    platformId: PlatformId,
): ParsedLink? {
    val normalized = normalizeTiktokInputUrl(inputUrl)
    val code = tiktokShortCode(normalized) ?: return null
    return ParsedLink(
        platformId = platformId,
        kind = TIKTOK_SHORT_LINK_KIND,
        targetId = code,
        normalizedUrl = normalized,
        sourceUrl = normalized,
    )
}

private fun tiktokShortCode(inputUrl: String): String? {
    val uri = runCatching { URI(normalizeTiktokInputUrl(inputUrl)) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    val host = uri.host?.lowercase() ?: return null
    if (!host.isDouyinShortHost()) return null
    return uri.path
        ?.split("/")
        ?.firstOrNull { it.isNotBlank() }
        ?.takeIf { it.isDouyinShortCode() }
}

internal fun normalizeTiktokInputUrl(raw: String): String {
    val trimmed = raw.trim().trimUrlPunctuation()
    if (trimmed.isBlank()) return trimmed
    if (trimmed.contains("://")) return trimmed
    val host = trimmed.substringBefore("/").substringBefore("?").lowercase()
    return if (host.isDouyinWebHost() || host.isDouyinShortHost()) "https://$trimmed" else trimmed
}

internal fun String.trimUrlPunctuation(): String {
    return trim().trimEnd(
        '.',
        ',',
        ';',
        ':',
        '!',
        '?',
        ')',
        ']',
        '}',
        '>',
        '。',
        '，',
        '；',
        '：',
        '！',
        '？',
        '）',
        '】',
        '》',
    )
}

private fun parseDesktopPath(
    pathSegments: List<String>,
    queryId: String?,
    sourceUrl: String,
    platformId: PlatformId,
): ParsedLink? {
    return when (pathSegments.firstOrNull()?.lowercase()) {
        "video" -> pathSegments.getOrNull(1)
            ?.takeIf { it.isDouyinAwemeId() }
            ?.let { videoParsedLink(it, sourceUrl, platformId) }
        "note" -> pathSegments.getOrNull(1)
            ?.takeIf { it.isDouyinAwemeId() }
            ?.let { noteParsedLink(it, sourceUrl, platformId) }
        "user" -> {
            val userId = pathSegments.getOrNull(1)?.takeIf { it.isDouyinUserId() } ?: return null
            queryId?.let { return videoParsedLink(it, sourceUrl, platformId) }
            userParsedLink(userId, sourceUrl, platformId)
        }
        else -> null
    }
}

private fun parseSharePath(
    pathSegments: List<String>,
    sourceUrl: String,
    platformId: PlatformId,
): ParsedLink? {
    if (pathSegments.firstOrNull()?.lowercase() != "share") return null
    return when (pathSegments.getOrNull(1)?.lowercase()) {
        "video" -> pathSegments.getOrNull(2)
            ?.takeIf { it.isDouyinAwemeId() }
            ?.let { videoParsedLink(it, sourceUrl, platformId) }
        "note" -> pathSegments.getOrNull(2)
            ?.takeIf { it.isDouyinAwemeId() }
            ?.let { noteParsedLink(it, sourceUrl, platformId) }
        "user" -> pathSegments.getOrNull(2)
            ?.takeIf { it.isDouyinUserId() }
            ?.let { userParsedLink(it, sourceUrl, platformId) }
        else -> null
    }
}

private fun videoParsedLink(
    awemeId: String,
    sourceUrl: String,
    platformId: PlatformId,
): ParsedLink {
    return ParsedLink(
        platformId = platformId,
        kind = LinkKinds.VIDEO,
        targetId = awemeId,
        normalizedUrl = awemeLink(awemeId, note = false),
        sourceUrl = sourceUrl,
    )
}

private fun noteParsedLink(
    awemeId: String,
    sourceUrl: String,
    platformId: PlatformId,
): ParsedLink {
    return ParsedLink(
        platformId = platformId,
        kind = LinkKinds.DYNAMIC,
        targetId = awemeId,
        normalizedUrl = awemeLink(awemeId, note = true),
        sourceUrl = sourceUrl,
    )
}

private fun userParsedLink(
    userId: String,
    sourceUrl: String,
    platformId: PlatformId,
): ParsedLink {
    return ParsedLink(
        platformId = platformId,
        kind = LinkKinds.USER,
        targetId = userId,
        normalizedUrl = userProfileLink(userId),
        sourceUrl = sourceUrl,
    )
}

private fun URI.queryAwemeId(): String? {
    val query = rawQuery ?: query ?: return null
    query.split('&').forEach { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) return@forEach
        val name = pair.substring(0, index)
        if (name.equals("modal_id", ignoreCase = true) ||
            name.equals("item_id", ignoreCase = true) ||
            name.equals("aweme_id", ignoreCase = true)
        ) {
            val value = pair.substring(index + 1).trim().takeIf { it.isDouyinAwemeId() }
            if (value != null) return value
        }
    }
    return null
}

private fun String.isDouyinWebHost(): Boolean {
    val host = lowercase().removePrefix("www.")
    if (host.isDouyinShortHost()) return false
    return host == "douyin.com" ||
        host.endsWith(".douyin.com") ||
        host == "iesdouyin.com" ||
        host.endsWith(".iesdouyin.com")
}

private fun String.isDouyinShortHost(): Boolean {
    val host = lowercase().removePrefix("www.")
    return host == "v.douyin.com" || host.endsWith(".v.douyin.com")
}

private fun String.isDouyinAwemeId(): Boolean {
    return length in 6..32 && all(Char::isDigit)
}

private fun String.isDouyinUserId(): Boolean {
    if (isBlank()) return false
    val reserved = setOf("self", "live", "discover", "follow", "search", "hot", "recommend")
    return lowercase() !in reserved && none { it == '/' || it == '?' || it == '#' }
}

private fun String.isDouyinShortCode(): Boolean {
    return length in 4..24 && all { it.isLetterOrDigit() }
}

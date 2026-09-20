package com.incubator4.dynamic.tiktok

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal data class TiktokAwemeSnapshot(
    val awemeId: String,
    val description: String = "",
    val createdAtEpochSeconds: Long? = null,
    val authorUserId: String? = null,
    val authorName: String? = null,
    val authorAvatarUrl: String? = null,
    val coverUrl: String? = null,
    val durationSeconds: Long? = null,
    val isNote: Boolean = false,
    val likeCount: Long? = null,
    val commentCount: Long? = null,
    val shareCount: Long? = null,
    val collectCount: Long? = null,
    val playCount: Long? = null,
)

internal fun parseTiktokAwemeSnapshot(json: String, fallbackId: String): TiktokAwemeSnapshot? {
    val root = parseJsonObject(json, "抖音作品响应不是有效 JSON")
    val code = root.long("status_code", "error_code", "code")
        ?: root.obj("data")?.long("error_code", "status_code", "code")
    val message = firstNonBlank(
        root.obj("data")?.string("description", "msg", "message"),
        root.string("status_msg", "msg", "message"),
    ).orEmpty()
    if (looksLikeRiskControl(code, message)) {
        throw TiktokBlockedException(
            message.ifBlank { "抖音请求疑似被风控，已停止继续尝试。请稍后再试或更新 Cookie。" },
        )
    }
    if (code != null && code != 0L) {
        throw if (looksLikeLoginFailure(message)) {
            TiktokLoginException("抖音登录状态不可用：${message.ifBlank { "请重新登录" }}")
        } else {
            TiktokApiException(message.ifBlank { "抖音作品不可用" })
        }
    }

    val aweme = findTiktokAwemeObject(root, fallbackId) ?: return null
    val author = aweme.obj("author", "authorInfo")
    val video = aweme.obj("video")
    val images = resolveAwemeImages(aweme)
    val statistics = aweme.obj("statistics", "stats")
    val awemeId = firstNonBlank(
        aweme.string("aweme_id", "awemeId", "id_str"),
        aweme.long("aweme_id", "id")?.takeIf { it > 0L }?.toString(),
        fallbackId,
    )?.takeIf { it != "0" } ?: return null
    val isNote = !images.isNullOrEmpty() ||
        aweme.boolean("isSlides", "is_slides") == true ||
        aweme.long("aweme_type", "awemeType") in NOTE_AWEME_TYPES
    val coverUrl = firstHttpUrl(
        video?.mediaUrl(
            "originCoverUrlList",
            "coverUrlList",
            "origin_cover",
            "cover",
            "dynamic_cover",
            "cover_url",
            "originCover",
            "dynamicCover",
        ),
        firstImageUrl(images),
        aweme.mediaUrl("originCoverUrlList", "coverUrlList", "cover", "origin_cover", "cover_url"),
    )
    return TiktokAwemeSnapshot(
        awemeId = awemeId,
        description = firstNonBlank(
            aweme.string("desc", "description", "caption", "title"),
        ).orEmpty(),
        createdAtEpochSeconds = parseTiktokEpochSeconds(
            aweme.long("create_time", "createTime", "create_timestamp"),
        ),
        authorUserId = firstNonBlank(
            author?.string("sec_uid", "secUid"),
            author?.long("uid", "user_id", "authorUserId")?.takeIf { it > 0L }?.toString(),
            author?.string("uid_str", "user_id_str", "uid", "user_id"),
            aweme.string("sec_uid", "secUid"),
            aweme.long("authorUserId", "author_user_id")?.takeIf { it > 0L }?.toString(),
            aweme.string("authorUserId", "author_user_id"),
        ),
        authorName = firstNonBlank(
            author?.string("nickname", "nickName", "screen_name", "unique_id", "uniqueId"),
            aweme.string("authorName", "author_name"),
        ),
        authorAvatarUrl = firstHttpUrl(
            author?.mediaUrl(
                "avatar_larger",
                "avatar_medium",
                "avatar_thumb",
                "avatarThumb",
                "avatar",
                "avatarUri",
                "avatar_url",
            ),
        ),
        coverUrl = coverUrl,
        durationSeconds = parseAwemeDurationSeconds(
            video?.long("duration", "durationMs", "duration_ms")
                ?: aweme.long("duration", "durationMs"),
        ),
        isNote = isNote,
        likeCount = statistics?.long("digg_count", "diggCount", "like_count", "likeCount", "admire_count"),
        commentCount = statistics?.long("comment_count", "commentCount"),
        shareCount = statistics?.long("share_count", "shareCount"),
        collectCount = statistics?.long("collect_count", "collectCount", "favorite_count"),
        playCount = statistics?.long("play_count", "playCount", "aweme_play_count"),
    )
}

internal fun findTiktokAwemeObject(root: JsonObject, preferredId: String? = null): JsonObject? {
    val candidates = mutableListOf<JsonObject>()
    collectAwemeObjects(root, depth = 0, into = candidates)
    if (candidates.isEmpty()) return null
    return candidates.maxBy { scoreTiktokAweme(it, preferredId) }
}

internal fun TiktokAwemeSnapshot.hasPreviewIdentity(): Boolean {
    return !coverUrl.isNullOrBlank() &&
        (!authorUserId.isNullOrBlank() || !authorName.isNullOrBlank())
}

internal fun TiktokAwemeSnapshot.mergeMissingFrom(other: TiktokAwemeSnapshot?): TiktokAwemeSnapshot {
    other ?: return this
    return copy(
        description = description.ifBlank { other.description },
        createdAtEpochSeconds = createdAtEpochSeconds ?: other.createdAtEpochSeconds,
        authorUserId = firstNonBlank(authorUserId, other.authorUserId),
        authorName = firstNonBlank(authorName, other.authorName),
        authorAvatarUrl = firstHttpUrl(authorAvatarUrl, other.authorAvatarUrl),
        coverUrl = firstHttpUrl(coverUrl, other.coverUrl),
        durationSeconds = durationSeconds ?: other.durationSeconds,
        isNote = isNote || other.isNote,
        likeCount = likeCount ?: other.likeCount,
        commentCount = commentCount ?: other.commentCount,
        shareCount = shareCount ?: other.shareCount,
        collectCount = collectCount ?: other.collectCount,
        playCount = playCount ?: other.playCount,
    )
}

internal fun TiktokAwemeSnapshot.enrichWithHtmlMeta(
    meta: TiktokHtmlMeta,
    fallbackId: String,
): TiktokAwemeSnapshot {
    return copy(
        awemeId = awemeId.ifBlank { fallbackId },
        description = description.ifBlank { firstNonBlank(meta.title, meta.description).orEmpty() },
        authorName = firstNonBlank(authorName, meta.authorName),
        coverUrl = firstHttpUrl(coverUrl, meta.coverUrl),
    )
}

internal fun TiktokHtmlMeta.toAwemeSnapshot(fallbackId: String): TiktokAwemeSnapshot? {
    val id = fallbackId.trim().takeIf { it.isNotBlank() } ?: return null
    if (firstNonBlank(title, description, authorName, coverUrl) == null) return null
    return TiktokAwemeSnapshot(
        awemeId = id,
        description = firstNonBlank(title, description).orEmpty(),
        authorName = authorName,
        coverUrl = coverUrl,
    )
}

private fun collectAwemeObjects(root: JsonObject, depth: Int, into: MutableList<JsonObject>) {
    if (depth > 8) return
    if (looksLikeTiktokAweme(root)) {
        into += root
    }
    root.values.forEach { element ->
        when (element) {
            is JsonObject -> collectAwemeObjects(element, depth + 1, into)
            is JsonArray -> element.forEach { child ->
                val obj = child as? JsonObject ?: return@forEach
                collectAwemeObjects(obj, depth + 1, into)
            }
            else -> Unit
        }
    }
}

private fun looksLikeTiktokAweme(obj: JsonObject): Boolean {
    val id = firstNonBlank(
        obj.string("aweme_id", "awemeId", "id_str"),
        obj.long("aweme_id", "id")?.takeIf { it > 0L }?.toString(),
    )
    if (id.isNullOrBlank() || id == "0") return false
    return obj.string("desc", "description", "caption") != null ||
        obj.obj("video") != null ||
        obj.array("images", "image_list", "imageList") != null ||
        obj.obj("author", "authorInfo") != null
}

private fun scoreTiktokAweme(obj: JsonObject, preferredId: String?): Int {
    val id = firstNonBlank(
        obj.string("aweme_id", "awemeId", "id_str"),
        obj.long("aweme_id", "id")?.takeIf { it > 0L }?.toString(),
    )
    var score = 0
    if (!preferredId.isNullOrBlank() && id == preferredId) score += 8
    if (obj.obj("author", "authorInfo") != null) score += 4
    if (obj.obj("video") != null || obj.array("images", "image_list", "imageList") != null) score += 3
    if (extractAwemeCoverUrl(obj) != null) score += 2
    if (obj.string("desc", "description", "caption") != null) score += 1
    return score
}

private fun extractAwemeCoverUrl(aweme: JsonObject): String? {
    return firstHttpUrl(
        aweme.obj("video")?.mediaUrl(
            "originCoverUrlList",
            "coverUrlList",
            "origin_cover",
            "cover",
            "dynamic_cover",
            "cover_url",
            "originCover",
            "dynamicCover",
        ),
        firstImageUrl(resolveAwemeImages(aweme)),
        aweme.mediaUrl("originCoverUrlList", "coverUrlList", "cover", "origin_cover", "cover_url"),
    )
}

private fun resolveAwemeImages(aweme: JsonObject): JsonArray? {
    aweme.array("images", "image_list", "imageList")?.let { return it }
    val encoded = aweme.string("imageInfos", "image_infos") ?: return null
    return runCatching { TIKTOK_JSON.parseToJsonElement(encoded) as? JsonArray }.getOrNull()
}

private fun firstImageUrl(images: JsonArray?): String? {
    images ?: return null
    return images.firstNotNullOfOrNull { element ->
        val obj = element as? JsonObject ?: return@firstNotNullOfOrNull element.asTrimmedString()
        firstHttpUrl(
            obj.array("url_list", "urlList")?.firstNotNullOfOrNull { it.asTrimmedString() },
            obj.string("url", "uri"),
            obj.urlListFirst("origin_url", "display_image", "url_list"),
        )
    }
}

private fun parseAwemeDurationSeconds(raw: Long?): Long? {
    val value = raw ?: return null
    if (value <= 0L) return null
    return if (value >= 10_000L) value / 1_000L else value
}

private val NOTE_AWEME_TYPES: Set<Long> = setOf(2L, 68L, 150L)

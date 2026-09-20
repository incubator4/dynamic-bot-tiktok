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

    val aweme = findTiktokAwemeObject(root) ?: return null
    val author = aweme.obj("author", "authorInfo")
    val video = aweme.obj("video")
    val images = aweme.array("images", "image_list", "imageList")
    val statistics = aweme.obj("statistics", "stats")
    val awemeId = firstNonBlank(
        aweme.string("aweme_id", "awemeId", "id_str"),
        aweme.long("aweme_id", "id")?.takeIf { it > 0L }?.toString(),
        fallbackId,
    )?.takeIf { it != "0" } ?: return null
    val isNote = !images.isNullOrEmpty() ||
        aweme.long("aweme_type", "awemeType") in NOTE_AWEME_TYPES
    val coverUrl = firstHttpUrl(
        video?.urlListFirst("origin_cover", "cover", "dynamic_cover", "cover_url"),
        video?.string("cover", "cover_url"),
        firstImageUrl(images),
        aweme.urlListFirst("cover", "origin_cover"),
        aweme.string("cover", "cover_url"),
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
            author?.long("uid", "user_id")?.takeIf { it > 0L }?.toString(),
            author?.string("uid_str", "user_id_str", "uid", "user_id"),
        ),
        authorName = firstNonBlank(
            author?.string("nickname", "nickName", "screen_name", "unique_id"),
        ),
        authorAvatarUrl = firstHttpUrl(
            author?.urlListFirst("avatar_larger", "avatar_medium", "avatar_thumb", "avatar"),
            author?.string("avatar_url", "avatar"),
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

internal fun findTiktokAwemeObject(root: JsonObject): JsonObject? {
    listOfNotNull(
        root.obj("aweme")?.obj("detail", "awemeDetail", "aweme_detail") ?: root.obj("aweme"),
        root.obj("awemeDetail", "aweme_detail"),
        root.obj("videoDetail", "video_detail")?.obj("awemeInfo", "aweme_info", "itemInfo", "aweme"),
        root.obj("itemInfo", "item_info")?.obj("itemStruct", "item_struct", "aweme"),
        firstArrayAweme(
            root.array("item_list", "itemList", "aweme_list", "awemeList"),
        ),
        root.obj("data")?.let(::findTiktokAwemeObject),
        root.obj("app")?.let(::findTiktokAwemeObject),
    ).firstOrNull(::looksLikeTiktokAweme)?.let { return it }
    return findFirstAwemeObject(root, depth = 0)
}

private fun firstArrayAweme(array: JsonArray?): JsonObject? {
    array ?: return null
    return array.firstNotNullOfOrNull { element ->
        (element as? JsonObject)?.takeIf(::looksLikeTiktokAweme)
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
        obj.array("images", "image_list") != null ||
        obj.obj("author") != null
}

private fun findFirstAwemeObject(root: JsonObject, depth: Int): JsonObject? {
    if (depth > 8) return null
    if (looksLikeTiktokAweme(root)) return root
    root.values.forEach { element ->
        when (element) {
            is JsonObject -> findFirstAwemeObject(element, depth + 1)?.let { return it }
            is JsonArray -> element.forEach { child ->
                val obj = child as? JsonObject ?: return@forEach
                findFirstAwemeObject(obj, depth + 1)?.let { return it }
            }
            else -> Unit
        }
    }
    return null
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

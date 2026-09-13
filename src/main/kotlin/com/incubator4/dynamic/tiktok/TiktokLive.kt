package com.incubator4.dynamic.tiktok

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import top.colter.dynamic.core.data.LiveStatus
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal data class TiktokLiveSnapshot(
    val userId: String,
    val roomId: String = "",
    val webRid: String? = null,
    val status: LiveStatus = LiveStatus.CLOSE,
    val title: String = "",
    val coverUrl: String? = null,
    val area: String? = null,
    val startedAtEpochSeconds: Long? = null,
)

internal fun parseTiktokLiveSnapshot(json: String, fallbackUserId: String): TiktokLiveSnapshot {
    val root = parseJsonObject(json, "抖音直播状态响应不是有效 JSON")
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
            TiktokApiException(message.ifBlank { "抖音直播状态不可用" })
        }
    }

    val user = findTiktokUserObject(root)
        ?: return TiktokLiveSnapshot(userId = fallbackUserId.trim().ifBlank { fallbackUserId })
    val room = resolveRoomObject(user)
    val userId = firstNonBlank(
        user.string("sec_uid", "secUid"),
        user.long("uid", "user_id")?.takeIf { it > 0L }?.toString(),
        user.string("uid_str", "user_id_str", "uid", "user_id"),
        fallbackUserId,
    ) ?: fallbackUserId
    val living = isTiktokUserLiving(user, room)
    val webRid = firstNonBlank(
        user.string("web_rid", "webRid"),
        room?.string("web_rid", "webRid"),
        user.string("unique_id", "uniqueId")?.takeIf { living },
    )
    val roomId = firstNonBlank(
        webRid,
        room?.string("id_str", "room_id_str", "id"),
        room?.long("id", "room_id")?.takeIf { it > 0L }?.toString(),
        user.string("room_id_str", "roomIdStr"),
        user.long("room_id", "roomId")?.takeIf { it > 0L }?.toString(),
        userId.takeIf { living },
    ).orEmpty().let { value -> if (value == "0") "" else value }
    val title = firstNonBlank(
        room?.string("title", "live_title", "display_title"),
        user.string("live_title", "room_title"),
    ).orEmpty()
    val coverUrl = firstHttpUrl(
        room?.urlListFirst("cover", "origin_cover", "cover_url"),
        room?.string("cover", "cover_url"),
        user.urlListFirst("avatar_larger", "avatar_medium", "avatar_thumb", "avatar"),
        user.string("avatar_url", "avatar"),
    )
    val area = firstNonBlank(
        room?.string("area", "category", "partition", "partition_name"),
        room?.obj("partition_road_map")?.obj("partition", "sub_partition")
            ?.string("title", "partition_name", "name"),
    )
    val startedAt = parseTiktokEpochSeconds(
        room?.long("start_time", "started_at", "create_time", "live_start_time")
            ?: user.long("live_start_time"),
    )
    return TiktokLiveSnapshot(
        userId = userId,
        roomId = roomId,
        webRid = webRid,
        status = if (living) LiveStatus.OPEN else LiveStatus.CLOSE,
        title = title,
        coverUrl = coverUrl,
        area = area,
        startedAtEpochSeconds = startedAt.takeIf { living },
    )
}

internal fun extractTiktokEmbeddedPayload(body: String): String {
    val trimmed = body.trim()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
    extractScriptJson(trimmed, "RENDER_DATA")?.let { return it }
    extractScriptJson(trimmed, "__NEXT_DATA__")?.let { return it }
    extractAssignmentJson(trimmed, "_ROUTER_DATA")?.let { return it }
    extractAssignmentJson(trimmed, "RENDER_DATA")?.let { return it }
    throw TiktokApiException("抖音用户主页没有返回可用的资料数据")
}

internal fun userProfileLink(userId: String): String {
    return "$TIKTOK_HOME/user/${userId.trim()}"
}

internal fun liveRoomLink(roomId: String, userId: String = ""): String {
    val rid = roomId.trim()
    if (rid.isBlank() || rid.startsWith("MS4w", ignoreCase = true)) {
        val profileId = userId.trim().ifBlank { rid }
        return if (profileId.isBlank()) TIKTOK_LIVE_HOME else userProfileLink(profileId)
    }
    return "$TIKTOK_LIVE_HOME/$rid"
}

internal fun findTiktokUserObject(root: JsonObject): JsonObject? {
    listOfNotNull(
        root.obj("user_info", "userInfo"),
        root.obj("user")?.obj("user") ?: root.obj("user"),
        root.obj("data")?.obj("user_info", "userInfo"),
        root.obj("data")?.obj("user")?.obj("user") ?: root.obj("data")?.obj("user"),
        root.obj("app")?.obj("user")?.obj("user") ?: root.obj("app")?.obj("user"),
        root.obj("userDetail", "user_detail")?.obj("userInfo", "user_info", "user"),
    ).firstOrNull(::looksLikeTiktokUser)?.let { return it }
    return findFirstUserObject(root, depth = 0)
}

private fun resolveRoomObject(user: JsonObject): JsonObject? {
    user.obj("room", "live_room", "liveRoom")?.let { return it }
    val raw = user.string("room_data", "roomData") ?: return null
    return runCatching { TIKTOK_JSON.parseToJsonElement(raw).jsonObject }.getOrNull()
}

private fun isTiktokUserLiving(user: JsonObject, room: JsonObject?): Boolean {
    user.boolean("is_live", "isLive", "living", "has_live")?.let { return it }
    val liveStatus = user.long("live_status", "liveStatus")
    if (liveStatus != null) return liveStatus == 1L
    val roomStatus = room?.long("status", "live_status", "liveStatus")
    if (roomStatus != null) return roomStatus == 2L
    return false
}

private fun looksLikeTiktokUser(obj: JsonObject): Boolean {
    return obj.string("sec_uid", "secUid") != null ||
        obj.long("live_status", "liveStatus") != null ||
        (obj.string("nickname", "nickName") != null &&
            (obj.string("uid", "uid_str", "user_id", "user_id_str") != null ||
                obj.long("uid", "user_id") != null))
}

private fun findFirstUserObject(root: JsonObject, depth: Int): JsonObject? {
    if (depth > 6) return null
    if (looksLikeTiktokUser(root)) return root
    root.values.forEach { element ->
        val child = element as? JsonObject ?: return@forEach
        findFirstUserObject(child, depth + 1)?.let { return it }
    }
    return null
}

private fun extractScriptJson(html: String, scriptId: String): String? {
    val regex = Regex(
        """<script[^>]*\bid=["']$scriptId["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val raw = regex.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return decodeMaybeUrlEncodedJson(raw)
}

private fun extractAssignmentJson(html: String, name: String): String? {
    val regex = Regex(
        """(?:window\.)?$name\s*=\s*(\{.*})\s*;""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    val raw = regex.find(html)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return decodeMaybeUrlEncodedJson(raw)
}

private fun decodeMaybeUrlEncodedJson(raw: String): String? {
    val candidates = listOf(
        raw,
        runCatching { URLDecoder.decode(raw, StandardCharsets.UTF_8) }.getOrNull(),
    )
    return candidates.firstOrNull { candidate ->
        val value = candidate?.trim().orEmpty()
        value.startsWith("{") || value.startsWith("[")
    }?.trim()
}

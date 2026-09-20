package com.incubator4.dynamic.tiktok

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

internal val TIKTOK_JSON: Json = Json {
    ignoreUnknownKeys = true
}

internal fun JsonObject.string(vararg keys: String): String? {
    keys.forEach { key ->
        val value = this[key].asTrimmedString()
        if (value != null) return value
    }
    return null
}

internal fun JsonObject.boolean(vararg keys: String): Boolean? {
    keys.forEach { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@forEach
        primitive.booleanOrNull?.let { return it }
        when (primitive.contentOrNull?.trim()?.lowercase()) {
            "true", "1" -> return true
            "false", "0" -> return false
        }
    }
    return null
}

internal fun JsonObject.long(vararg keys: String): Long? {
    keys.forEach { key ->
        val primitive = this[key] as? JsonPrimitive ?: return@forEach
        primitive.longOrNull?.let { return it }
        primitive.contentOrNull?.trim()?.toLongOrNull()?.let { return it }
    }
    return null
}

internal fun JsonObject.obj(vararg keys: String): JsonObject? {
    keys.forEach { key ->
        val value = this[key] as? JsonObject
        if (value != null) return value
    }
    return null
}

internal fun JsonObject.array(vararg keys: String): JsonArray? {
    keys.forEach { key ->
        val value = this[key] as? JsonArray
        if (value != null) return value
    }
    return null
}

internal fun JsonElement?.asTrimmedString(): String? {
    val primitive = this as? JsonPrimitive ?: return null
    return primitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

internal fun parseJsonObject(json: String, errorMessage: String): JsonObject {
    return runCatching { TIKTOK_JSON.parseToJsonElement(json).jsonObject }.getOrElse {
        throw TiktokApiException(errorMessage)
    }
}

internal fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }
}

internal fun firstHttpUrl(vararg urls: String?): String? {
    return urls.firstNotNullOfOrNull(::normalizeHttpUrl)
}

internal fun JsonObject.urlListFirst(vararg objectKeys: String): String? {
    objectKeys.forEach { key ->
        val obj = this.obj(key) ?: return@forEach
        val url = obj.array("url_list", "urlList")?.firstNotNullOfOrNull { it.asTrimmedString() }
            ?: obj.string("url", "uri")
        firstHttpUrl(url)?.let { return it }
    }
    return null
}

internal fun JsonObject.mediaUrl(vararg keys: String): String? {
    keys.forEach { key ->
        array(key)?.firstNotNullOfOrNull { firstHttpUrl(it.asTrimmedString()) }?.let { return it }
        urlListFirst(key)?.let { return it }
        firstHttpUrl(string(key))?.let { return it }
    }
    return null
}

internal fun parseTiktokEpochSeconds(raw: Long?): Long? {
    val value = raw ?: return null
    if (value <= 0L) return null
    return if (value > 10_000_000_000L) value / 1_000L else value
}

internal fun normalizeHttpUrl(url: String?): String? {
    val value = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return when {
        value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true) -> value
        value.startsWith("//") -> "https:$value"
        else -> null
    }
}

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

internal fun JsonObject.boolean(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    return primitive.booleanOrNull
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

internal fun firstHttpUrl(vararg urls: String?): String? {
    return urls.firstNotNullOfOrNull(::normalizeHttpUrl)
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

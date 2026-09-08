package com.github.gillesbergerp.reviewrelay.util.json

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.JsonStreamParser

/**
 * Gson behind accessors that answer null rather than throw.
 *
 * OpenCode payloads drift between versions, so every read here asks whether a field is what we
 * expect instead of asserting it, and a field that is absent, null or the wrong type reads alike.
 */

class JsonException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

operator fun JsonElement?.get(key: String): JsonElement? =
    (this as? JsonObject)?.get(key)?.takeUnless { it.isJsonNull }

val JsonElement?.string: String? get() = primitive?.takeIf { it.isString }?.asString
val JsonElement?.double: Double? get() = primitive?.takeIf { it.isNumber }?.asDouble
val JsonElement?.long: Long? get() = primitive?.takeIf { it.isNumber }?.asLong
val JsonElement?.int: Int? get() = primitive?.takeIf { it.isNumber }?.asInt
val JsonElement?.bool: Boolean? get() = primitive?.takeIf { it.isBoolean }?.asBoolean
val JsonElement?.items: List<JsonElement> get() = (this as? JsonArray)?.toList() ?: emptyList()
val JsonElement?.fields: Map<String, JsonElement>
    get() = (this as? JsonObject)?.entrySet()?.associate { it.key to it.value } ?: emptyMap()

private val JsonElement?.primitive: JsonPrimitive? get() = this as? JsonPrimitive

object Json {

    // HTML escaping is on by default and would turn every < > & = in a review comment into \uXXXX.
    private val GSON = GsonBuilder().disableHtmlEscaping().create()

    fun parse(text: String): JsonElement {
        // Gson reads an empty document as a null literal; from an HTTP body it is a failure.
        if (text.isBlank()) throw JsonException("Empty input")
        return try {
            JsonParser.parseString(text)
        } catch (e: RuntimeException) {
            throw JsonException("Not JSON: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    fun parseOrNull(text: String): JsonElement? = try {
        parse(text)
    } catch (_: JsonException) {
        null
    }

    /**
     * Every document in [text], for a stream that holds more than one.
     *
     * `gh api --paginate` writes one array per page, back to back, which [parse] rejects outright as
     * trailing content rather than reading the first of them.
     */
    fun parseAll(text: String): List<JsonElement> {
        if (text.isBlank()) throw JsonException("Empty input")
        return try {
            JsonStreamParser(text).asSequence().toList()
        } catch (e: RuntimeException) {
            throw JsonException("Not JSON: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    /** Null map values are dropped, which is how an unset agent stays out of the prompt body. */
    fun write(value: Any?): String = GSON.toJson(value)
}

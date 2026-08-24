package de.lautstark.vorlaut.boardpackage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Lenient accessors over a parsed JSON tree.
 *
 * The importer reads trees rather than deserialising into declared classes on
 * purpose. SPEC.md 10.3: an importer **MUST ignore any field it does not
 * recognise, and MUST NOT fail** — unknown plain OBF fields, unknown
 * `ext_lautstark_*` fields, and every other vendor's `ext_*` alike — and unknown
 * fields must not even produce a warning. A schema-validating deserialiser is the
 * implementation that fails fixture `unknown-ext`, which exists to catch exactly
 * that. Reading only what is understood makes ignoring the rest the default
 * rather than something to remember to configure.
 */
internal object Json {
    private val parser =
        kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

    /** Parses [bytes] as a JSON object, or null if it is not one. */
    fun objectFrom(bytes: ByteArray): JsonObject? =
        try {
            parser.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonObject
        } catch (_: Exception) {
            null
        }
}

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(key: String): Boolean? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toBooleanStrictOrNull()
}

internal fun JsonObject.int(key: String): Int? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toIntOrNull()
}

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonElement.asObject(): JsonObject? = this as? JsonObject

/** A string, or null for anything else — including JSON `null`, which grid cells use. */
internal fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `{ "id": "path" }` maps, skipping any entry whose value is not a string. */
internal fun JsonObject.stringMap(): Map<String, String> =
    entries
        .mapNotNull { (key, value) ->
            value.asStringOrNull()?.let { key to it }
        }.toMap()

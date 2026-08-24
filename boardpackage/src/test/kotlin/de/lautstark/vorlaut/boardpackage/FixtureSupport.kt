package de.lautstark.vorlaut.boardpackage

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Reads the conformance fixtures the Gradle build materialised.
 *
 * The fixtures are not in this repository. `provideExchangeFixtures` fetches them
 * at the pinned commit and hands the directory over in a system property; if that
 * property is missing the tests fail rather than quietly passing on nothing.
 */
object Fixtures {
    val directory: File by lazy {
        val configured =
            System.getProperty("exchange.fixtures")
                ?: error(
                    "exchange.fixtures is not set. Run the tests through Gradle, which fetches the " +
                        "fixtures at the pinned commit: ./gradlew :boardpackage:test",
                )
        File(configured).also {
            check(it.isDirectory) { "exchange fixtures directory does not exist: $it" }
        }
    }

    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun readJson(name: String): JsonObject = json.parseToJsonElement(directory.resolve(name).readText()) as JsonObject

    fun readBytes(name: String): ByteArray = directory.resolve(name).readBytes()

    /** The machine-readable list. Step 1 of the spec README's contract. */
    fun index(): List<FixtureEntry> =
        (readJson("index.json")["fixtures"] as JsonArray).map { element ->
            val entry = element as JsonObject
            FixtureEntry(
                name = entry.string("fixture")!!,
                file = entry.string("file")!!,
                expected = entry.string("expected")!!,
                outcome = entry.string("outcome")!!,
            )
        }
}

data class FixtureEntry(
    val name: String,
    val file: String,
    val expected: String,
    val outcome: String,
)

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.child(key: String): JsonObject? = this[key] as? JsonObject

/** True when the key is present, even if its value is JSON null. */
internal fun JsonObject.declares(key: String): Boolean = containsKey(key)

/** A present-but-null value, which `oversized-image` uses to assert "no picture". */
internal fun JsonObject.isNullAt(key: String): Boolean = this[key] is JsonNull

internal fun JsonElement.textOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Compares only the fields an expectation actually states.
 *
 * This is forced by the fixtures rather than chosen: the expectation files are
 * not uniform about which optional fields they carry. `minimal` states
 * `vocalization` for a button whose vocalization equals its label, while
 * `unknown-action` omits it for a button in the same position; `multipage` omits
 * `image` for buttons that do have one, and `nfd-normalization` omits
 * `symbol_source` though its manifest sets it. Requiring an exact key set would
 * fail conformant importers over the fixtures' own inconsistency, so a stated
 * field must match and an unstated one is not an assertion.
 *
 * The one thing this must not do is treat a stated `null` as unstated —
 * `oversized-image` writes `"image": null` to assert that the picture is gone,
 * and that is a real assertion.
 */
class Mismatches(
    private val label: String,
) {
    private val problems = ArrayList<String>()

    fun check(
        condition: Boolean,
        message: () -> String,
    ) {
        if (!condition) problems += message()
    }

    fun expectString(
        expected: JsonObject,
        key: String,
        actual: String?,
        where: String,
    ) {
        if (!expected.declares(key)) return
        if (expected.isNullAt(key)) {
            check(actual == null) { "$where: expected $key to be absent, was ${quote(actual)}" }
            return
        }
        val want = expected.string(key)
        check(want == actual) { "$where: expected $key ${quote(want)}, was ${quote(actual)}" }
    }

    fun expectBoolean(
        expected: JsonObject,
        key: String,
        actual: Boolean,
        where: String,
    ) {
        if (!expected.declares(key)) return
        val want = (expected[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: return
        check(want == actual) { "$where: expected $key $want, was $actual" }
    }

    fun expectInt(
        expected: JsonObject,
        key: String,
        actual: Int,
        where: String,
    ) {
        if (!expected.declares(key)) return
        val want = (expected[key] as? JsonPrimitive)?.content?.toIntOrNull() ?: return
        check(want == actual) { "$where: expected $key $want, was $actual" }
    }

    val isEmpty: Boolean get() = problems.isEmpty()

    fun report(): String = problems.joinToString("\n") { "  - $it" }

    fun failIfAny() {
        if (problems.isNotEmpty()) {
            throw AssertionError("$label did not match its expectation:\n${report()}")
        }
    }

    private fun quote(value: String?): String = if (value == null) "absent" else "\"$value\""
}

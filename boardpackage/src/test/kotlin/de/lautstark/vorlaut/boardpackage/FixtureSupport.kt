package de.lautstark.vorlaut.boardpackage

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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

/**
 * Walks a fixture's `scenario`: an ordered list of presses with the bar contents
 * after each, and — where the walk navigates — the board left showing.
 *
 * Shared by the two fixtures that carry one, because the walk is the same walk.
 * The button for each step is looked up **on the board currently showing**, which
 * is what makes the navigation part of the assertion rather than decoration: a
 * press that should have changed boards and did not fails here on a button that
 * is not where the walk is standing.
 *
 * The `:home` and `load_board` destinations are resolved here rather than taken
 * from the app's BoardViewModel, which cannot be reached from this module and
 * must not be — `:boardpackage` is a plain JVM library on purpose. The rule is
 * SPEC.md 7.4's and small enough to state twice; what would be worth sharing is
 * the *bar*, and that already is.
 */
internal fun walkScenario(
    fixtureFile: String,
    expectedFile: String,
) {
    val accepted = BoardPackageImporter.import(Fixtures.readBytes(fixtureFile)) as ImportResult.Accepted
    val boardPackage = accepted.boardPackage
    val buttons =
        boardPackage.boards
            .flatMap { board -> board.buttons.map { (board.id to it.id) to it } }
            .toMap()

    val bar = MessageBar()
    var showing = boardPackage.rootBoardId
    val scenario = Fixtures.readJson(expectedFile).array("scenario")!!

    scenario.filterIsInstance<JsonObject>().forEachIndexed { index, step ->
        val where = "step $index (${step.string("step")})"
        val buttonId = step.string("step")!!.removePrefix("activate ")
        val button =
            buttons[showing to buttonId]
                ?: throw AssertionError("$where: no button $buttonId on board $showing, which is the one showing")

        val spoken = bar.press(button)
        showing =
            when (val action = button.onActivate) {
                is OnActivate.Navigation -> destinationOf(action, boardPackage)
                is OnActivate.AppendThenNavigate -> destinationOf(action.then, boardPackage)
                is OnActivate.SpeakThenNavigate -> destinationOf(action.then, boardPackage)
                else -> showing
            }

        assertEquals(
            "$where: bar contents",
            step.array("bar").orEmpty().mapNotNull { it.textOrNull() },
            bar.contents().mapNotNull { it.spoken },
        )
        assertEquals("$where: what was spoken", step.string("spoken"), spoken)
        if (step.declares("board")) {
            assertEquals("$where: the board left showing", step.string("board"), showing)
        }
    }
}

private fun destinationOf(
    action: OnActivate.Navigation,
    boardPackage: BoardPackage,
): String =
    when (action) {
        is OnActivate.Navigate -> action.boardId
        OnActivate.Home -> boardPackage.rootBoardId
    }

/**
 * A fixture package with its manifest edited, for the cases no fixture carries.
 *
 * The fixtures state what a *conformant builder* writes, and the spec's README is
 * firm that they are the normative artefact. But some rules are about what an
 * importer does with a manifest no builder would produce — SPEC.md 7.5's clamp
 * and its "treat nonsense as off" are both of that kind, and asking the upstream
 * generator for a fixture that deliberately writes an invalid field would put a
 * bad example in the place everybody copies from.
 *
 * So those cases are built here, from a real package, and are deliberately *not*
 * conformance: nothing built this way is asserted against an `.expected.json`.
 *
 * The manifest is parsed and rebuilt rather than spliced as text, because
 * splicing in a key the package already carries leaves two of them in one object
 * and the answer then depends on which one the parser keeps — a property of
 * kotlinx.serialization, and not one a test about press timings should be pinning
 * by accident.
 */
internal fun withManifest(
    bytes: ByteArray,
    overrides: Map<String, JsonElement>,
): ByteArray {
    val out = ByteArrayOutputStream()
    ZipInputStream(ByteArrayInputStream(bytes)).use { source ->
        ZipOutputStream(out).use { sink ->
            while (true) {
                val entry = source.nextEntry ?: break
                val content = source.readBytes()
                sink.putNextEntry(ZipEntry(entry.name))
                sink.write(
                    if (entry.name != "manifest.json") {
                        content
                    } else {
                        val manifest = Json.parseToJsonElement(content.toString(Charsets.UTF_8)) as JsonObject
                        JsonObject(manifest + overrides).toString().toByteArray(Charsets.UTF_8)
                    },
                )
                sink.closeEntry()
            }
        }
    }
    return out.toByteArray()
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

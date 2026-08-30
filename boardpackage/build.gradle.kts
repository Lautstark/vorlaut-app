import java.net.URI

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// A plain JVM module on purpose. The importer is the part of this app that has
// to be exactly right, and the exchange fixtures are the only thing that says
// whether it is - so it must be runnable in a plain `test` task, with no
// emulator and no Robolectric between the assertion and the parser.
//
// The one rule that keeps that true: nothing under src/main may import
// android.*. `forbidAndroidImports` below fails the build if it does, because
// the first accidental `android.util.Log` would quietly make this module
// untestable on the JVM again.
kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// -----------------------------------------------------------------------------
// The conformance fixtures.
//
// They live in Lautstark/vorlaut-editor under exchange/fixtures and are
// fetched at a pinned commit, never copied into this repository. The spec's own
// README is blunt about why: a copy stops tracking the spec the moment either
// side changes, and a stale fixture passes forever. That is worse than having no
// fixtures, because it looks like coverage.
//
// Files are fetched one by one from raw.githubusercontent.com at the pinned SHA
// rather than as a tarball of the whole repository. A raw URL carrying a full
// commit SHA is immutable, which is the property a pin needs, and it avoids
// pulling a whole editor down to read twelve small files.
//
// The repository is the editor's because that is where the spec went on
// 2026-08-27, under ADR 0012: the editor left vorlaut-diy-talker and took
// exchange/ with it, so that the format and the writer of it — the editor's
// src/data/app_package.ts — sit in one repository rather than two. The old
// address kept working long after it stopped being the right one, because a raw
// URL at a full SHA is immutable and the fixtures are still in that tree; what
// it could never do again is carry a *newer* spec, since no further commit was
// ever going to land there. See docs/exchange-pin.md.
// -----------------------------------------------------------------------------

val exchangeRepository = "Lautstark/vorlaut-editor"
val exchangeSha = providers.gradleProperty("exchange.sha").orElse("").map { it.trim() }
val exchangeLocalPath = providers.gradleProperty("exchange.localPath").map { it.trim() }
val fixturesOutput = layout.buildDirectory.dir("exchange/fixtures")

val provideExchangeFixtures =
    tasks.register("provideExchangeFixtures") {
        description = "Materialises the exchange conformance fixtures at the pinned commit."
        group = "verification"

        val sha = exchangeSha
        val localPath = exchangeLocalPath
        val repository = exchangeRepository
        val output = fixturesOutput

        inputs.property("sha", sha)
        inputs.property("localPath", localPath.orElse(""))
        outputs.dir(output)

        doLast {
            val destination = output.get().asFile
            destination.deleteRecursively()
            destination.mkdirs()

            val local = localPath.orNull.orEmpty()
            if (local.isNotEmpty()) {
                // Local development against an unreleased spec. Never set in CI, and
                // deliberately not a pin - docs/exchange-pin.md says so too.
                val source = File(local).resolve("fixtures")
                if (!source.isDirectory) {
                    throw GradleException("exchange.localPath=$local has no fixtures/ directory")
                }
                source.copyRecursively(destination, overwrite = true)
                logger.lifecycle("Exchange fixtures taken from $source (local checkout, not a pin)")
                return@doLast
            }

            val pinned = sha.get()
            if (pinned.isEmpty()) {
                // Failing loudly is the point. A conformance suite that quietly does
                // nothing when its inputs are missing is the same silent divergence a
                // stale copy causes, reached from the other side.
                throw GradleException(
                    buildString {
                        appendLine("The exchange spec is not pinned, so the conformance fixtures cannot be fetched.")
                        appendLine()
                        appendLine("Set the commit SHA in gradle.properties:")
                        appendLine("    exchange.sha=<40-character commit SHA>")
                        appendLine()
                        appendLine("Or point at a local checkout while developing against an unreleased spec:")
                        appendLine("    ./gradlew :boardpackage:test -Pexchange.localPath=\$HOME/Code/vorlaut-editor/exchange")
                        appendLine()
                        append("See docs/exchange-pin.md.")
                    },
                )
            }

            val base = "https://raw.githubusercontent.com/$repository/$pinned/exchange/fixtures"

            fun fetch(name: String): ByteArray =
                try {
                    URI("$base/$name").toURL().openStream().use { it.readBytes() }
                } catch (e: Exception) {
                    throw GradleException("Could not fetch $name at $repository@$pinned: ${e.message}", e)
                }

            val index = fetch("index.json")
            destination.resolve("index.json").writeBytes(index)
            // Parsed with a regex rather than a JSON library so the build script needs
            // no extra dependency; the index is generated and its shape is stable.
            Regex("\"(?:file|expected)\"\\s*:\\s*\"([^\"]+)\"")
                .findAll(index.toString(Charsets.UTF_8))
                .map { it.groupValues[1] }
                .distinct()
                .forEach { destination.resolve(it).writeBytes(fetch(it)) }

            logger.lifecycle("Exchange fixtures fetched from $repository@$pinned")
        }
    }

tasks.test {
    dependsOn(provideExchangeFixtures)
    systemProperty("exchange.fixtures", fixturesOutput.get().asFile.absolutePath)
}

val forbidAndroidImports =
    tasks.register("forbidAndroidImports") {
        description = "Fails if the parser has picked up an Android dependency."
        group = "verification"
        val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
        val root = projectDir
        val marker = layout.buildDirectory.file("checks/no-android-imports.txt")
        inputs.files(sources)
        outputs.file(marker)
        doLast {
            val offenders =
                sources.files.filter { file ->
                    file.readLines().any { it.trimStart().startsWith("import android") }
                }
            if (offenders.isNotEmpty()) {
                throw GradleException(
                    "The board package parser must stay free of Android imports, so it can be " +
                        "unit-tested on the JVM. Offending files:\n" +
                        offenders.joinToString("\n") { "  " + it.relativeTo(root) },
                )
            }
            marker
                .get()
                .asFile
                .apply { parentFile.mkdirs() }
                .writeText("ok\n")
        }
    }

tasks.named("check") { dependsOn(forbidAndroidImports) }

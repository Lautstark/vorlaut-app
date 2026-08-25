plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// -----------------------------------------------------------------------------
// Release signing and versioning.
//
// Both are supplied from outside the tree. The keystore and its passwords come
// from the environment - .gitignore already refuses *.jks and keystore.properties,
// and the release workflow materialises the keystore from repository secrets into
// a temporary path. The version comes from the git tag being released, so the tag
// is the single place a version is decided and the file below cannot drift from it.
//
// Every value falls back to what a local `./gradlew build` needs, so a developer
// with none of this set still gets a working debug build and an unsigned release
// APK, exactly as before.
// -----------------------------------------------------------------------------

fun secret(
    environmentName: String,
    propertyName: String,
) = providers
    .environmentVariable(environmentName)
    .orElse(providers.gradleProperty(propertyName))
    .map { it.trim() }
    .filter { it.isNotEmpty() }

val releaseKeystore = secret("RELEASE_KEYSTORE", "release.keystore")
val releaseKeystorePassword = secret("RELEASE_KEYSTORE_PASSWORD", "release.keystorePassword")
val releaseKeyAlias = secret("RELEASE_KEY_ALIAS", "release.keyAlias")
val releaseKeyPassword = secret("RELEASE_KEY_PASSWORD", "release.keyPassword")

val signingCredentials =
    listOf(releaseKeystore, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
val signingIsConfigured = signingCredentials.all { it.isPresent }

// Half-configured signing is the case worth failing on. Three of four values set
// is a typo, not a decision, and the result would be an unsigned release APK that
// looks like a successful build until a device refuses to install it. All four or
// none; anything between stops the build here.
if (signingCredentials.any { it.isPresent } && !signingIsConfigured) {
    throw GradleException(
        buildString {
            appendLine("Release signing is only half configured.")
            appendLine()
            appendLine("Set all four, or none of them:")
            appendLine("    RELEASE_KEYSTORE          (path to the .jks)")
            appendLine("    RELEASE_KEYSTORE_PASSWORD")
            appendLine("    RELEASE_KEY_ALIAS")
            appendLine("    RELEASE_KEY_PASSWORD")
            appendLine()
            append("See docs/releasing.md.")
        },
    )
}

android {
    namespace = "de.lautstark.vorlaut.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.lautstark.vorlaut.app"
        minSdk = 26
        targetSdk = 37
        // The release workflow passes both, derived from the tag it is building.
        // The fallbacks are what an untagged local build gets.
        versionCode = providers.gradleProperty("release.versionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("release.versionName").getOrElse("0.1.0")
    }

    buildFeatures {
        compose = true
    }

    signingConfigs {
        if (signingIsConfigured) {
            create("release") {
                storeFile = file(releaseKeystore.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Null when no credentials are present, which leaves the APK unsigned
            // rather than falling back to the debug key. The release workflow does
            // not take the build's word for this: it runs `apksigner verify` on the
            // artifact before it uploads anything.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        sarifReport = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":boardpackage"))

    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
}

// The app's unit tests use the same conformance fixtures as the parser's, and
// get them the same way: fetched at the pinned commit, never copied in.
tasks.withType<Test>().configureEach {
    dependsOn(":boardpackage:provideExchangeFixtures")
    systemProperty(
        "exchange.fixtures",
        project(":boardpackage")
            .layout.buildDirectory
            .dir("exchange/fixtures")
            .get()
            .asFile.absolutePath,
    )
}

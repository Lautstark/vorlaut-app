plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "de.lautstark.vorlaut.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.lautstark.vorlaut.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

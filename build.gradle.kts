plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.spotless)
}

spotless {
    kotlin {
        target("**/*.kt")
        targetExclude("**/build/**")
        ktlint()
    }
    kotlinGradle {
        target("**/*.gradle.kts")
        ktlint()
    }
    format("misc") {
        target("**/*.md", "**/*.toml", "**/*.xml", "**/.gitignore")
        targetExclude("**/build/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

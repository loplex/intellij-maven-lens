import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "maven-lens-ij-plugin"

pluginManagement {
    plugins {
        // Pinned to the Kotlin line bundled with the target IntelliJ platform
        // (intellijIdea(...) in build.gradle.kts). The plugin ships no stdlib of
        // its own (kotlin.stdlib.default.dependency in gradle.properties), so
        // the platform's resumes its coroutines - and a newer compiler writes
        // an @DebugMetadata version that stdlib cannot read. The IDE runs with
        // coroutine debug probes installed, so every single resumption then
        // dies: measured with Kotlin 2.4.10 against IntelliJ 2025.2.6.2, the
        // plugin's service never completed a cycle and 12 of 13 tests timed
        // out, on "Debug metadata version mismatch. Expected: 1, got 2".
        // The Plugin Verifier is not what catches this - the same build
        // reported every target IDE compatible, with no internal-API usage.
        // Re-check plugins/Kotlin/kotlinc/build.txt in the target platform
        // whenever intellijIdea(...) is bumped, and move this pin in lockstep.
        id("org.jetbrains.kotlin.jvm") version "2.1.21"
        id("org.jetbrains.changelog") version "2.5.0"
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("org.jetbrains.intellij.platform.settings") version "2.18.1"
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    // Configure all projects' repositories
    repositories {
        mavenCentral()

        // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
        intellijPlatform {
            defaultRepositories()
            intellijDependencies()
        }
    }
}

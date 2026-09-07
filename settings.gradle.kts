import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "maven-lens-ij-plugin"

pluginManagement {
    plugins {
        // Pinned to the Kotlin line bundled with the target IntelliJ platform
        // (intellijIdea(...) in build.gradle.kts) - a newer Kotlin's coroutine
        // codegen trips both the platform's coroutine debug-probes and the
        // Plugin Verifier's INTERNAL_API_USAGES check. Re-check
        // plugins/Kotlin/kotlinc/build.txt in the target platform whenever
        // intellijIdea(...) is bumped, and move this pin in lockstep.
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

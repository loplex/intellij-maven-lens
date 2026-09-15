import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

rootProject.name = "maven-lens-ij-plugin"

pluginManagement {
    plugins {
        // PROBE: raised from the pinned 2.1.21 to ask verifyPlugin a single
        // question - does a Kotlin this much newer than the platform's own
        // bundled line make the plugin use platform-internal API? Revert
        // before merging; the pin belongs on the platform's Kotlin line.
        id("org.jetbrains.kotlin.jvm") version "2.4.10"
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

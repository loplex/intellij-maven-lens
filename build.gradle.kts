import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    pluginVerification {
        // Spelled out rather than left at the default so INTERNAL_API_USAGES cannot quietly drop
        // off the list again. It was dropped once, to let the plugin call the Maven embedder
        // through @ApiStatus.Internal classes, and the cost showed up only at publishing time:
        // JetBrains Marketplace runs the Plugin Verifier itself and rejects internal-API usage
        // outright, with no way for a plugin to opt out. A build that stays green here but fails
        // there is worse than no check at all, so this list has to keep failing on it.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
            FailureLevel.INTERNAL_API_USAGES,
            FailureLevel.OVERRIDE_ONLY_API_USAGES,
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        bundledPlugin("org.jetbrains.idea.maven")
        javaCompiler()
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Maven)
    }
}

changelog {
    // The reference-link footer of CHANGELOG.md is only rendered when the repository is known -
    // without this the versionPrefix below has nothing to apply to and no links are written.
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")

    // Releases are tagged with the bare version, the way publishing a release draft names the
    // tag, so those links have to be generated without the 'v' prefix this plugin would otherwise
    // apply - they would point at tags that do not exist
    versionPrefix = ""
}

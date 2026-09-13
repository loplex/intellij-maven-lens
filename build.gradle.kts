import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
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

        ides {
            // Left alone, this block defaults to recommended(), whose channels are RELEASE, EAP
            // and RC. With no until-build patched into plugin.xml the upper bound is open, so that
            // default pulls in whatever EAP is newest - a fresh ~1.5 GB download on every clean
            // run, against a build nobody can install the plugin into yet.
            //
            // No version is spelled out on either branch: both inherit sinceBuild from the
            // patched since-build, which is itself derived from the target platform.
            if (providers.environmentVariable("CI").isPresent) {
                // Every released build the plugin claims to support - EAP and RC dropped, since a
                // failure there says more about the EAP than about the plugin. This is the run
                // that decides whether a build can be published.
                select {
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
            } else {
                // On a developer machine, verify against the platform this build already resolved:
                // the task then downloads nothing at all. It checks less than CI does, which is
                // the point - it is the quick answer, not the authoritative one.
                current()
            }
        }
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

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask.FailureLevel
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(21)
}

// A machine that has local.properties (git-ignored) naming an IDE installation:
//
//     localIdePath = /opt/idea
//
// gets a runLocalIde task that launches the plugin in that installation, and one more IDE for the
// Plugin Verifier to check against. It does not become the platform: the plugin is still compiled
// and tested against the pinned release below, so what a developer machine builds is what CI
// builds. Launching is the part that wants the current IDE; compiling against it does not work
// anyway, since the test framework cannot compose a 2026.2 installation onto one classpath.
val localIdePath: String = providers
    .fileContents(layout.projectDirectory.file("local.properties")).asText
    .map { Properties().apply { load(it.reader()) }.getProperty("localIdePath").orEmpty().trim() }
    .orElse("")
    .get()

intellijPlatform {
    publishing {
        // The Marketplace channel a publishPlugin run by hand uploads to: the first identifier of the
        // pre-release suffix, or `default` for a final release, so that 0.3.0-beta.1 is offered only to
        // whoever subscribed to `beta` and 0.3.0 to everyone. Without this every version, pre-release or
        // not, lands on `default`. The release itself is uploaded by the Publish workflow over the
        // Marketplace API, with the same rule as `channel_of` in tools/check-release.py - which is also
        // what marks the GitHub release and asks the Marketplace afterwards; a change here is a change there.
        channels = providers.gradleProperty("version").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }

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
                // On a developer machine, verify against the platform this build already resolved,
                // plus the installation local.properties names when there is one: both are on disk
                // already, so the task downloads nothing. It checks less than CI does, which is the
                // point - it is the quick answer, not the authoritative one. The local IDE is the
                // interesting half, since it is usually newer than the pinned release and is what
                // runLocalIde launches the plugin in.
                current()
                if (localIdePath.isNotEmpty()) {
                    local(localIdePath)
                }
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        // One platform, everywhere, for everyone: what since-build is patched from, what the tests
        // run on, and what the published artifact is compiled against. A local installation stands
        // in for it nowhere - see localIdePath above for what it does instead.
        intellijIdea("2025.2.6.2")
        bundledPlugin("org.jetbrains.idea.maven")
        javaCompiler()
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Maven)
    }
}

// Launches the plugin in the installation local.properties names, rather than in a second copy of
// the pinned platform. The plugin installed there is the one this build produces - compiled against
// the pinned release - which is exactly how it reaches a user from Marketplace.
if (localIdePath.isNotEmpty()) {
    intellijPlatformTesting.runIde.register("runLocalIde") {
        localPath = file(localIdePath)
    }
}

changelog {
    // The reference-link footer of CHANGELOG.md is only rendered when the repository is known -
    // without this the versionPrefix below has nothing to apply to and no links are written.
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")

    // What a release tag carries in front of the version, declared once in gradle.properties and
    // read from there by the release tooling too. Without this the plugin applies a 'v' of its own
    // and the links point at tags that do not exist; with the fact written down twice, the links
    // and the tags could come to disagree and nothing would say so.
    versionPrefix = providers.gradleProperty("tagPrefix").getOrElse("")
}

tasks {
    publishPlugin {
        // -SNAPSHOT says the version has not been released, so it is precisely what must not be published. The
        // release workflows never run this task - Publish uploads the accepted archive over the Marketplace API
        // - so it is only ever reached by a publish run by hand, from a branch that may still carry the marker,
        // which is exactly when it is worth refusing.
        val declared = providers.gradleProperty("version").get()
        doFirst {
            require(!declared.endsWith("-SNAPSHOT")) {
                "$declared is a version being worked on, not one to publish"
            }
        }
    }
}

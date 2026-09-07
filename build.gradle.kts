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
        // Default failure levels are COMPATIBILITY_PROBLEMS, INTERNAL_API_USAGES and
        // OVERRIDE_ONLY_API_USAGES. INTERNAL_API_USAGES is dropped here: resolving the
        // transitive dependencies of a Maven plugin needs MavenEmbedderWrappersManager and
        // MavenEmbedderWrappers, and the bundled Maven plugin marks both @ApiStatus.Internal
        // without offering a public equivalent.
        //
        // The Plugin Verifier cannot mute a single internal-API usage: -ignored-problems only
        // filters binary compatibility problems, and -suppress-internal-api-usages applies to
        // JetBrains-authored plugins only. Suppressing the whole category is therefore the only
        // option, which is why the two remaining levels are spelled out rather than left at
        // their default - a newly introduced internal-API usage will not be reported anymore,
        // so re-check the Verifier report after every platform bump and restore this level as
        // soon as a public API covers the use case.
        failureLevel = listOf(
            FailureLevel.COMPATIBILITY_PROBLEMS,
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
    // Releases are tagged with the bare version, the way publishing a release draft names the
    // tag, so the reference-link footer of CHANGELOG.md has to be generated without the 'v'
    // prefix this plugin would otherwise apply - those links would point at tags that do not exist
    versionPrefix = ""
}

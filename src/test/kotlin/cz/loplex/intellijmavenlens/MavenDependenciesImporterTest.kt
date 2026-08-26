package cz.loplex.intellijmavenlens

import com.intellij.maven.testFramework.MavenImportingTestCase
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.testFramework.PlatformTestUtil
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * End-to-end test driving a real (embedded) Maven import through [MavenDependenciesImporter],
 * exactly as the IDE would after a `pom.xml` reload.
 *
 * The local Maven repository used by the import is redirected to a private, hermetic directory
 * pre-seeded with fake plugin/dependency JARs, so the test resolves entirely offline instead of
 * depending on real network access or the developer's `~/.m2`.
 *
 * The imported project uses `pom` packaging so its default lifecycle doesn't pull in the
 * `jar`-packaging bindings (compiler/resources/surefire/jar). What's left - `maven-clean-plugin`,
 * `maven-install-plugin`, `maven-deploy-plugin` and `maven-site-plugin` - is bound to the
 * `clean`/`site` lifecycles Maven applies regardless of packaging, so the IDE always tries to
 * resolve those too. Left unseeded, that means real network calls on every import:
 * `maven-site-plugin` alone pulls in a dependency tree ~278 POMs deep (Doxia, Velocity, ...),
 * which is what made this test take 45s-60s before this fake stand-in existed. [DEFAULT_LIFECYCLE_PLUGINS]
 * pins the exact groupId:artifactId:version the bundled Maven distribution currently binds for
 * those two lifecycles (see the "maven plugin resolution started: [...]" line in idea.log during
 * an import) and pre-seeds a minimal fake for each, so none of them ever need the network. If a
 * future IDE bump changes those default versions, an unseeded plugin just falls back to a real
 * (slow) network resolution rather than failing the test outright - a sudden slowdown here is the
 * signal to update this list.
 */
class MavenDependenciesImporterTest : MavenImportingTestCase() {

    override fun runInDispatchThread(): Boolean {
        return false
    }

    override fun setUp() {
        super.setUp()
        repositoryPath = dir.resolve("local-repository")
        Files.createDirectories(repositoryPath)
    }

    fun `test attaches plugin and its internal dependency as a project library`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        installFakeArtifact(GROUP_ID, "sample-plugin-dep", "1.0.0")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }

        importProject(
            """
            <groupId>$GROUP_ID</groupId>
            <artifactId>project</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
            <build>
                <plugins>
                    <plugin>
                        <groupId>$GROUP_ID</groupId>
                        <artifactId>sample-plugin</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>$GROUP_ID</groupId>
                                <artifactId>sample-plugin-dep</artifactId>
                                <version>1.0.0</version>
                            </dependency>
                        </dependencies>
                    </plugin>
                </plugins>
            </build>
            """.trimIndent()
        )

        val libraryName = "${MavenDependenciesImporter.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(libraryName)

        val defaultLifecyclePluginLibraryNames = DEFAULT_LIFECYCLE_PLUGINS.map { (groupId, artifactId, version) ->
            "${MavenDependenciesImporter.LIBRARY_PREFIX}$groupId:$artifactId:$version"
        }
        assertProjectLibraries(libraryName, *defaultLifecyclePluginLibraryNames.toTypedArray())
        assertLibraryClassRootsContain(
            libraryName,
            artifactPath(GROUP_ID, "sample-plugin", "1.0.0"),
            artifactPath(GROUP_ID, "sample-plugin-dep", "1.0.0"),
        )
        // assertModuleLibDeps() asserts order, but the order plugins appear in the effective
        // model (ours first vs. the lifecycle-injected defaults) isn't something to rely on.
        val actualModuleLibDeps = ModuleRootManager.getInstance(getModule("project")).orderEntries
            .filterIsInstance<LibraryOrderEntry>()
            .mapNotNull { it.libraryName }
        assertUnorderedElementsAreEqual(actualModuleLibDeps, listOf(libraryName) + defaultLifecyclePluginLibraryNames)
    }

    fun `test garbage-collects stale MavenLens libraries on reimport`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }
        val defaultLifecyclePluginLibraryNames = DEFAULT_LIFECYCLE_PLUGINS.map { (groupId, artifactId, version) ->
            "${MavenDependenciesImporter.LIBRARY_PREFIX}$groupId:$artifactId:$version"
        }

        importProject(
            """
            <groupId>$GROUP_ID</groupId>
            <artifactId>project</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
            <build>
                <plugins>
                    <plugin>
                        <groupId>$GROUP_ID</groupId>
                        <artifactId>sample-plugin</artifactId>
                        <version>1.0.0</version>
                    </plugin>
                </plugins>
            </build>
            """.trimIndent()
        )
        val oldLibraryName = "${MavenDependenciesImporter.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(oldLibraryName)
        assertProjectLibraries(oldLibraryName, *defaultLifecyclePluginLibraryNames.toTypedArray())

        installFakeArtifact(GROUP_ID, "sample-plugin", "2.0.0", packaging = "maven-plugin")
        updateProjectPom(
            """
            <groupId>$GROUP_ID</groupId>
            <artifactId>project</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
            <build>
                <plugins>
                    <plugin>
                        <groupId>$GROUP_ID</groupId>
                        <artifactId>sample-plugin</artifactId>
                        <version>2.0.0</version>
                    </plugin>
                </plugins>
            </build>
            """.trimIndent()
        )
        importProject()

        val newLibraryName = "${MavenDependenciesImporter.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:2.0.0"
        awaitLibrary(newLibraryName)

        assertProjectLibraries(newLibraryName, *defaultLifecyclePluginLibraryNames.toTypedArray())
    }

    fun `test preserves library and order entry identity across reimport with unchanged content`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }

        importProject(
            """
            <groupId>$GROUP_ID</groupId>
            <artifactId>project</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
            <build>
                <plugins>
                    <plugin>
                        <groupId>$GROUP_ID</groupId>
                        <artifactId>sample-plugin</artifactId>
                        <version>1.0.0</version>
                    </plugin>
                </plugins>
            </build>
            """.trimIndent()
        )
        val libraryName = "${MavenDependenciesImporter.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(libraryName)

        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        val libraryBeforeReimport = libraryTable.getLibraryByName(libraryName)
        assertNotNull(libraryBeforeReimport)

        val module = getModule("project")
        val orderEntryLibraryBeforeReimport = ModuleRootManager.getInstance(module).orderEntries
            .filterIsInstance<LibraryOrderEntry>()
            .first { it.libraryName == libraryName }
            .library
        assertSame(libraryBeforeReimport, orderEntryLibraryBeforeReimport)

        // Reimporting without touching the pom must be a no-op for MavenLens: the module's
        // LibraryOrderEntry references a library by identity, not by name, so recreating it would
        // strand that reference. waitForAllBackgroundActivityToCalmDown() lets the async
        // resolve-and-apply cycle triggered by this reimport finish before asserting nothing changed.
        importProject()
        PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()

        val libraryAfterReimport = libraryTable.getLibraryByName(libraryName)
        assertSame(
            "Library instance identity must survive a reimport with unchanged content",
            libraryBeforeReimport,
            libraryAfterReimport,
        )

        val orderEntryLibraryAfterReimport = ModuleRootManager.getInstance(module).orderEntries
            .filterIsInstance<LibraryOrderEntry>()
            .first { it.libraryName == libraryName }
            .library
        assertSame(
            "Module order entry must keep referencing the same Library instance",
            libraryBeforeReimport,
            orderEntryLibraryAfterReimport,
        )
    }

    private fun awaitLibrary(libraryName: String) {
        PlatformTestUtil.waitWithEventsDispatching(
            { "Maven Lens never attached library $libraryName" },
            { LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(libraryName) != null },
            10,
        )
    }

    private fun assertLibraryClassRootsContain(libraryName: String, vararg expectedJars: Path) {
        val library = LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(libraryName)
        assertNotNull("Library $libraryName not found", library)
        val actualPaths = library!!.getFiles(OrderRootType.CLASSES).map { it.path.substringBefore("!/") }
        for (expected in expectedJars) {
            assertContain(actualPaths, expected.toString())
        }
    }

    /** Writes a minimal valid `pom.xml` + non-empty `.jar` for `groupId:artifactId:version` into the fake local repository. */
    private fun installFakeArtifact(groupId: String, artifactId: String, version: String, packaging: String = "jar") {
        val artifactDir = repositoryPath
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
        Files.createDirectories(artifactDir)

        Files.writeString(
            artifactDir.resolve("$artifactId-$version.pom"),
            """
            <?xml version="1.0"?>
            <project xmlns="http://maven.apache.org/POM/$modelVersion">
                <modelVersion>$modelVersion</modelVersion>
                <groupId>$groupId</groupId>
                <artifactId>$artifactId</artifactId>
                <version>$version</version>
                <packaging>$packaging</packaging>
            </project>
            """.trimIndent()
        )

        JarOutputStream(Files.newOutputStream(artifactDir.resolve("$artifactId-$version.jar"))).use { jar ->
            jar.putNextEntry(JarEntry("marker.txt").apply { method = ZipEntry.STORED; size = 0; crc = 0 })
            jar.closeEntry()
        }
    }

    private fun artifactPath(groupId: String, artifactId: String, version: String): Path =
        repositoryPath
            .resolve(groupId.replace('.', '/'))
            .resolve(artifactId)
            .resolve(version)
            .resolve("$artifactId-$version.jar")

    private companion object {
        const val GROUP_ID = "test.mavenlens"

        /**
         * The plugins the bundled Maven distribution binds to the `clean` and `site` lifecycles
         * for every project regardless of packaging - see the class doc comment. Coordinates and
         * versions taken from the "maven plugin resolution started: [...]" line in idea.log.
         */
        val DEFAULT_LIFECYCLE_PLUGINS = listOf(
            Triple("org.apache.maven.plugins", "maven-clean-plugin", "3.2.0"),
            Triple("org.apache.maven.plugins", "maven-install-plugin", "3.1.2"),
            Triple("org.apache.maven.plugins", "maven-deploy-plugin", "3.1.2"),
            Triple("org.apache.maven.plugins", "maven-site-plugin", "3.12.1"),
        )
    }
}

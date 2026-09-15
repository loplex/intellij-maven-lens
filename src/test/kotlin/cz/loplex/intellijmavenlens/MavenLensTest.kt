package cz.loplex.intellijmavenlens

import com.intellij.maven.testFramework.MavenImportingTestCase
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.TestActionEvent
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

/**
 * End-to-end test driving a real (embedded) Maven import through [MavenDependenciesImporter] and
 * [MavenLensService], exactly as the IDE would after a `pom.xml` reload.
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
class MavenLensTest : MavenImportingTestCase() {

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

        val libraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(libraryName)

        val defaultLifecyclePluginLibraryNames = DEFAULT_LIFECYCLE_PLUGINS.map { (groupId, artifactId, version) ->
            "${MavenLensService.LIBRARY_PREFIX}$groupId:$artifactId:$version"
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
            "${MavenLensService.LIBRARY_PREFIX}$groupId:$artifactId:$version"
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
        val oldLibraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
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

        val newLibraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:2.0.0"
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
        val libraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
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

    fun `test multi-module reactor shares a single library and order entry across modules`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }

        val modulePluginXml = """
            <parent>
                <groupId>$GROUP_ID</groupId>
                <artifactId>project</artifactId>
                <version>1.0.0</version>
            </parent>
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
        createModulePom("module-a", "<artifactId>module-a</artifactId>\n$modulePluginXml")
        createModulePom("module-b", "<artifactId>module-b</artifactId>\n$modulePluginXml")

        importProject(
            """
            <groupId>$GROUP_ID</groupId>
            <artifactId>project</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
            <modules>
                <module>module-a</module>
                <module>module-b</module>
            </modules>
            """.trimIndent()
        )

        val libraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(libraryName)

        val library = LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(libraryName)
        assertNotNull(library)

        for (moduleName in listOf("module-a", "module-b")) {
            val orderEntryLibrary = ModuleRootManager.getInstance(getModule(moduleName)).orderEntries
                .filterIsInstance<LibraryOrderEntry>()
                .first { it.libraryName == libraryName }
                .library
            assertSame(
                "Modules sharing the same plugin must reference the exact same Library instance",
                library,
                orderEntryLibrary,
            )
        }
    }

    fun `test a reimport after one module's pom changes keeps every module's library`() {
        installFakeArtifact(GROUP_ID, "plugin-a", "1.0.0", packaging = "maven-plugin")
        installFakeArtifact(GROUP_ID, "plugin-b", "1.0.0", packaging = "maven-plugin")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }

        // A plugin per module, so each module owns a library no other module keeps alive.
        fun modulePom(artifactId: String, pluginArtifactId: String, extra: String = "") = """
            <artifactId>$artifactId</artifactId>
            <parent>
                <groupId>$GROUP_ID</groupId>
                <artifactId>project</artifactId>
                <version>1.0.0</version>
            </parent>
            <packaging>pom</packaging>
            $extra
            <build>
                <plugins>
                    <plugin>
                        <groupId>$GROUP_ID</groupId>
                        <artifactId>$pluginArtifactId</artifactId>
                        <version>1.0.0</version>
                    </plugin>
                </plugins>
            </build>
        """.trimIndent()

        createModulePom("module-a", modulePom("module-a", "plugin-a"))
        createModulePom("module-b", modulePom("module-b", "plugin-b"))
        importProject(
            """
            <groupId>$GROUP_ID</groupId>
            <artifactId>project</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
            <modules>
                <module>module-a</module>
                <module>module-b</module>
            </modules>
            """.trimIndent()
        )

        val libraryA = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:plugin-a:1.0.0"
        val libraryB = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:plugin-b:1.0.0"
        awaitLibrary(libraryA)
        awaitLibrary(libraryB)

        // Only module-a's pom changes. applyToProject() collects every MavenLens: library the
        // current cycle did not resolve, so a sync that reported module-a alone would take
        // module-b's library with it.
        //
        // Measured against IU-252.28539.54, the reimport reports the whole reactor - three
        // projects, three modules - and MavenLens resolves all six libraries again, so nothing is
        // at risk and this passes without the collection ever being reached. That is the point of
        // keeping it: it does not provoke the failure, it pins the assumption the collection rests
        // on, and turns red if a future platform starts reporting only what it re-imported.
        createModulePom("module-a", modulePom("module-a", "plugin-a", "<description>touched</description>"))
        importProject()
        PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()

        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        assertNotNull(
            "A reimport that did not report module-b must not collect module-b's library",
            libraryTable.getLibraryByName(libraryB),
        )
        assertContain(lensOrderEntryNames("module-b"), libraryB)
        assertNotNull(libraryTable.getLibraryByName(libraryA))
        assertContain(lensOrderEntryNames("module-a"), libraryA)
    }


    fun `test plugin version inherited from parent pluginManagement is resolved`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }

        createModulePom(
            "parent",
            """
            <groupId>$GROUP_ID</groupId>
            <artifactId>parent</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
            <build>
                <pluginManagement>
                    <plugins>
                        <plugin>
                            <groupId>$GROUP_ID</groupId>
                            <artifactId>sample-plugin</artifactId>
                            <version>1.0.0</version>
                        </plugin>
                    </plugins>
                </pluginManagement>
            </build>
            """.trimIndent()
        )

        // The child declares the plugin with no <version> at all - the effective version must
        // come from the parent's <pluginManagement>, exactly like IntelliJ's Maven model resolves it.
        importProject(
            """
            <groupId>$GROUP_ID</groupId>
            <artifactId>project</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
            <parent>
                <groupId>$GROUP_ID</groupId>
                <artifactId>parent</artifactId>
                <version>1.0.0</version>
                <relativePath>parent/pom.xml</relativePath>
            </parent>
            <build>
                <plugins>
                    <plugin>
                        <groupId>$GROUP_ID</groupId>
                        <artifactId>sample-plugin</artifactId>
                    </plugin>
                </plugins>
            </build>
            """.trimIndent()
        )

        val libraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(libraryName)

        val defaultLifecyclePluginLibraryNames = DEFAULT_LIFECYCLE_PLUGINS.map { (groupId, artifactId, version) ->
            "${MavenLensService.LIBRARY_PREFIX}$groupId:$artifactId:$version"
        }
        assertProjectLibraries(libraryName, *defaultLifecyclePluginLibraryNames.toTypedArray())
    }

    fun `test plugin is silently skipped when its jar cannot be resolved in the local repository`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        // Simulates a missing/corrupt local-repo artifact: the plugin's POM is present (so Maven's
        // own model resolution succeeds) but its JAR is not, which is what locateArtifactJar() must
        // tolerate rather than throwing.
        installFakeArtifact(GROUP_ID, "unresolvable-plugin", "1.0.0", packaging = "maven-plugin", withJar = false)
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
                    <plugin>
                        <groupId>$GROUP_ID</groupId>
                        <artifactId>unresolvable-plugin</artifactId>
                        <version>1.0.0</version>
                    </plugin>
                </plugins>
            </build>
            """.trimIndent()
        )

        val resolvableLibraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(resolvableLibraryName)

        val unresolvableLibraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:unresolvable-plugin:1.0.0"
        assertNull(
            "A plugin whose JAR can't be found in the local repository must not get a library",
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(unresolvableLibraryName),
        )
        assertFalse(
            "The module must not gain an order entry for a plugin that was never resolved",
            ModuleRootManager.getInstance(getModule("project")).orderEntries
                .filterIsInstance<LibraryOrderEntry>()
                .any { it.libraryName == unresolvableLibraryName },
        )
    }

    fun `test switching Maven Lens off detaches its libraries, switching it back on reattaches them`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }

        importProject(SINGLE_PLUGIN_POM)
        val libraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(libraryName)

        setMavenLensEnabled(false)
        awaitNoLensLibraries()
        assertProjectLibraries()
        assertEmpty(
            "Switching Maven Lens off must take the module order entries with the libraries",
            lensOrderEntryNames("project"),
        )

        // Switching back on must not wait for the next reload - it resolves the currently imported
        // projects itself.
        setMavenLensEnabled(true)
        awaitLibrary(libraryName)
        assertContain(lensOrderEntryNames("project"), libraryName)
    }

    fun `test import attaches nothing while Maven Lens is switched off`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }
        project.service<MavenLensSettings>().enabled = false

        importProject(SINGLE_PLUGIN_POM)
        PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()

        // Without this the two assertions below would hold just as well for an import that never
        // ran at all, which is the same weakness the failed-resolution test above was built on.
        assertModules("project")
        assertNotEmpty(MavenProjectsManager.getInstance(project).projects)

        assertProjectLibraries()
        assertEmpty(lensOrderEntryNames("project"))
    }

    fun `test a resolution that fails outright leaves the attached libraries alone`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }

        importProject(SINGLE_PLUGIN_POM)
        val libraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(libraryName)

        // A library no resolution can ever produce. applyToProject() removes every MavenLens
        // library the current cycle did not resolve, so this surviving is what tells "the service
        // deliberately skipped applying" apart from "it applied an empty result" - the two cases
        // the assertion on libraryName below cannot distinguish on its own.
        val staleName = "${MavenLensService.LIBRARY_PREFIX}stale:stale:0"
        plantLensLibrary(staleName)

        // The failure is injected rather than provoked. Misconfiguring Maven does not reach this
        // branch: measured against IU-252.28539.54, pointing Maven at a directory that is not a
        // Maven distribution - and emptying the embedder pool afterwards - still resolves every
        // plugin, because resolution never needs the named distribution. A test that tried to
        // provoke the failure that way passed while the branch it names never ran.
        replaceResolver { _, _ -> throw IOException("injected resolution failure") }

        val warnings = captureWarnings {
            project.service<MavenLensService>().scheduleSync(MavenProjectsManager.getInstance(project).projects)
            PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()
        }

        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        assertNotNull(
            "A resolution that failed for every project must not be read as 'nothing is declared any more'",
            libraryTable.getLibraryByName(libraryName),
        )
        assertContain(lensOrderEntryNames("project"), libraryName)
        assertNotNull(
            "applyToProject() has to be skipped entirely, not called with an empty result",
            libraryTable.getLibraryByName(staleName),
        )
        assertTrue(
            "The service has to report that it kept the libraries; it logged $warnings",
            warnings.any { it.contains("keeping the current libraries") },
        )
    }

    fun `test a resolution that fails for some projects applies what did resolve`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }

        val modulePom = """
            <parent>
                <groupId>$GROUP_ID</groupId>
                <artifactId>project</artifactId>
                <version>1.0.0</version>
            </parent>
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
        createModulePom("module-a", "<artifactId>module-a</artifactId>\n$modulePom")
        createModulePom("module-b", "<artifactId>module-b</artifactId>\n$modulePom")

        importProject(
            """
            <groupId>$GROUP_ID</groupId>
            <artifactId>project</artifactId>
            <version>1.0.0</version>
            <packaging>pom</packaging>
            <modules>
                <module>module-a</module>
                <module>module-b</module>
            </modules>
            """.trimIndent()
        )

        val libraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(libraryName)

        val staleName = "${MavenLensService.LIBRARY_PREFIX}stale:stale:0"
        plantLensLibrary(staleName)

        // Only module-b resolves; everything else throws. The safety net above deliberately does
        // not cover this: it holds the current libraries only when *nothing* resolved, so a
        // partial failure is applied, and module-a loses what it had.
        val resolvedRoot = jarRootOf(artifactPath(GROUP_ID, "sample-plugin", "1.0.0"))
        replaceResolver { mavenProject, _ ->
            if (mavenProject.mavenId.artifactId == "module-b") {
                listOf(ResolvedLibrary(libraryName, listOf(resolvedRoot)))
            } else {
                throw IOException("injected resolution failure for ${mavenProject.mavenId.artifactId}")
            }
        }

        project.service<MavenLensService>().scheduleSync(MavenProjectsManager.getInstance(project).projects)
        PlatformTestUtil.waitForAllBackgroundActivityToCalmDown()

        val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
        assertNull(
            "A partial failure still has a real answer to apply, so the stale library has to go",
            libraryTable.getLibraryByName(staleName),
        )
        assertNotNull(libraryTable.getLibraryByName(libraryName))
        assertContain(lensOrderEntryNames("module-b"), libraryName)
        assertEmpty(lensOrderEntryNames("module-a"))
    }

    fun `test the toolbar action stays hidden until the project is mavenized, then drives the switch`() {
        installFakeArtifact(GROUP_ID, "sample-plugin", "1.0.0", packaging = "maven-plugin")
        for ((groupId, artifactId, version) in DEFAULT_LIFECYCLE_PLUGINS) {
            installFakeArtifact(groupId, artifactId, version, packaging = "maven-plugin")
        }

        val action = ToggleMavenLensAction()
        assertFalse(
            "A switch for the Maven tool window has no business showing up before there is a Maven project",
            presentationAfterUpdate(action).isEnabledAndVisible,
        )

        importProject(SINGLE_PLUGIN_POM)
        val libraryName = "${MavenLensService.LIBRARY_PREFIX}$GROUP_ID:sample-plugin:1.0.0"
        awaitLibrary(libraryName)

        assertTrue(presentationAfterUpdate(action).isEnabledAndVisible)
        assertTrue("Maven Lens is on by default, so the switch has to read as on", action.isSelected(actionEvent(action)))

        // Driving the action, rather than the settings service the other toggle tests use, is the
        // point here: it is the only check that the toolbar button is wired to the service at all.
        action.setSelected(actionEvent(action), false)
        assertFalse(project.service<MavenLensSettings>().enabled)
        awaitNoLensLibraries()
        assertFalse(action.isSelected(actionEvent(action)))

        action.setSelected(actionEvent(action), true)
        awaitLibrary(libraryName)
        assertTrue(action.isSelected(actionEvent(action)))
    }

    private fun actionEvent(action: ToggleMavenLensAction): AnActionEvent =
        TestActionEvent.createTestEvent(action, SimpleDataContext.getProjectContext(project))

    private fun presentationAfterUpdate(action: ToggleMavenLensAction): Presentation =
        actionEvent(action).also { action.update(it) }.presentation

    /**
     * [PlatformTestUtil.waitWithEventsDispatching] requires being called from the EDT (it pumps the
     * Swing event queue itself while waiting). This test runs off the EDT ([runInDispatchThread] is
     * `false`, needed for Maven's coroutine-based import), and the real EDT thread keeps draining its
     * own queue independently in the background - including the `invokeLater` that runs
     * [MavenDependenciesImporter]'s `onSuccess` - so a plain poll from this thread is enough.
     */
    private fun awaitLibrary(libraryName: String) =
        await("Maven Lens never attached library $libraryName") {
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(libraryName) != null
        }

    private fun awaitNoLensLibraries() =
        await("Maven Lens never detached its libraries") {
            LibraryTablesRegistrar.getInstance().getLibraryTable(project).libraries
                .none { it.name?.startsWith(MavenLensService.LIBRARY_PREFIX) == true }
        }

    private fun await(failureMessage: String, condition: () -> Boolean) {
        val deadlineMs = System.currentTimeMillis() + 30_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadlineMs) {
                fail(failureMessage)
            }
            Thread.sleep(50)
        }
    }

    /** Puts [resolver] in place of the real one for the remainder of the test. */
    private fun replaceResolver(resolver: MavenPluginResolver) =
        project.replaceService(MavenPluginResolver::class.java, resolver, testRootDisposable)

    /**
     * Creates a "MavenLens:" library that no resolution can produce, so that a later assertion can
     * tell whether `applyToProject()` ran at all: it garbage-collects every MavenLens library the
     * current cycle did not resolve, so this one survives only if it was never called.
     */
    private fun plantLensLibrary(name: String) =
        WriteAction.runAndWait<RuntimeException> {
            val model = LibraryTablesRegistrar.getInstance().getLibraryTable(project).modifiableModel
            model.createLibrary(name)
            model.commit()
        }

    /**
     * Collects everything logged at WARN while [block] runs. The processor is a global, not a
     * thread local, which is what makes this work at all: the service logs from its own coroutine
     * scope, not from the test thread.
     */
    private fun captureWarnings(block: () -> Unit): List<String> {
        val warnings = java.util.Collections.synchronizedList(mutableListOf<String>())
        LoggedErrorProcessor.executeWith<RuntimeException>(object : LoggedErrorProcessor() {
            override fun processWarn(category: String, message: String, t: Throwable?): Boolean {
                warnings += message
                return true
            }
        }) { block() }
        return warnings
    }

    /** The JAR-filesystem root a resolved artifact becomes a library class root through. */
    private fun jarRootOf(jarPath: Path): VirtualFile {
        val localFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(jarPath)!!
        return JarFileSystem.getInstance().getJarRootForLocalFile(localFile)!!
    }

    /** Flips the switch the toolbar action drives, and applies it the same way the action does. */
    private fun setMavenLensEnabled(enabled: Boolean) {
        project.service<MavenLensSettings>().enabled = enabled
        project.service<MavenLensService>().scheduleApplyEnabledState()
    }

    private fun lensOrderEntryNames(moduleName: String): List<String> =
        ModuleRootManager.getInstance(getModule(moduleName)).orderEntries
            .filterIsInstance<LibraryOrderEntry>()
            .mapNotNull { it.libraryName }
            .filter { it.startsWith(MavenLensService.LIBRARY_PREFIX) }

    private fun assertLibraryClassRootsContain(libraryName: String, vararg expectedJars: Path) {
        val library = LibraryTablesRegistrar.getInstance().getLibraryTable(project).getLibraryByName(libraryName)
        assertNotNull("Library $libraryName not found", library)
        val actualPaths = library!!.getFiles(OrderRootType.CLASSES).map { it.path.substringBefore("!/") }
        for (expected in expectedJars) {
            assertContain(actualPaths, expected.toString())
        }
    }

    /**
     * Writes a minimal valid `pom.xml` for `groupId:artifactId:version` into the fake local
     * repository, plus a non-empty `.jar` alongside it unless [withJar] is false - used to
     * simulate a missing/corrupt local-repo artifact (POM present, JAR not) without touching the
     * network.
     */
    private fun installFakeArtifact(
        groupId: String,
        artifactId: String,
        version: String,
        packaging: String = "jar",
        withJar: Boolean = true,
    ) {
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

        if (!withJar) {
            return
        }

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

        /** A project declaring exactly one resolvable plugin - enough for the toggle tests. */
        val SINGLE_PLUGIN_POM = """
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

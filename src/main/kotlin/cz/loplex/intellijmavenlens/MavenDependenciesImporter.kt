package cz.loplex.intellijmavenlens

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.ModifiableRootModel
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.idea.maven.model.MavenId
import org.jetbrains.idea.maven.model.MavenPlugin
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import java.nio.file.Files
import java.nio.file.Path

/**
 * Listens for Maven re-imports and exposes every Maven plugin (and the artifacts declared in its
 * internal `<dependencies>` block) as an IntelliJ project library attached to the owning module,
 * so plugin internals become browsable and completable in the editor.
 */
class MavenDependenciesImporter(private val project: Project) : MavenImportListener {

    override fun importFinished(importedProjects: Collection<MavenProject>, newModules: List<Module>) {
        if (importedProjects.isEmpty()) {
            return
        }

        ProgressManager.getInstance().run(ResolveLibrariesTask(project, importedProjects))
    }

    /**
     * Resolves plugin JARs from the local repository on a background thread, then hands the
     * result to [applyToProject] on the EDT to mutate the project/module model.
     */
    private class ResolveLibrariesTask(
        project: Project,
        private val importedProjects: Collection<MavenProject>,
    ) : Task.Backgroundable(project, "Maven Lens: Resolving plugin dependencies", true) {

        private var moduleLibraries: Map<Module, List<ResolvedLibrary>> = emptyMap()

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = false
            val manager = MavenProjectsManager.getInstance(project)
            val localRepository = manager.repositoryPath
            val result = LinkedHashMap<Module, List<ResolvedLibrary>>()

            for ((index, mavenProject) in importedProjects.withIndex()) {
                indicator.checkCanceled()
                indicator.fraction = index.toDouble() / importedProjects.size

                val module = ReadAction.compute<Module?, RuntimeException> {
                    manager.findModule(mavenProject)
                } ?: continue
                val libraries = resolvePluginLibraries(mavenProject, localRepository)
                if (libraries.isNotEmpty()) {
                    result[module] = libraries
                }
            }

            moduleLibraries = result
        }

        override fun onSuccess() {
            if (moduleLibraries.isEmpty()) {
                LOG.debug("No resolvable Maven plugin dependencies found, nothing to attach.")
                return
            }
            applyToProject(project, moduleLibraries)
        }

        override fun onThrowable(error: Throwable) {
            LOG.warn("Failed to resolve Maven plugin dependencies for Maven Lens", error)
        }

        private fun resolvePluginLibraries(mavenProject: MavenProject, localRepository: Path): List<ResolvedLibrary> {
            val libraries = mutableListOf<ResolvedLibrary>()
            val plugins = ReadAction.compute<List<MavenPlugin>, RuntimeException> { mavenProject.plugins.toList() }

            for (plugin in plugins) {
                val classRoots = LinkedHashSet<VirtualFile>()

                locateArtifactJar(localRepository, plugin.mavenId)?.let(classRoots::add)
                for (dependencyId in plugin.dependencies) {
                    locateArtifactJar(localRepository, dependencyId)?.let(classRoots::add)
                }

                if (classRoots.isEmpty()) {
                    LOG.debug("No JARs resolved for plugin ${plugin.mavenId.displayString}, skipping.")
                    continue
                }

                libraries += ResolvedLibrary(libraryName(plugin), classRoots.toList())
            }

            return libraries
        }

        private fun locateArtifactJar(localRepository: Path, mavenId: MavenId): VirtualFile? {
            val groupId = mavenId.groupId ?: return null
            val artifactId = mavenId.artifactId ?: return null
            val version = mavenId.version ?: return null

            val jarPath = localRepository
                .resolve(groupId.replace('.', '/'))
                .resolve(artifactId)
                .resolve(version)
                .resolve("$artifactId-$version.jar")

            if (!Files.isRegularFile(jarPath)) {
                LOG.debug("Artifact JAR not found in local repository: $jarPath")
                return null
            }

            val localFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(jarPath) ?: return null
            return JarFileSystem.getInstance().getJarRootForLocalFile(localFile)
        }

        private fun libraryName(plugin: MavenPlugin): String =
            "$LIBRARY_PREFIX${plugin.groupId}:${plugin.artifactId}:${plugin.version}"
    }

    private data class ResolvedLibrary(val name: String, val classRoots: List<VirtualFile>)

    companion object {
        private val LOG = Logger.getInstance(MavenDependenciesImporter::class.java)

        /** Prefix used to identify (and later garbage-collect) libraries owned by Maven Lens. */
        const val LIBRARY_PREFIX = "MavenLens: "

        /**
         * Syncs every "MavenLens:" project library with the resolved plugin data and attaches them
         * to the classpath of the modules they belong to.
         *
         * A library whose name and class roots already match a resolved plugin is left untouched
         * rather than removed and recreated: module [com.intellij.openapi.roots.LibraryOrderEntry]
         * instances reference a library by its identity, so blindly recreating every library on each
         * import would strand every previously-added order entry as a broken reference the moment the
         * old instance is removed from the table. Only libraries that actually changed (or no longer
         * correspond to any resolved plugin) are removed/recreated; module order entries are diffed
         * the same way, by name, against what this import actually resolved.
         *
         * Everything is prepared first (library table changes, per-module
         * [com.intellij.openapi.roots.ModifiableRootModel]s with their library entries added) and
         * only committed once none of that preparation has thrown - so a failure partway through
         * never leaves some modules updated and others stale. Only the actual `commit()` calls,
         * which the platform documents as effectively non-failing, sit outside that guarantee.
         */
        private fun applyToProject(project: Project, moduleLibraries: Map<Module, List<ResolvedLibrary>>) {
            WriteAction.run<RuntimeException> {
                val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                val tableModel = libraryTable.modifiableModel
                val preparedRootModels = mutableListOf<ModifiableRootModel>()

                try {
                    val resolvedByName = LinkedHashMap<String, ResolvedLibrary>()
                    for (libraries in moduleLibraries.values) {
                        for (resolved in libraries) {
                            resolvedByName.putIfAbsent(resolved.name, resolved)
                        }
                    }

                    val existingLibraries = tableModel.libraries
                        .filter { it.name?.startsWith(LIBRARY_PREFIX) == true }
                        .associateBy { it.name!! }

                    val activeLibraries = HashMap<String, Library>()
                    for ((name, resolved) in resolvedByName) {
                        val existing = existingLibraries[name]
                        val expectedRoots = resolved.classRoots.map { it.url }.toSet()
                        val upToDate = existing != null && existing.getUrls(OrderRootType.CLASSES).toSet() == expectedRoots

                        activeLibraries[name] = if (upToDate) {
                            existing
                        } else {
                            if (existing != null) {
                                tableModel.removeLibrary(existing)
                            }
                            val library = tableModel.createLibrary(name)
                            val libraryModel = library.modifiableModel
                            for (classRoot in resolved.classRoots) {
                                libraryModel.addRoot(classRoot, OrderRootType.CLASSES)
                            }
                            libraryModel.commit()
                            library
                        }
                    }

                    // Anything still in the table that no longer corresponds to a resolved plugin is stale.
                    for (library in existingLibraries.values) {
                        if (library.name !in activeLibraries) {
                            tableModel.removeLibrary(library)
                        }
                    }

                    for ((module, libraries) in moduleLibraries) {
                        if (module.isDisposed) {
                            continue
                        }

                        val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                        preparedRootModels += rootModel

                        val wantedNames = libraries.mapTo(HashSet()) { it.name }

                        // Drop order entries for libraries this import no longer resolves for this
                        // module (dropped plugin, or a library that got recreated above).
                        for (entry in rootModel.orderEntries) {
                            val libraryName = (entry as? LibraryOrderEntry)?.libraryName ?: continue
                            if (libraryName.startsWith(LIBRARY_PREFIX) &&
                                (libraryName !in wantedNames || activeLibraries[libraryName] !== entry.library)
                            ) {
                                rootModel.removeOrderEntry(entry)
                            }
                        }

                        val alreadyPresent = rootModel.orderEntries
                            .filterIsInstance<LibraryOrderEntry>()
                            .mapNotNullTo(HashSet()) { it.libraryName }

                        for (resolved in libraries) {
                            if (resolved.name in alreadyPresent) {
                                continue
                            }
                            val library = activeLibraries[resolved.name] ?: continue
                            rootModel.addLibraryEntry(library)
                        }
                    }

                    // Nothing above touched committed project state - commit the shared library
                    // table first so every module below can only ever reference an already-known library.
                    tableModel.commit()
                    for (rootModel in preparedRootModels) {
                        rootModel.commit()
                    }

                    LOG.info(
                        "Maven Lens synced ${activeLibraries.size} plugin librar" +
                            (if (activeLibraries.size == 1) "y" else "ies") +
                            " across ${moduleLibraries.size} module(s)."
                    )
                } catch (e: Throwable) {
                    for (rootModel in preparedRootModels) {
                        if (!rootModel.isDisposed) {
                            rootModel.dispose()
                        }
                    }
                    Disposer.dispose(tableModel)
                    throw e
                }
            }
        }
    }
}

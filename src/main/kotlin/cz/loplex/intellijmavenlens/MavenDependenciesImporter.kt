package cz.loplex.intellijmavenlens

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
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
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.idea.maven.buildtool.MavenLogEventHandler
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenPlugin
import org.jetbrains.idea.maven.model.MavenRemoteRepository
import org.jetbrains.idea.maven.project.MavenEmbedderWrappersManager
import org.jetbrains.idea.maven.project.MavenEmbedderWrappers
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.PluginResolutionRequest
import org.jetbrains.idea.maven.utils.MavenUtil
import java.nio.file.Files
import java.nio.file.Path

/**
 * Listens for Maven re-imports and exposes every Maven plugin (and its dependencies, resolved
 * transitively the same way Maven itself resolves them for a real build) as an IntelliJ project
 * library attached to the owning module, so plugin internals become browsable and completable in
 * the editor.
 */
class MavenDependenciesImporter(private val project: Project) : MavenImportListener {

    override fun importFinished(importedProjects: Collection<MavenProject>, newModules: List<Module>) {
        if (importedProjects.isEmpty()) {
            return
        }

        // importFinished fires synchronously from inside Maven's own import coroutine, and
        // resolving now suspends on that same embedder/coroutine machinery (see
        // resolvePluginLibraries). Launching on the project's own scope - rather than blocking here
        // - lets this listener return immediately, and ties the resolve to the project's lifecycle
        // so it gets cancelled instead of running on against a project that's already closing.
        project.service<MavenLensCoroutineScopeHolder>().scope.launch(Dispatchers.Default) {
            resolveAndApply(importedProjects)
        }
    }

    private suspend fun resolveAndApply(importedProjects: Collection<MavenProject>) {
        val manager = MavenProjectsManager.getInstance(project)
        val moduleLibraries = LinkedHashMap<Module, List<ResolvedLibrary>>()

        withBackgroundProgress(project, "Maven Lens: Resolving plugin dependencies", true) {
            val embedderWrappers = project.service<MavenEmbedderWrappersManager>().createMavenEmbedderWrappers()
            embedderWrappers.use {
                for (mavenProject in importedProjects) {
                    val module = ReadAction.compute<Module?, RuntimeException> {
                        manager.findModule(mavenProject)
                    } ?: continue

                    val libraries = try {
                        resolvePluginLibraries(mavenProject, embedderWrappers)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("Failed to resolve Maven plugin dependencies for ${mavenProject.displayName}", e)
                        emptyList()
                    }
                    if (libraries.isNotEmpty()) {
                        moduleLibraries[module] = libraries
                    }
                }
            }
        }

        if (moduleLibraries.isEmpty()) {
            LOG.debug("No resolvable Maven plugin dependencies found, nothing to attach.")
            return
        }
        applyToProject(project, moduleLibraries)
    }

    /**
     * Resolves every plugin declared on [mavenProject] through the real Maven plugin-dependency
     * resolver (the out-of-process Maven embedder), the same mechanism Maven itself uses to build
     * a plugin's classpath for an actual build. This picks up the plugin's own transitive
     * dependencies as well as anything declared in its `<dependencies>` block, not just the
     * directly-declared artifacts.
     */
    private suspend fun resolvePluginLibraries(
        mavenProject: MavenProject,
        embedderWrappers: MavenEmbedderWrappers,
    ): List<ResolvedLibrary> {
        val (plugins, baseDir, remoteRepositories) = ReadAction.compute<PluginResolutionInput, RuntimeException> {
            PluginResolutionInput(
                mavenProject.plugins.toList(),
                MavenUtil.getBaseDir(mavenProject.directoryFile),
                mavenProject.remotePluginRepositories,
            )
        }
        if (plugins.isEmpty()) {
            return emptyList()
        }

        val embedder = embedderWrappers.getEmbedder(baseDir)
        val resolutionRequests = plugins.map { PluginResolutionRequest(it.mavenId, remoteRepositories, true, it.dependencies) }
        val responseByPluginId = embedder.resolvePlugins(resolutionRequests, null, MavenLogEventHandler, false)
            .associateBy { it.mavenPluginId }

        val libraries = mutableListOf<ResolvedLibrary>()
        for (plugin in plugins) {
            val response = responseByPluginId[plugin.mavenId]
            val artifacts = LinkedHashSet<MavenArtifact>()
            response?.pluginArtifact?.let(artifacts::add)
            response?.pluginDependencyArtifacts?.let(artifacts::addAll)

            if (artifacts.isEmpty()) {
                LOG.debug("No artifacts resolved for plugin ${plugin.mavenId.displayString}, skipping.")
                continue
            }

            val classRoots = artifacts.mapNotNull { locateJarRoot(it.file.toPath()) }
            if (classRoots.isEmpty()) {
                continue
            }

            libraries += ResolvedLibrary(libraryName(plugin), classRoots)
        }

        return libraries
    }

    private data class PluginResolutionInput(
        val plugins: List<MavenPlugin>,
        val baseDir: Path,
        val remoteRepositories: List<MavenRemoteRepository>,
    )

    private fun locateJarRoot(jarPath: Path): VirtualFile? {
        if (!Files.isRegularFile(jarPath)) {
            LOG.debug("Resolved artifact JAR not found on disk: $jarPath")
            return null
        }

        val localFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(jarPath) ?: return null
        return JarFileSystem.getInstance().getJarRootForLocalFile(localFile)
    }

    private fun libraryName(plugin: MavenPlugin): String =
        "$LIBRARY_PREFIX${plugin.groupId}:${plugin.artifactId}:${plugin.version}"

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
        private suspend fun applyToProject(project: Project, moduleLibraries: Map<Module, List<ResolvedLibrary>>) {
            writeAction {
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

/** Ties [MavenDependenciesImporter]'s background resolve to the project's lifecycle. */
@Service(Service.Level.PROJECT)
private class MavenLensCoroutineScopeHolder(val scope: CoroutineScope)

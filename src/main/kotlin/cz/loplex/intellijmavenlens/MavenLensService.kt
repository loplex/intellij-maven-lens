package cz.loplex.intellijmavenlens

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.idea.maven.buildtool.MavenLogEventHandler
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenPlugin
import org.jetbrains.idea.maven.model.MavenRemoteRepository
import org.jetbrains.idea.maven.project.MavenEmbeddersManager
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.project.MavenProjectsManager
import org.jetbrains.idea.maven.server.PluginResolutionRequest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Owns everything Maven Lens does to the project model: resolving every declared Maven plugin
 * (transitively, the same way Maven resolves it for a real build) and keeping the resulting
 * `MavenLens:` project libraries in sync with what was resolved.
 *
 * [MavenDependenciesImporter] drives it from Maven's import listener, [ToggleMavenLensAction] from
 * the Maven tool window toolbar; both go through [scheduleSync]/[scheduleApplyEnabledState] so the
 * work runs on this service's own scope, tied to the project's lifecycle.
 */
@Service(Service.Level.PROJECT)
class MavenLensService(private val project: Project, private val scope: CoroutineScope) {

    /**
     * Serializes the resolve/attach and detach cycles: a toggle flipped while an import-triggered
     * resolve is still running must not end up racing it, or the losing side's write action would
     * decide the final state.
     */
    private val mutex = Mutex()
    private var currentJob: Job? = null

    /**
     * Syncs the libraries for a finished Maven import, unless the user switched Maven Lens off.
     *
     * `importFinished` fires synchronously from inside Maven's own import coroutine, and resolving
     * now suspends on that same embedder/coroutine machinery (see [resolvePluginLibraries]).
     * Scheduling the work - rather than blocking the caller - lets the listener return immediately.
     */
    fun scheduleSync(importedProjects: Collection<MavenProject>) {
        if (!project.service<MavenLensSettings>().enabled) {
            LOG.debug("Maven Lens is disabled for this project, skipping the post-import sync.")
            return
        }
        schedule { resolveAndApply(importedProjects) }
    }

    /**
     * Brings the project in line with the current [MavenLensSettings.enabled] value: resolves and
     * attaches everything right away when switched on (so enabling doesn't sit idle until the next
     * reload), and drops every attached library when switched off.
     */
    fun scheduleApplyEnabledState() {
        if (project.service<MavenLensSettings>().enabled) {
            schedule { resolveAndApply(MavenProjectsManager.getInstance(project).projects) }
        } else {
            schedule { applyToProject(emptyMap()) }
        }
    }

    /**
     * Cancels whatever cycle is still in flight before starting the next one - an import that is
     * immediately followed by another import, or by a toggle, has nothing to gain from finishing
     * the superseded resolve. [mutex] then keeps the write actions themselves in submission order,
     * since cancellation cannot interrupt a write action that already started.
     */
    @Synchronized
    private fun schedule(block: suspend () -> Unit) {
        currentJob?.cancel()
        currentJob = scope.launch(Dispatchers.Default) {
            mutex.withLock { block() }
        }
    }

    private suspend fun resolveAndApply(importedProjects: Collection<MavenProject>) {
        if (importedProjects.isEmpty()) {
            return
        }

        val manager = MavenProjectsManager.getInstance(project)
        val moduleLibraries = LinkedHashMap<Module, List<ResolvedLibrary>>()
        var resolutionFailed = false

        withBackgroundProgress(project, "Maven Lens: Resolving plugin dependencies", true) {
            val embeddersManager = manager.embeddersManager
            for (mavenProject in importedProjects) {
                val module = ReadAction.compute<Module?, RuntimeException> {
                    manager.findModule(mavenProject)
                } ?: continue

                val libraries = try {
                    resolvePluginLibraries(mavenProject, embeddersManager)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Failed to resolve Maven plugin dependencies for ${mavenProject.displayName}", e)
                    resolutionFailed = true
                    emptyList()
                }
                if (libraries.isNotEmpty()) {
                    moduleLibraries[module] = libraries
                }
            }
        }

        // Nothing resolved *and* something blew up: treat it as "we don't know" rather than "there
        // is nothing", and leave the previously attached libraries alone. An empty result without
        // any failure is a real answer - no plugin is declared any more - and has to be applied, or
        // libraries for plugins that were just removed from the pom would linger forever.
        if (resolutionFailed && moduleLibraries.isEmpty()) {
            LOG.warn("Maven plugin dependency resolution failed for every imported project, keeping the current libraries.")
            return
        }
        applyToProject(moduleLibraries)
    }

    /**
     * Resolves every plugin declared on [mavenProject] through the real Maven plugin-dependency
     * resolver (the out-of-process Maven embedder), the same mechanism Maven itself uses to build
     * a plugin's classpath for an actual build. This picks up the plugin's own transitive
     * dependencies as well as anything declared in its `<dependencies>` block, not just the
     * directly-declared artifacts.
     *
     * The embedder comes from [MavenEmbeddersManager] rather than from the newer
     * `MavenEmbedderWrappers`: the latter is marked `@ApiStatus.Internal`, and JetBrains
     * Marketplace rejects plugins that use internal API. [MavenEmbeddersManager] is public - it
     * only carries `@ApiStatus.Obsolete`, which the Plugin Verifier does not report - and reaches
     * the very same [org.jetbrains.idea.maven.server.MavenEmbedderWrapper.resolvePlugins].
     *
     * It hands embedders out on loan from a pool keyed by base directory, so every one of them has
     * to be given back; without the `release` below the loan is never returned and the pool starts
     * a second embedder process for the next caller on the same directory.
     */
    private suspend fun resolvePluginLibraries(
        mavenProject: MavenProject,
        embeddersManager: MavenEmbeddersManager,
    ): List<ResolvedLibrary> {
        val (plugins, remoteRepositories) = ReadAction.compute<PluginResolutionInput, RuntimeException> {
            PluginResolutionInput(
                mavenProject.plugins.toList(),
                mavenProject.remotePluginRepositories,
            )
        }
        if (plugins.isEmpty()) {
            return emptyList()
        }

        val embedder = embeddersManager.getEmbedder(mavenProject, MavenEmbeddersManager.FOR_DEPENDENCIES_RESOLVE)
        val responseByPluginId = try {
            val resolutionRequests = plugins.map { PluginResolutionRequest(it.mavenId, remoteRepositories, true, it.dependencies) }
            embedder.resolvePlugins(resolutionRequests, null, MavenLogEventHandler, false)
                .associateBy { it.mavenPluginId }
        } finally {
            embeddersManager.release(embedder)
        }

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

    /**
     * Syncs every "MavenLens:" project library with the resolved plugin data and attaches them
     * to the classpath of the modules they belong to. An empty [moduleLibraries] is a valid input
     * meaning "nothing is resolved any more", which detaches everything Maven Lens ever added -
     * that is exactly what switching the plugin off does.
     *
     * A library whose name and class roots already match a resolved plugin is left untouched
     * rather than removed and recreated: module [LibraryOrderEntry] instances reference a library
     * by its identity, so blindly recreating every library on each import would strand every
     * previously-added order entry as a broken reference the moment the old instance is removed
     * from the table. Only libraries that actually changed (or no longer correspond to any
     * resolved plugin) are removed/recreated; module order entries are diffed the same way, by
     * name, against what this cycle actually resolved.
     *
     * Everything is prepared first (library table changes, per-module [ModifiableRootModel]s with
     * their library entries added) and only committed once none of that preparation has thrown -
     * so a failure partway through never leaves some modules updated and others stale. Only the
     * actual `commit()` calls, which the platform documents as effectively non-failing, sit
     * outside that guarantee.
     */
    private suspend fun applyToProject(moduleLibraries: Map<Module, List<ResolvedLibrary>>) {
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

                // Every module is visited, not just the ones something was resolved for: a module
                // that lost its last plugin - or the whole project, when Maven Lens is switched
                // off - still carries order entries that have to go with the libraries above.
                for (module in ModuleManager.getInstance(project).modules) {
                    if (module.isDisposed) {
                        continue
                    }

                    val libraries = moduleLibraries[module].orEmpty()
                    val wantedNames = libraries.mapTo(HashSet()) { it.name }
                    val rootManager = ModuleRootManager.getInstance(module)
                    if (!needsUpdate(rootManager, wantedNames, activeLibraries)) {
                        continue
                    }

                    val rootModel = rootManager.modifiableModel
                    preparedRootModels += rootModel

                    // Drop order entries for libraries this cycle no longer resolves for this
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

                    // Iterating the resolved list rather than wantedNames keeps the entries in
                    // the order the plugins were resolved in, instead of a hash set's order.
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
                        ", updating ${preparedRootModels.size} module(s)."
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

    /**
     * Whether [rootManager]'s module actually differs from what was resolved for it. Obtaining a
     * [ModifiableRootModel] is not free and every one of them has to be committed or disposed, so
     * the modules that have nothing to change - the common case, since most re-imports resolve
     * exactly what is already attached - are skipped before one is created.
     */
    private fun needsUpdate(
        rootManager: ModuleRootManager,
        wantedNames: Set<String>,
        activeLibraries: Map<String, Library>,
    ): Boolean {
        val ownEntries = rootManager.orderEntries
            .filterIsInstance<LibraryOrderEntry>()
            .filter { it.libraryName?.startsWith(LIBRARY_PREFIX) == true }

        val hasStaleEntry = ownEntries.any {
            it.libraryName !in wantedNames || activeLibraries[it.libraryName] !== it.library
        }
        val presentNames = ownEntries.mapNotNullTo(HashSet()) { it.libraryName }
        return hasStaleEntry || wantedNames.any { it !in presentNames }
    }

    companion object {
        private val LOG = Logger.getInstance(MavenLensService::class.java)

        /** Prefix used to identify (and later garbage-collect) libraries owned by Maven Lens. */
        const val LIBRARY_PREFIX = "MavenLens: "
    }
}

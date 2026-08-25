package cz.loplex.intellijmavenlens

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.roots.libraries.Library
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar
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

                val module = manager.findModule(mavenProject) ?: continue
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

            for (plugin in mavenProject.plugins) {
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
         * Recreates every "MavenLens:" project library from the resolved plugin data and attaches
         * them to the classpath of the modules they belong to.
         *
         * Runs entirely inside a single write action: existing "MavenLens:" libraries are removed
         * first (so stale versions from a previous import never linger), then the fresh set is
         * created and wired into each module's [com.intellij.openapi.roots.ModifiableRootModel].
         */
        private fun applyToProject(project: Project, moduleLibraries: Map<Module, List<ResolvedLibrary>>) {
            WriteAction.run<RuntimeException> {
                val libraryTable = LibraryTablesRegistrar.getInstance().getLibraryTable(project)
                val tableModel = libraryTable.modifiableModel

                for (library in tableModel.libraries) {
                    val name = library.name
                    if (name != null && name.startsWith(LIBRARY_PREFIX)) {
                        tableModel.removeLibrary(library)
                    }
                }

                val createdLibraries = HashMap<String, Library>()
                for (libraries in moduleLibraries.values) {
                    for (resolved in libraries) {
                        createdLibraries.getOrPut(resolved.name) {
                            val library = tableModel.createLibrary(resolved.name)
                            val libraryModel = library.modifiableModel
                            for (classRoot in resolved.classRoots) {
                                libraryModel.addRoot(classRoot, OrderRootType.CLASSES)
                            }
                            libraryModel.commit()
                            library
                        }
                    }
                }
                tableModel.commit()

                for ((module, libraries) in moduleLibraries) {
                    if (module.isDisposed) {
                        continue
                    }

                    val rootModel = ModuleRootManager.getInstance(module).modifiableModel
                    try {
                        for (resolved in libraries) {
                            val library = createdLibraries[resolved.name] ?: continue
                            rootModel.addLibraryEntry(library)
                        }
                        rootModel.commit()
                    } catch (e: Exception) {
                        rootModel.dispose()
                        throw e
                    }
                }

                LOG.info(
                    "Maven Lens attached ${createdLibraries.size} plugin librar" +
                        (if (createdLibraries.size == 1) "y" else "ies") +
                        " across ${moduleLibraries.size} module(s)."
                )
            }
        }
    }
}

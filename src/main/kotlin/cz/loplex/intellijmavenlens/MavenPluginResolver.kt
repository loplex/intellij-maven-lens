package cz.loplex.intellijmavenlens

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.idea.maven.buildtool.MavenLogEventHandler
import org.jetbrains.idea.maven.model.MavenArtifact
import org.jetbrains.idea.maven.model.MavenPlugin
import org.jetbrains.idea.maven.model.MavenRemoteRepository
import org.jetbrains.idea.maven.project.MavenEmbeddersManager
import org.jetbrains.idea.maven.project.MavenProject
import org.jetbrains.idea.maven.server.PluginResolutionRequest
import java.nio.file.Files
import java.nio.file.Path

/** A single Maven plugin's resolved classpath, ready to become a `MavenLens:` project library. */
internal data class ResolvedLibrary(val name: String, val classRoots: List<VirtualFile>)

/**
 * Resolves one Maven project's declared plugins into the libraries [MavenLensService] attaches.
 *
 * This is a service rather than a private method of [MavenLensService] so that a test can put a
 * failing implementation in its place. [MavenLensService] treats a throwing resolver as "we don't
 * know what is declared" and deliberately keeps the currently attached libraries; that branch
 * cannot be reached by misconfiguring Maven - measured against IU-252.28539.54, pointing Maven at
 * a directory that is not a Maven distribution (and emptying the embedder pool afterwards) still
 * resolves every plugin, because resolution never needs the named distribution. Injecting the
 * failure is therefore the only way that branch is ever exercised.
 */
internal fun interface MavenPluginResolver {

    suspend fun resolve(mavenProject: MavenProject, embeddersManager: MavenEmbeddersManager): List<ResolvedLibrary>
}

/** The real resolver: the out-of-process Maven embedder, the same one Maven uses for a build. */
internal class MavenEmbedderPluginResolver : MavenPluginResolver {

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
    override suspend fun resolve(
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
        "${MavenLensService.LIBRARY_PREFIX}${plugin.groupId}:${plugin.artifactId}:${plugin.version}"

    companion object {
        private val LOG = Logger.getInstance(MavenEmbedderPluginResolver::class.java)
    }
}

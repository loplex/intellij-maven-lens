package cz.loplex.intellijmavenlens

import com.intellij.openapi.components.service
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import org.jetbrains.idea.maven.project.MavenImportListener
import org.jetbrains.idea.maven.project.MavenProject

/**
 * Listens for Maven re-imports and hands them to [MavenLensService], which exposes every Maven
 * plugin (and its dependencies, resolved transitively the same way Maven itself resolves them for
 * a real build) as an IntelliJ project library attached to the owning module, so plugin internals
 * become browsable and completable in the editor.
 */
class MavenDependenciesImporter(private val project: Project) : MavenImportListener {

    override fun importFinished(importedProjects: Collection<MavenProject>, newModules: List<Module>) {
        if (importedProjects.isEmpty()) {
            return
        }
        project.service<MavenLensService>().scheduleSync(importedProjects)
    }
}

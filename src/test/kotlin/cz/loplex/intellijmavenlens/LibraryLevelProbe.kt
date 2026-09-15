package cz.loplex.intellijmavenlens

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.workspace.jps.entities.LibraryDependency
import com.intellij.platform.workspace.jps.entities.LibraryTableId
import com.intellij.platform.workspace.jps.entities.ModuleEntity

/**
 * Reads the platform's `LibraryLevelsTracker` counter directly, so that a test can state what the
 * tracker holds instead of waiting for the error it logs when the counter goes wrong.
 *
 * Watching for that error is not a usable instrument: `LibraryLevelsTracker.LOG` is a static field
 * initialised when the class is loaded, so whether a `LoggedErrorProcessor` ever sees it depends on
 * when that load happens relative to the test logger being installed, and grepping a shared,
 * buffered log cannot tell "the counter was right" from "the situation never arose". The counter
 * itself can be read at any moment and says both.
 *
 * Reflection is unavoidable: the tracker is `@ApiStatus.Internal` and not on the plugin's compile
 * classpath. Only test code depends on it.
 */
internal object LibraryLevelProbe {

    /** The level name project-level libraries - the only kind Maven Lens creates - are tracked under. */
    const val PROJECT_LEVEL = "project"

    /**
     * What the platform believes and what the workspace model actually holds, read together.
     *
     * Both halves are read inside one read action on purpose. The tracker is maintained from inside
     * the very write action that changes the entities, so the two agree only between write actions;
     * reading them separately means reading two different moments, and an unlocked read of the
     * counter next to a read-action read of the model produced exactly that false mismatch before.
     */
    fun snapshot(project: Project, level: String = PROJECT_LEVEL): LibraryLevelSnapshot =
        ReadAction.compute<LibraryLevelSnapshot, RuntimeException> {
            val tracker = tracker(project)
            LibraryLevelSnapshot(
                tracked = trackedOccurrences(tracker, level),
                actual = actualOccurrences(project, level),
                listening = !(tracker.javaClass
                    .getMethod("isNotUsed", String::class.java)
                    .invoke(tracker, level) as Boolean),
            )
        }

    /**
     * How many module dependencies the tracker believes exist at `level`.
     *
     * Zero and "never seen" are the same reading by construction: `MultiSet.remove` drops a key
     * whose previous value was `<= 1`, so an over-decremented counter is erased rather than kept
     * negative. That is why it only means something next to [LibraryLevelSnapshot.actual].
     */
    private fun trackedOccurrences(tracker: Any, level: String): Int {
        val multiSet = tracker.javaClass.getDeclaredField("libraryLevels")
            .apply { isAccessible = true }
            .get(tracker)
        val storage = multiSet.javaClass.getDeclaredField("storage")
            .apply { isAccessible = true }
            .get(multiSet)
        return storage.javaClass.getMethod("getInt", Any::class.java).invoke(storage, level) as Int
    }

    /**
     * How many module dependencies at `level` the workspace model actually holds - the number the
     * tracker is supposed to be counting. Counted the way `ModuleDependencyIndexImpl` counts it:
     * per dependency entry rather than per distinct library, and ignoring module-level libraries.
     */
    private fun actualOccurrences(project: Project, level: String): Int =
        WorkspaceModel.getInstance(project).currentSnapshot
            .entities(ModuleEntity::class.java)
            .flatMap { it.dependencies.asSequence() }
            .filterIsInstance<LibraryDependency>()
            .map { it.library.tableId }
            .filter { it !is LibraryTableId.ModuleLibraryTableId && it.level == level }
            .count()

    private fun tracker(project: Project): Any {
        val trackerClass = Class.forName(
            "com.intellij.workspaceModel.ide.impl.legacyBridge.module.LibraryLevelsTracker",
            false,
            WorkspaceModel::class.java.classLoader,
        )
        return project.getService(trackerClass)
            ?: error("LibraryLevelsTracker is not available for this project")
    }
}

/**
 * @param tracked what `LibraryLevelsTracker` counts for the level.
 * @param actual how many module dependencies at that level the workspace model holds.
 * @param listening whether the platform still listens on that level's library table - it attaches
 *   the listener when the counter leaves zero and detaches it when the counter returns there, so a
 *   counter that lost an increment also means changes to those libraries stop reaching the modules.
 */
internal data class LibraryLevelSnapshot(val tracked: Int, val actual: Int, val listening: Boolean) {
    val consistent: Boolean get() = tracked == actual
}

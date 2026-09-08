package cz.loplex.intellijmavenlens

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

/**
 * Per-project switch deciding whether Maven Lens attaches plugin libraries at all.
 *
 * Stored in the workspace file rather than in `.idea/`: whether someone wants Maven plugin
 * internals indexed is a personal, machine-local preference - flipping it must not turn up as a
 * shared project change in everyone else's working copy.
 */
@Service(Service.Level.PROJECT)
@State(name = "MavenLensSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class MavenLensSettings : SimplePersistentStateComponent<MavenLensSettings.MavenLensState>(MavenLensState()) {

    var enabled: Boolean
        get() = state.enabled
        set(value) {
            state.enabled = value
        }

    class MavenLensState : BaseState() {
        var enabled: Boolean by property(true)
    }
}

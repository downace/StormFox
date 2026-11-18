package com.github.downace.stormfox.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.github.downace.stormfox.MagoVersionService

internal data class MagoSettingsSnapshot(val command: String, val executionRoot: String)

@Service(Service.Level.APP)
@State(name = "MagoSettings", storages = [Storage("mago.xml", roamingType = RoamingType.DISABLED)])
class MagoSettings : PersistentStateComponent<MagoSettings.State> {

    class State {
        var magoPath: String = ""
            set(value) {
                if (field == value) return
                field = value
                onPathChanged?.invoke(snapshot())
            }

        @Transient
        @get:com.intellij.util.xmlb.annotations.Transient
        internal var onPathChanged: ((MagoSettingsSnapshot) -> Unit)? = null

        @JvmField
        var configPath: String = ""

        var executionRoot: String = ""
            set(value) {
                if (field == value) return
                field = value
                onPathChanged?.invoke(snapshot())
            }

        internal fun snapshot() = MagoSettingsSnapshot(magoPath, executionRoot)

        @JvmField
        var enabled: Boolean = true

        @JvmField
        var twigEnabled: Boolean? = null

        @JvmField
        var showStatusWidget: Boolean = true
    }

    private var state = attach(State())

    private fun attach(value: State): State {
        value.onPathChanged = { snapshot -> MagoVersionService.getInstance().refresh(snapshot) }
        return value
    }

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state.onPathChanged = null
        this.state = attach(state)
        ApplicationManager.getApplication().getServiceIfCreated(MagoVersionService::class.java)
            ?.refresh(state.snapshot())
    }

    companion object {
        @Volatile
        @org.jetbrains.annotations.TestOnly
        internal var lookupGuard: (() -> Unit)? = null

        fun getInstance(): MagoSettings {
            lookupGuard?.invoke()
            return ApplicationManager.getApplication().getService(MagoSettings::class.java)
        }
    }
}

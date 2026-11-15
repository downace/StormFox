package com.github.downace.stormfox.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.github.downace.stormfox.MagoVersionService

@Service(Service.Level.APP)
@State(name = "MagoSettings", storages = [Storage("mago.xml", roamingType = RoamingType.DISABLED)])
class MagoSettings : PersistentStateComponent<MagoSettings.State> {

    class State {
        var magoPath: String = ""
            set(value) {
                if (field == value) return
                field = value
                onPathChanged?.invoke(value)
            }

        @Transient
        internal var onPathChanged: ((String) -> Unit)? = null

        @JvmField
        var configPath: String = ""

        @JvmField
        var enabled: Boolean = true
    }

    private var state = attach(State())

    private fun attach(value: State): State {
        value.onPathChanged = { MagoVersionService.getInstance().refresh(it) }
        return value
    }

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state.onPathChanged = null
        this.state = attach(state)
        ApplicationManager.getApplication().getServiceIfCreated(MagoVersionService::class.java)?.refresh(state.magoPath)
    }

    companion object {
        fun getInstance(): MagoSettings = ApplicationManager.getApplication().getService(MagoSettings::class.java)
    }
}

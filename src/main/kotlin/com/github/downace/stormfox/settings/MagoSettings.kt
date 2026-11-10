package com.github.downace.stormfox.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(name = "MagoSettings", storages = [Storage("mago.xml", roamingType = RoamingType.DISABLED)])
class MagoSettings : PersistentStateComponent<MagoSettings.State> {

    class State {
        @JvmField
        var magoPath: String = ""

        @JvmField
        var configPath: String = ""

        /**
         * Off means the built-in PHP formatter handles Reformat Code and nothing is formatted on save.
         * It is on by default so that installing the plugin does not silently change the behaviour.
         */
        @JvmField
        var enabled: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    companion object {
        fun getInstance(): MagoSettings = ApplicationManager.getApplication().getService(MagoSettings::class.java)
    }
}

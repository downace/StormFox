package com.github.downace.stormfox.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.github.downace.stormfox.MagoVersionService
import com.intellij.util.messages.Topic

internal data class MagoSettingsSnapshot(val command: String, val executionRoot: String, val configPath: String = "", val diagnosticOptions: MagoDiagnosticOptions = MagoDiagnosticOptions())

internal fun interface MagoSettingsListener {
    fun settingsChanged(snapshot: MagoSettingsSnapshot)

    companion object {
        val TOPIC = Topic.create("Mago settings changed", MagoSettingsListener::class.java)
    }
}

@Service(Service.Level.APP)
@State(name = "MagoSettings", storages = [Storage("mago.xml", roamingType = RoamingType.DISABLED)])
internal class LegacyMagoSettings : PersistentStateComponent<MagoSettings.State> {
    private var state = MagoSettings.State()
    override fun getState() = state
    override fun loadState(state: MagoSettings.State) { this.state = state }
}

@Service(Service.Level.PROJECT)
@State(name = "MagoProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class MagoSettings(private val project: Project) : PersistentStateComponent<MagoSettings.State> {

    class State {
        @JvmField
        var migrationCompleted: Boolean = false
        var magoPath: String = ""
            set(value) {
                if (field == value) return
                field = value
                onPathChanged?.invoke(snapshot())
            }

        @Transient
        @get:com.intellij.util.xmlb.annotations.Transient
        internal var onPathChanged: ((MagoSettingsSnapshot) -> Unit)? = null

        var configPath: String = ""
            set(value) {
                if (field == value) return
                field = value
                onPathChanged?.invoke(snapshot())
            }

        var executionRoot: String = ""
            set(value) {
                if (field == value) return
                field = value
                onPathChanged?.invoke(snapshot())
            }

        var lintMode: MagoLintMode = MagoLintMode.NORMAL
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var lintIgnoreBaseline: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var lintFixableOnly: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var twigLintMode: MagoLintMode = MagoLintMode.NORMAL
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var twigLintIgnoreBaseline: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var twigLintFixableOnly: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var analyzeIgnoreBaseline: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var analyzeNoStubs: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var analyzeSkipIgnores: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var analyzeFixableOnly: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var guardMode: MagoGuardMode = MagoGuardMode.BOTH
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var guardIgnoreBaseline: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var guardNoStubs: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        var guardFixableOnly: Boolean = false
            set(value) { if (field != value) { field = value; onPathChanged?.invoke(snapshot()) } }

        internal fun diagnosticOptions() = MagoDiagnosticOptions(
            lintMode = lintMode, lintIgnoreBaseline = lintIgnoreBaseline, lintFixableOnly = lintFixableOnly,
            twigLintMode = twigLintMode, twigLintIgnoreBaseline = twigLintIgnoreBaseline, twigLintFixableOnly = twigLintFixableOnly,
            analyzeIgnoreBaseline = analyzeIgnoreBaseline, analyzeNoStubs = analyzeNoStubs,
            analyzeSkipIgnores = analyzeSkipIgnores, analyzeFixableOnly = analyzeFixableOnly,
            guardMode = guardMode, guardIgnoreBaseline = guardIgnoreBaseline,
            guardNoStubs = guardNoStubs, guardFixableOnly = guardFixableOnly,
        )
        internal fun snapshot() = MagoSettingsSnapshot(magoPath, executionRoot, configPath, diagnosticOptions())

        @JvmField
        var enabled: Boolean = true

        @JvmField
        // Null preserves the pre-Twig formatter choice for existing settings.
        var twigEnabled: Boolean? = null

        @JvmField
        var showStatusWidget: Boolean = true
    }

    private var state = attach(State())

    private fun attach(value: State): State {
        var previous = value.snapshot()
        value.onPathChanged = { snapshot ->
            val versionChanged = previous.command != snapshot.command || previous.executionRoot != snapshot.executionRoot
            previous = snapshot
            project.messageBus.syncPublisher(MagoSettingsListener.TOPIC).settingsChanged(snapshot)
            if (versionChanged) MagoVersionService.getInstance(project).refresh(snapshot)
        }
        return value
    }

    override fun getState(): State = state

    override fun loadState(state: State) {
        val previous = this.state.snapshot()
        this.state.onPathChanged = null
        this.state = attach(state)
        val snapshot = state.snapshot()
        if (snapshot != previous) project.messageBus.syncPublisher(MagoSettingsListener.TOPIC).settingsChanged(snapshot)
        project.getServiceIfCreated(MagoVersionService::class.java)
            ?.refresh(snapshot)
    }

    override fun noStateLoaded() {
        val legacy = ApplicationManager.getApplication().getService(LegacyMagoSettings::class.java).state
        loadState(State().also {
            it.magoPath = legacy.magoPath
            it.configPath = legacy.configPath
            it.executionRoot = legacy.executionRoot
            it.enabled = legacy.enabled
            it.twigEnabled = legacy.twigEnabled
            it.showStatusWidget = legacy.showStatusWidget
            it.migrationCompleted = true
        })
    }

    companion object {
        @Volatile
        @org.jetbrains.annotations.TestOnly
        internal var lookupGuard: (() -> Unit)? = null

        fun getInstance(project: Project): MagoSettings {
            lookupGuard?.invoke()
            return project.getService(MagoSettings::class.java)
        }
    }
}
